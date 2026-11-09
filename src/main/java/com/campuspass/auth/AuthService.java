package com.campuspass.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campuspass.common.ApiException;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return count", Long.class);

    private final AccountMapper accounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final StringRedisTemplate redis;
    private final int accountLimit;
    private final int ipLimit;

    public AuthService(AccountMapper accounts, PasswordEncoder passwordEncoder, TokenService tokens,
                       StringRedisTemplate redis,
                       @Value("${campuspass.rate-limit.login-account-per-minute:10}") int accountLimit,
                       @Value("${campuspass.rate-limit.login-ip-per-minute:60}") int ipLimit) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.redis = redis;
        this.accountLimit = accountLimit;
        this.ipLimit = ipLimit;
    }

    @Transactional
    public Account register(String username, String password, String displayName) {
        if (accounts.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username)) > 0) {
            throw ApiException.conflict("USERNAME_TAKEN", "该账号已注册");
        }
        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setDisplayName(displayName);
        account.setRole("STUDENT");
        account.setStatus("ACTIVE");
        accounts.insert(account);
        return account;
    }

    public AuthResponse login(String username, String password, String ip) {
        limit("auth:limit:user:" + TokenService.sha256(username.toLowerCase()), accountLimit);
        limit("auth:limit:ip:" + TokenService.sha256(ip), ipLimit);
        Account account = accounts.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw ApiException.unauthorized("BAD_CREDENTIALS", "账号或密码错误");
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "账号已停用");
        }
        return response(account, tokens.issue(account));
    }

    public AuthResponse refresh(String refreshToken) {
        String sessionId = tokens.sessionIdFromRefresh(refreshToken);
        if (sessionId == null) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh Token 无效");
        }
        Map<Object, Object> session = redis.opsForHash().entries("auth:session:" + sessionId);
        if (session.isEmpty()) {
            throw ApiException.unauthorized("SESSION_REVOKED", "登录会话已撤销");
        }
        Long userId = Long.valueOf(session.get("userId").toString());
        Account account = accounts.selectById(userId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            tokens.revoke(sessionId, userId);
            throw ApiException.unauthorized("ACCOUNT_DISABLED", "账号已停用");
        }
        TokenService.TokenPair pair = tokens.rotate(sessionId, userId, refreshToken, account);
        return response(account, pair);
    }

    public void logout(String sessionId, Long userId) {
        tokens.revoke(sessionId, userId);
    }

    public Account me(Long userId) {
        Account account = accounts.selectById(userId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw ApiException.unauthorized("ACCOUNT_DISABLED", "账号不存在或已停用");
        }
        return account;
    }

    @Transactional
    public Account createManagedAccount(String username, String password, String displayName, String role) {
        if (!role.equals("ORGANIZER") && !role.equals("ADMIN")) {
            throw ApiException.badRequest("INVALID_ROLE", "管理账号角色必须为 ORGANIZER 或 ADMIN");
        }
        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setDisplayName(displayName);
        account.setRole(role);
        account.setStatus("ACTIVE");
        accounts.insert(account);
        return account;
    }

    @Transactional
    public void setAccountStatus(Long userId, String status) {
        if (!status.equals("ACTIVE") && !status.equals("DISABLED")) {
            throw ApiException.badRequest("INVALID_STATUS", "status 只能是 ACTIVE 或 DISABLED");
        }
        int updated = accounts.update(null, new LambdaUpdateWrapper<Account>()
                .eq(Account::getId, userId).set(Account::getStatus, status));
        if (updated == 0) throw ApiException.notFound("ACCOUNT_NOT_FOUND", "账号不存在");
        if (status.equals("DISABLED")) tokens.revokeAll(userId);
    }

    private void limit(String key, int max) {
        Long count = redis.execute(RATE_LIMIT_SCRIPT, java.util.List.of(key), "60");
        if (count != null && count > max) throw ApiException.tooManyRequests("LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后再试");
    }

    private AuthResponse response(Account account, TokenService.TokenPair pair) {
        return new AuthResponse(pair.accessToken(), pair.refreshToken(), pair.accessExpiresInSeconds(),
                pair.refreshExpiresInSeconds(), publicAccount(account));
    }

    private PublicAccount publicAccount(Account account) {
        return new PublicAccount(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole());
    }

    public record PublicAccount(Long id, String username, String displayName, String role) { }
    public record AuthResponse(String accessToken, String refreshToken, long accessExpiresInSeconds,
                               long refreshExpiresInSeconds, PublicAccount user) { }
}

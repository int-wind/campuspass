package com.campuspass.auth;

import com.campuspass.common.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_SESSIONS_PREFIX = "auth:user-sessions:";
    private static final String SESSION_USER = "userId";
    private static final String SESSION_ROLE = "role";
    private static final String SESSION_REFRESH_HASH = "refreshHash";
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'refreshHash') ~= ARGV[1] then return 0 end " +
            "redis.call('HSET', KEYS[1], 'refreshHash', ARGV[2]) " +
            "redis.call('PEXPIRE', KEYS[1], ARGV[3]) return 1", Long.class);

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public TokenPair issue(Account account) {
        String sessionId = UUID.randomUUID().toString();
        String refreshToken = newRefreshToken(sessionId);
        redis.opsForHash().put(SESSION_PREFIX + sessionId, SESSION_USER, account.getId().toString());
        redis.opsForHash().put(SESSION_PREFIX + sessionId, SESSION_ROLE, account.getRole());
        redis.opsForHash().put(SESSION_PREFIX + sessionId, SESSION_REFRESH_HASH, sha256(refreshToken));
        redis.expire(SESSION_PREFIX + sessionId, properties.getRefreshTtl());
        redis.opsForSet().add(USER_SESSIONS_PREFIX + account.getId(), sessionId);
        redis.expire(USER_SESSIONS_PREFIX + account.getId(), properties.getRefreshTtl().plusDays(1));
        Instant now = Instant.now();
        String accessToken = createAccessToken(account, sessionId, now);
        return new TokenPair(accessToken, refreshToken, properties.getAccessTtl().toSeconds(),
                properties.getRefreshTtl().toSeconds());
    }

    public Claims parseAccess(String token) {
        try {
            return Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token).getBody();
        } catch (Exception ex) {
            throw ApiException.unauthorized("INVALID_ACCESS_TOKEN", "Access Token 无效或已过期");
        }
    }

    public TokenPair rotate(String sessionId, Long userId, String oldRefreshToken, Account account) {
        String sessionKey = SESSION_PREFIX + sessionId;
        String newToken = newRefreshToken(sessionId);
        Long rotated = redis.execute(ROTATE_SCRIPT, java.util.List.of(sessionKey), sha256(oldRefreshToken),
                sha256(newToken), Long.toString(properties.getRefreshTtl().toMillis()));
        if (rotated == null || rotated != 1L) {
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED", "Refresh Token 已使用、过期或已撤销");
        }
        redis.expire(USER_SESSIONS_PREFIX + userId, properties.getRefreshTtl().plusDays(1));
        Instant now = Instant.now();
        return new TokenPair(createAccessToken(account, sessionId, now), newToken,
                properties.getAccessTtl().toSeconds(), properties.getRefreshTtl().toSeconds());
    }

    public void revoke(String sessionId, Long userId) {
        redis.delete(SESSION_PREFIX + sessionId);
        redis.opsForSet().remove(USER_SESSIONS_PREFIX + userId, sessionId);
    }

    public void revokeAll(Long userId) {
        var sessions = redis.opsForSet().members(USER_SESSIONS_PREFIX + userId);
        if (sessions != null && !sessions.isEmpty()) {
            redis.delete(sessions.stream().map(id -> SESSION_PREFIX + id).toList());
        }
        redis.delete(USER_SESSIONS_PREFIX + userId);
    }

    public String sessionIdFromRefresh(String refreshToken) {
        if (refreshToken == null) return null;
        int separator = refreshToken.indexOf('.');
        if (separator < 36) return null;
        String sessionId = refreshToken.substring(0, separator);
        try {
            UUID.fromString(sessionId);
            return sessionId;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String newRefreshToken(String sessionId) {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String createAccessToken(Account account, String sessionId, Instant now) {
        return Jwts.builder()
                .setSubject(account.getId().toString())
                .setId(sessionId)
                .claim("role", account.getRole())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(properties.getAccessTtl())))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record TokenPair(String accessToken, String refreshToken, long accessExpiresInSeconds,
                            long refreshExpiresInSeconds) { }
}

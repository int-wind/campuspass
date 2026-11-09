package com.campuspass.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class TokenServiceTest {
    private TokenService tokens;
    private AuthProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        properties = new AuthProperties();
        properties.setJwtSecret("unit-test-secret-at-least-32-bytes-long");
        tokens = new TokenService(redis, properties);
    }

    @Test
    void issuesShortLivedAccessAndOpaqueSessionBoundRefreshToken() {
        Account account = new Account();
        account.setId(42L);
        account.setRole("STUDENT");
        TokenService.TokenPair pair = tokens.issue(account);

        var claims = tokens.parseAccess(pair.accessToken());
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role", String.class)).isEqualTo("STUDENT");
        assertThat(pair.accessExpiresInSeconds()).isEqualTo(900);
        assertThat(pair.refreshExpiresInSeconds()).isEqualTo(604800);
        assertThat(tokens.sessionIdFromRefresh(pair.refreshToken())).isEqualTo(claims.getId());
        assertThat(TokenService.sha256(pair.refreshToken())).hasSize(64);
        assertThat(pair.refreshToken()).isNotEqualTo("42");
        assertThat(pair.refreshToken().split("\\.")).hasSize(2);
    }

    @Test
    void rejectsMalformedRefreshTokenAndInvalidAccessToken() {
        assertThat(tokens.sessionIdFromRefresh("short")).isNull();
        assertThatThrownBy(() -> tokens.parseAccess("not-a-jwt"))
                .isInstanceOf(com.campuspass.common.ApiException.class)
                .hasMessageContaining("Access Token");
    }

    @Test
    void rejectsExpiredAccessToken() throws InterruptedException {
        properties.setAccessTtl(Duration.ofMillis(20));
        Account account = new Account();
        account.setId(42L);
        account.setRole("STUDENT");
        TokenService.TokenPair pair = tokens.issue(account);
        Thread.sleep(50);
        assertThatThrownBy(() -> tokens.parseAccess(pair.accessToken()))
                .isInstanceOf(com.campuspass.common.ApiException.class)
                .hasMessageContaining("Access Token");
    }
}

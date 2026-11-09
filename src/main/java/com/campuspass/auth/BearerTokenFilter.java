package com.campuspass.auth;

import com.campuspass.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public BearerTokenFilter(TokenService tokens, StringRedisTemplate redis, ObjectMapper mapper) {
        this.tokens = tokens;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = tokens.parseAccess(header.substring(7));
                String sessionKey = "auth:session:" + claims.getId();
                Map<Object, Object> session = redis.opsForHash().entries(sessionKey);
                String userId = claims.getSubject();
                if (session.isEmpty() || !userId.equals(session.get("userId"))) {
                    throw ApiException.unauthorized("SESSION_REVOKED", "登录会话已撤销");
                }
                String role = String.valueOf(session.get("role"));
                var authentication = new UsernamePasswordAuthenticationToken(userId, null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                authentication.setDetails(claims.getId());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ApiException ex) {
                SecurityContextHolder.clearContext();
                response.setStatus(ex.getStatus().value());
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getWriter(), Map.of("code", ex.getCode(), "message", ex.getMessage()));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

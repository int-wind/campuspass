package com.campuspass.auth;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthService.PublicAccount register(@Valid @RequestBody RegisterRequest request) {
        Account account = authService.register(request.username(), request.password(), request.displayName());
        return new AuthService.PublicAccount(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole());
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        return authService.login(request.username(), request.password(), ip);
    }

    @PostMapping("/refresh")
    public AuthService.AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(Authentication authentication) {
        authService.logout((String) authentication.getDetails(), Long.valueOf(authentication.getName()));
        return Map.of("status", "logged_out");
    }

    @GetMapping("/me")
    public AuthService.PublicAccount me(Authentication authentication) {
        Account account = authService.me(Long.valueOf(authentication.getName()));
        return new AuthService.PublicAccount(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole());
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{4,64}") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName) { }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record RefreshRequest(@NotBlank String refreshToken) { }
}

package com.campuspass.auth;

import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountController {
    private final AuthService authService;
    public AdminAccountController(AuthService authService) { this.authService = authService; }

    @PostMapping
    public AuthService.PublicAccount create(@Valid @RequestBody CreateManagedRequest request) {
        Account account = authService.createManagedAccount(request.username(), request.password(),
                request.displayName(), request.role());
        return new AuthService.PublicAccount(account.getId(), account.getUsername(), account.getDisplayName(), account.getRole());
    }

    @PatchMapping("/{id}/status")
    public Map<String, String> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        authService.setAccountStatus(id, request.status());
        return Map.of("status", request.status());
    }

    public record CreateManagedRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{4,64}") String username,
                                       @NotBlank @Size(min = 8, max = 72) String password,
                                       @NotBlank @Size(max = 80) String displayName,
                                       @NotBlank String role) { }
    public record StatusRequest(@NotBlank String status) { }
}

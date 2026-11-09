package com.campuspass.enrollment;

import com.campuspass.common.ApiException;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EnrollmentController {
    private final EnrollmentService enrollments;
    public EnrollmentController(EnrollmentService enrollments) { this.enrollments = enrollments; }

    @PostMapping("/events/{eventId}/enrollments")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<EnrollmentService.AcceptedRequest> apply(Authentication auth, @PathVariable Long eventId,
                                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.accepted().body(enrollments.apply(userId(auth), eventId, key));
    }

    @GetMapping("/enrollment-requests/{requestId}")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.RequestResult requestStatus(Authentication auth, @PathVariable String requestId) {
        return enrollments.requestStatus(userId(auth), requestId);
    }

    @GetMapping("/enrollments/mine")
    @PreAuthorize("hasRole('STUDENT')")
    public Object mine(Authentication auth, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return enrollments.mine(userId(auth), page, size);
    }

    @GetMapping("/enrollments/{enrollmentId}/queue-position")
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String, Long> queuePosition(Authentication auth, @PathVariable Long enrollmentId) {
        return Map.of("position", enrollments.queuePosition(userId(auth), enrollmentId));
    }

    @PostMapping("/enrollments/{enrollmentId}/confirm")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentView confirm(Authentication auth, @PathVariable Long enrollmentId) {
        return enrollments.confirm(userId(auth), enrollmentId);
    }

    @PostMapping("/enrollments/{enrollmentId}/cancel")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentView cancel(Authentication auth, @PathVariable Long enrollmentId) {
        return enrollments.cancel(userId(auth), enrollmentId);
    }

    @PostMapping("/enrollments/{enrollmentId}/checkin-code")
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String, String> issueCheckinCode(Authentication auth, @PathVariable Long enrollmentId) {
        return Map.of("code", enrollments.issueCheckinCode(userId(auth), enrollmentId));
    }

    @PostMapping("/enrollments/{enrollmentId}/checkin")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public Map<String, String> checkin(Authentication auth, @PathVariable Long enrollmentId,
                                       @Valid @RequestBody CheckinRequest request) {
        long actor = userId(auth);
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        enrollments.checkin(actor, admin, enrollmentId, request.code());
        return Map.of("status", "CHECKED_IN");
    }

    private Long userId(Authentication auth) { return Long.valueOf(auth.getName()); }
    public record CheckinRequest(@NotBlank String code) { }
}

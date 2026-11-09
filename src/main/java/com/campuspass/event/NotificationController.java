package com.campuspass.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasRole('STUDENT')")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<NotificationView> list(Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        return jdbc.query("SELECT id,event_id,enrollment_id,type,payload,read_at,created_at FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 100",
                (rs, row) -> new NotificationView(rs.getLong("id"), rs.getLong("event_id"), (Long) rs.getObject("enrollment_id"),
                        rs.getString("type"), rs.getString("payload"),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime()), userId);
    }

    @PatchMapping("/{id}/read")
    public Map<String, String> markRead(Authentication auth, @PathVariable Long id) {
        int changed = jdbc.update("UPDATE notifications SET read_at=NOW(3) WHERE id=? AND user_id=? AND read_at IS NULL", id, Long.valueOf(auth.getName()));
        if (changed == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=? AND user_id=?", Integer.class, id, Long.valueOf(auth.getName()));
            if (exists == null || exists == 0) throw com.campuspass.common.ApiException.notFound("NOTIFICATION_NOT_FOUND", "站内通知不存在");
        }
        return Map.of("status", "READ");
    }

    public record NotificationView(Long id, Long eventId, Long enrollmentId, String type, String payload,
                                   LocalDateTime readAt, LocalDateTime createdAt) { }
}

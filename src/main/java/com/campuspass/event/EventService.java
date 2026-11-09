package com.campuspass.event;

import com.campuspass.common.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcTemplate jdbc;
    private final EventCache cache;

    public EventService(JdbcTemplate jdbc, EventCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public List<EventDetail> list(int page, int size) {
        int safePage = Math.max(0, Math.min(page, 10000));
        int safeSize = Math.max(1, Math.min(size, 100));
        return jdbc.query("SELECT * FROM events WHERE status IN ('PUBLISHED','ENROLLMENT_CLOSED','ENDED') ORDER BY starts_at,id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), safeSize, safePage * safeSize);
    }

    public EventDetail get(Long id) {
        return cache.get(id, () -> jdbc.query("SELECT * FROM events WHERE id=?", (rs, row) -> map(rs), id)
                .stream().findFirst().orElse(null));
    }

    @Transactional
    public EventDetail create(Long organizerId, EventWriteRequest request) {
        validate(request);
        jdbc.update("INSERT INTO events(organizer_id,title,description,location,starts_at,ends_at,enrollment_opens_at,enrollment_closes_at,capacity,status) VALUES(?,?,?,?,?,?,?,?,?,'DRAFT')",
                organizerId, request.title(), request.description(), request.location(), request.startsAt(),
                request.endsAt(), request.enrollmentOpensAt(), request.enrollmentClosesAt(), request.capacity());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return getFromDb(id);
    }

    @Transactional
    public EventDetail update(Long actorId, boolean admin, Long id, EventWriteRequest request) {
        validate(request);
        EventDetail event = getForUpdate(id);
        requireOwner(event, actorId, admin);
        if (!event.status().equals("DRAFT")) throw ApiException.conflict("EVENT_ALREADY_PUBLISHED", "活动发布后不能修改活动信息和名额");
        jdbc.update("UPDATE events SET title=?,description=?,location=?,starts_at=?,ends_at=?,enrollment_opens_at=?,enrollment_closes_at=?,capacity=? WHERE id=?",
                request.title(), request.description(), request.location(), request.startsAt(), request.endsAt(),
                request.enrollmentOpensAt(), request.enrollmentClosesAt(), request.capacity(), id);
        cache.invalidateAfterCommit(id);
        return getFromDb(id);
    }

    @Transactional
    public EventDetail publish(Long actorId, boolean admin, Long id) {
        EventDetail event = getForUpdate(id);
        requireOwner(event, actorId, admin);
        if (!event.status().equals("DRAFT")) throw ApiException.conflict("EVENT_NOT_DRAFT", "只有草稿活动可以发布");
        jdbc.update("UPDATE events SET status='PUBLISHED' WHERE id=?", id);
        cache.invalidateAfterCommit(id);
        return getFromDb(id);
    }

    @Transactional
    public void delete(Long actorId, boolean admin, Long id) {
        EventDetail event = getForUpdate(id);
        requireOwner(event, actorId, admin);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=?", Integer.class, id);
        if (count != null && count > 0) throw ApiException.conflict("EVENT_HAS_ENROLLMENTS", "已有报名记录的活动不能删除");
        jdbc.update("DELETE FROM events WHERE id=?", id);
        cache.invalidateAfterCommit(id);
    }

    public EventDetail getForUpdate(Long id) {
        return jdbc.query("SELECT * FROM events WHERE id=? FOR UPDATE", (rs, row) -> map(rs), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "活动不存在"));
    }

    public EventDetail getFromDb(Long id) {
        return jdbc.query("SELECT * FROM events WHERE id=?", (rs, row) -> map(rs), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "活动不存在"));
    }

    static EventDetail map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventDetail(rs.getLong("id"), rs.getLong("organizer_id"), rs.getString("title"),
                rs.getString("description"), rs.getString("location"), rs.getTimestamp("starts_at").toLocalDateTime(),
                rs.getTimestamp("ends_at").toLocalDateTime(), rs.getTimestamp("enrollment_opens_at").toLocalDateTime(),
                rs.getTimestamp("enrollment_closes_at").toLocalDateTime(), rs.getInt("capacity"),
                rs.getInt("occupied_count"), rs.getString("status"), rs.getLong("waitlist_sequence"));
    }

    public static void requireOwner(EventDetail event, Long actorId, boolean admin) {
        if (!admin && !event.organizerId().equals(actorId)) throw ApiException.forbidden("NOT_EVENT_OWNER", "只能管理自己创建的活动");
    }

    private void validate(EventWriteRequest request) {
        if (request.startsAt().isBefore(LocalDateTime.now()) || !request.endsAt().isAfter(request.startsAt())) {
            throw ApiException.badRequest("INVALID_EVENT_TIME", "活动时间不合法");
        }
        if (request.enrollmentOpensAt().isAfter(request.enrollmentClosesAt())
                || request.enrollmentClosesAt().isAfter(request.startsAt())) {
            throw ApiException.badRequest("INVALID_ENROLLMENT_TIME", "报名时间必须早于或等于活动开始时间");
        }
        if (request.capacity() < 1 || request.capacity() > 100000) throw ApiException.badRequest("INVALID_CAPACITY", "名额必须在 1 到 100000 之间");
    }

    public record EventWriteRequest(String title, String description, String location, LocalDateTime startsAt,
                                    LocalDateTime endsAt, LocalDateTime enrollmentOpensAt,
                                    LocalDateTime enrollmentClosesAt, int capacity) { }
}

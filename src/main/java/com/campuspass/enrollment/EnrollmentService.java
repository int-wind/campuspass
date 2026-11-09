package com.campuspass.enrollment;

import com.campuspass.common.ApiException;
import com.campuspass.event.EventCache;
import com.campuspass.event.EventDetail;
import com.campuspass.event.EventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EnrollmentService {
    private static final String RESERVE_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('GET', KEYS[1]) end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 'DUPLICATE' end
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            if now < tonumber(ARGV[8]) or now >= tonumber(ARGV[9]) then return 'CLOSED' end
            local waiting = tonumber(ARGV[3])
            local occupied = tonumber(ARGV[4])
            local capacity = tonumber(ARGV[5])
            local reserved = tonumber(redis.call('GET', KEYS[6]) or '0')
            local result
            if waiting > 0 or redis.call('ZCARD', KEYS[3]) > 0 or occupied + reserved >= capacity then
              if tonumber(redis.call('GET', KEYS[4]) or '0') < tonumber(ARGV[7]) then redis.call('SET', KEYS[4], ARGV[7]) end
              local sequence = redis.call('INCR', KEYS[4])
              redis.call('ZADD', KEYS[3], sequence, ARGV[2])
              result = 'WAITLIST:' .. sequence
            else
              redis.call('SET', KEYS[5], ARGV[2], 'EX', ARGV[6])
              redis.call('INCR', KEYS[6])
              redis.call('EXPIRE', KEYS[6], ARGV[6])
              result = 'RESERVED'
            end
            redis.call('SET', KEYS[1], result, 'EX', 86400)
            redis.call('SADD', KEYS[2], ARGV[1])
            return result
            """;
    private static final DefaultRedisScript<String> RESERVE_SCRIPT = new DefaultRedisScript<>(RESERVE_LUA, String.class);
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end " +
            "redis.call('DEL', KEYS[1]); local n=redis.call('DECR', KEYS[2]); " +
            "if n < 0 then redis.call('SET', KEYS[2], 0, 'EX', 30) else redis.call('EXPIRE', KEYS[2], 30) end; return 1", Long.class);

    private final JdbcTemplate jdbc;
    private final EventService events;
    private final EventCache cache;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final boolean asyncSignup;
    private final SecureRandom random = new SecureRandom();

    public EnrollmentService(JdbcTemplate jdbc, EventService events, EventCache cache,
                             StringRedisTemplate redis, RabbitTemplate rabbit, ObjectMapper mapper,
                             @Value("${campuspass.enrollment.async:true}") boolean asyncSignup) {
        this.jdbc = jdbc;
        this.events = events;
        this.cache = cache;
        this.redis = redis;
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.asyncSignup = asyncSignup;
    }

    @Transactional
    public AcceptedRequest apply(Long userId, Long eventId, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "请提供 8 到 100 位 Idempotency-Key");
        }
        EventDetail event = events.getFromDb(eventId);
        LocalDateTime now = LocalDateTime.now();
        if (!event.status().equals("PUBLISHED") || now.isBefore(event.enrollmentOpensAt()) || !now.isBefore(event.enrollmentClosesAt())) {
            throw ApiException.conflict("ENROLLMENT_CLOSED", "活动当前不在报名时间内");
        }
        String requestHash = sha256(userId + ":" + eventId);
        List<AcceptedRequest> existingRequest = jdbc.query("SELECT id, status, result_code FROM enrollment_requests WHERE user_id=? AND event_id=? AND idempotency_key=?",
                (rs, row) -> new AcceptedRequest(rs.getString("id"), rs.getString("status"), rs.getString("result_code")), userId, eventId, idempotencyKey);
        if (!existingRequest.isEmpty()) return existingRequest.get(0);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=? AND user_id=? AND status IN ('WAITLIST','PENDING_CONFIRM','CONFIRMED','CHECKED_IN')",
                Integer.class, eventId, userId);
        if (active != null && active > 0) throw ApiException.conflict("ALREADY_ENROLLED", "已有有效报名或候补记录");
        Integer latestGeneration = jdbc.queryForObject("SELECT COALESCE(MAX(generation),0) FROM enrollments WHERE event_id=? AND user_id=?",
                Integer.class, eventId, userId);
        int generation = (latestGeneration == null ? 0 : latestGeneration) + 1;
        String requestId = UUID.randomUUID().toString();
        String marker = "campuspass:enroll:idempotency:" + userId + ":" + eventId + ":" + sha256(idempotencyKey);
        Long waitlistCount = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=? AND status='WAITLIST'", Long.class, eventId);
        String reservation = redis.execute(RESERVE_SCRIPT, List.of(marker, "campuspass:enroll:active:" + eventId,
                "campuspass:enroll:waitlist:" + eventId, "campuspass:enroll:sequence:" + eventId,
                "campuspass:enroll:reservation:" + eventId + ":" + requestId,
                "campuspass:enroll:reserved-count:" + eventId), userId.toString(), requestId,
                Long.toString(waitlistCount == null ? 0 : waitlistCount), Integer.toString(event.occupiedCount()),
                Integer.toString(event.capacity()), "30", Long.toString(event.waitlistSequence()),
                Long.toString(event.enrollmentOpensAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()),
                Long.toString(event.enrollmentClosesAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()));
        if (reservation == null || reservation.equals("DUPLICATE")) throw ApiException.conflict("ALREADY_ENROLLED", "已有有效报名记录");
        if (reservation.equals("CLOSED")) throw ApiException.conflict("ENROLLMENT_CLOSED", "活动当前不在报名时间内");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        redis.delete(marker);
                        releaseReservation(eventId, requestId);
                        redis.opsForZSet().remove("campuspass:enroll:waitlist:" + eventId, requestId);
                    }
                }
            });
        }
        // Lock the parent event before inserting FK-backed request/outbox rows. Under InnoDB
        // REPEATABLE READ, concurrent child inserts take shared parent locks; upgrading those
        // locks later in processEnrollment() can deadlock when several requests target one event.
        event = events.getForUpdate(eventId);
        now = LocalDateTime.now();
        if (!event.status().equals("PUBLISHED") || now.isBefore(event.enrollmentOpensAt()) || !now.isBefore(event.enrollmentClosesAt())) {
            throw ApiException.conflict("ENROLLMENT_CLOSED", "活动当前不在报名时间内");
        }
        try {
            jdbc.update("INSERT INTO enrollment_requests(id,user_id,event_id,idempotency_key,request_hash,generation,status) VALUES(?,?,?,?,?,?,'PENDING')",
                    requestId, userId, eventId, idempotencyKey, requestHash, generation);
            enqueueOutbox(requestId, eventId, "PROCESS_ENROLLMENT", new EventCommand(requestId));
        } catch (DuplicateKeyException ex) {
            List<AcceptedRequest> winner = jdbc.query("SELECT id,status,result_code FROM enrollment_requests WHERE user_id=? AND event_id=? AND idempotency_key=?",
                    (rs, row) -> new AcceptedRequest(rs.getString("id"), rs.getString("status"), rs.getString("result_code")),
                    userId, eventId, idempotencyKey);
            if (!winner.isEmpty()) return winner.get(0);
            throw ex;
        } catch (RuntimeException ex) {
            redis.delete(marker);
            releaseReservation(eventId, requestId);
            redis.opsForZSet().remove("campuspass:enroll:waitlist:" + eventId, requestId);
            throw ex;
        }
        if (!asyncSignup) processEnrollment(requestId);
        return new AcceptedRequest(requestId, asyncSignup ? "ACCEPTED" : "PROCESSED", null);
    }

    public RequestResult requestStatus(Long userId, String requestId) {
        return jdbc.query("SELECT id,user_id,event_id,status,result_code,error_message FROM enrollment_requests WHERE id=? AND user_id=?",
                (rs, row) -> new RequestResult(rs.getString("id"), rs.getString("status"), rs.getString("result_code"), rs.getString("error_message")),
                requestId, userId).stream().findFirst().orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "报名请求不存在"));
    }

    public List<EnrollmentView> mine(Long userId, int page, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, Math.min(page, 10000)) * limit;
        return jdbc.query("SELECT e.id,e.event_id,e.generation,e.status,e.queue_sequence,e.confirmation_expires_at,e.created_at,v.title,v.location,v.starts_at,v.ends_at FROM enrollments e JOIN events v ON v.id=e.event_id WHERE e.user_id=? ORDER BY e.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new EnrollmentView(rs.getLong("id"), rs.getLong("event_id"), rs.getString("title"), rs.getString("location"),
                        rs.getTimestamp("starts_at").toLocalDateTime(), rs.getTimestamp("ends_at").toLocalDateTime(), rs.getInt("generation"),
                        rs.getString("status"), (Long) rs.getObject("queue_sequence"),
                        rs.getTimestamp("confirmation_expires_at") == null ? null : rs.getTimestamp("confirmation_expires_at").toLocalDateTime()),
                userId, limit, offset);
    }

    public long queuePosition(Long userId, Long enrollmentId) {
        EnrollmentRow row = enrollment(enrollmentId);
        if (!row.userId().equals(userId)) throw ApiException.forbidden("NOT_ENROLLMENT_OWNER", "只能查看自己的候补位置");
        if (!row.status().equals("WAITLIST")) return 0;
        Long position = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=? AND status='WAITLIST' AND queue_sequence<=?",
                Long.class, row.eventId(), row.queueSequence());
        return position == null ? 0 : position;
    }

    @Transactional
    public EnrollmentView confirm(Long userId, Long enrollmentId) {
        EnrollmentRow row = enrollmentForUpdate(enrollmentId);
        if (!row.userId().equals(userId)) throw ApiException.forbidden("NOT_ENROLLMENT_OWNER", "只能确认自己的报名");
        if (!row.status().equals("PENDING_CONFIRM")) throw ApiException.conflict("NOT_AWAITING_CONFIRMATION", "报名不在待确认状态");
        if (row.confirmExpires() == null || !row.confirmExpires().isAfter(LocalDateTime.now())) {
            expireAndPromote(row);
            return view(enrollmentId);
        }
        jdbc.update("UPDATE enrollments SET status='CONFIRMED' WHERE id=? AND status='PENDING_CONFIRM'", enrollmentId);
        jdbc.update("INSERT INTO notifications(user_id,event_id,enrollment_id,type,payload) VALUES(?,?,?,'ENROLLMENT_CONFIRMED',JSON_OBJECT('message','报名已确认'))",
                row.userId(), row.eventId(), enrollmentId);
        return view(enrollmentId);
    }

    @Transactional
    public EnrollmentView cancel(Long userId, Long enrollmentId) {
        EnrollmentRow row = enrollmentForUpdate(enrollmentId);
        if (!row.userId().equals(userId)) throw ApiException.forbidden("NOT_ENROLLMENT_OWNER", "只能取消自己的报名");
        if (row.status().equals("CHECKED_IN")) throw ApiException.conflict("ALREADY_CHECKED_IN", "已核销报名不能取消");
        if (!row.status().equals("PENDING_CONFIRM") && !row.status().equals("CONFIRMED") && !row.status().equals("WAITLIST")) {
            throw ApiException.conflict("NOT_CANCELLABLE", "当前报名状态不能取消");
        }
        EventDetail event = events.getForUpdate(row.eventId());
        if (!LocalDateTime.now().isBefore(event.startsAt())) throw ApiException.conflict("EVENT_STARTED", "活动开始后不能取消");
        boolean occupied = !row.status().equals("WAITLIST");
        jdbc.update("UPDATE enrollments SET status='CANCELLED' WHERE id=?", enrollmentId);
        redis.opsForSet().remove("campuspass:enroll:active:" + row.eventId(), row.userId().toString());
        if (row.queueSequence() != null) redis.opsForZSet().remove("campuspass:enroll:waitlist:" + row.eventId(), row.requestId());
        if (occupied) {
            releaseSeat(row, "CANCEL");
            promoteNext(row.eventId());
        }
        cache.invalidateAfterCommit(row.eventId());
        return view(enrollmentId);
    }

    @Transactional
    public String issueCheckinCode(Long userId, Long enrollmentId) {
        EnrollmentRow row = enrollmentForUpdate(enrollmentId);
        if (!row.userId().equals(userId)) throw ApiException.forbidden("NOT_ENROLLMENT_OWNER", "只能为自己的报名获取核销码");
        EventDetail event = events.getForUpdate(row.eventId());
        if (!row.status().equals("CONFIRMED")) throw ApiException.conflict("NOT_CONFIRMED", "只有已确认报名可以获取核销码");
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(event.startsAt().minusHours(1)) || now.isAfter(event.endsAt().plusHours(2))) {
            throw ApiException.conflict("CHECKIN_WINDOW_CLOSED", "当前不在核销码有效时间窗口");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("UPDATE enrollments SET checkin_code_hash=? WHERE id=? AND status='CONFIRMED'", sha256(code), enrollmentId);
        return code;
    }

    @Transactional
    public void checkin(Long actorId, boolean admin, Long enrollmentId, String code) {
        EnrollmentRow row = enrollmentForUpdate(enrollmentId);
        EventDetail event = events.getForUpdate(row.eventId());
        EventService.requireOwner(event, actorId, admin);
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(event.startsAt().minusHours(1)) || now.isAfter(event.endsAt().plusHours(2))) {
            throw ApiException.conflict("CHECKIN_WINDOW_CLOSED", "当前不在活动核销时间窗口");
        }
        if (!row.status().equals("CONFIRMED")) throw ApiException.conflict("NOT_CONFIRMED", "只有已确认报名可以核销");
        if (row.checkinCodeHash() == null || !MessageDigest.isEqual(row.checkinCodeHash().getBytes(StandardCharsets.UTF_8), sha256(code).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.badRequest("INVALID_CHECKIN_CODE", "核销码错误或已失效");
        }
        int changed = jdbc.update("UPDATE enrollments SET status='CHECKED_IN',checked_in_at=NOW(3) WHERE id=? AND status='CONFIRMED'", enrollmentId);
        if (changed != 1) throw ApiException.conflict("ALREADY_CHECKED_IN", "该报名已核销");
    }

    @Transactional
    public void processEnrollment(String requestId) {
        RequestRow request = requestForUpdate(requestId);
        if (!request.status().equals("PENDING")) return;
        EventDetail event = events.getForUpdate(request.eventId());
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=? AND user_id=? AND status IN ('WAITLIST','PENDING_CONFIRM','CONFIRMED','CHECKED_IN')",
                Integer.class, request.eventId(), request.userId());
        if (active != null && active > 0) {
            updateRequest(requestId, "REJECTED", "ALREADY_ENROLLED", "已有有效报名");
            releaseReservation(request.eventId(), requestId);
            return;
        }
        Long waiting = jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE event_id=? AND status='WAITLIST'", Long.class, request.eventId());
        String reserveKey = "campuspass:enroll:reservation:" + request.eventId() + ":" + requestId;
        String marker = "campuspass:enroll:active:" + request.eventId();
        String requestMarker = "campuspass:enroll:idempotency:" + request.userId() + ":" + request.eventId() + ":" + sha256(request.idempotencyKey());
        String reservation = redis.execute(RESERVE_SCRIPT, List.of(requestMarker, marker,
                "campuspass:enroll:waitlist:" + request.eventId(), "campuspass:enroll:sequence:" + request.eventId(), reserveKey,
                "campuspass:enroll:reserved-count:" + request.eventId()),
                request.userId().toString(), requestId, Long.toString(waiting == null ? 0 : waiting),
                Integer.toString(event.occupiedCount()), Integer.toString(event.capacity()), "30", Long.toString(event.waitlistSequence()),
                Long.toString(event.enrollmentOpensAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()),
                Long.toString(event.enrollmentClosesAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()));
        if (reservation == null || reservation.equals("DUPLICATE")) {
            updateRequest(requestId, "REJECTED", "ALREADY_ENROLLED", "已有有效报名");
            return;
        }
        if (reservation.startsWith("WAITLIST:")) {
            Long sequence = Long.valueOf(reservation.substring("WAITLIST:".length()));
            jdbc.update("UPDATE events SET waitlist_sequence=GREATEST(waitlist_sequence,?) WHERE id=?", sequence, event.id());
            jdbc.update("INSERT INTO enrollments(event_id,user_id,generation,status,queue_sequence,request_id) VALUES(?,?,?,'WAITLIST',?,?)",
                    event.id(), request.userId(), request.generation(), sequence, requestId);
            jdbc.update("UPDATE enrollment_requests SET status='WAITLISTED',result_code='WAITLISTED' WHERE id=?", requestId);
            jdbc.update("INSERT INTO notifications(user_id,event_id,type,payload) VALUES(?,?,'WAITLISTED',JSON_OBJECT('message','已进入活动候补队列'))",
                    request.userId(), event.id());
        } else {
            int changed = jdbc.update("UPDATE events SET occupied_count=occupied_count+1 WHERE id=? AND occupied_count<capacity", event.id());
            if (changed != 1) {
                long sequence = nextQueueSequence(event.id());
                jdbc.update("INSERT INTO enrollments(event_id,user_id,generation,status,queue_sequence,request_id) VALUES(?,?,?,'WAITLIST',?,?)",
                        event.id(), request.userId(), request.generation(), sequence, requestId);
                jdbc.update("UPDATE enrollment_requests SET status='WAITLISTED',result_code='WAITLISTED' WHERE id=?", requestId);
            } else {
                LocalDateTime expiry = LocalDateTime.now().plusMinutes(5);
                jdbc.update("INSERT INTO enrollments(event_id,user_id,generation,status,request_id,confirmation_expires_at) VALUES(?,?,?,'PENDING_CONFIRM',?,?)",
                        event.id(), request.userId(), request.generation(), requestId, expiry);
                Long enrollmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO capacity_ledger(event_id,request_id,enrollment_id,generation,delta,reason) VALUES(?,?,?,?,1,'RESERVE')",
                        event.id(), requestId, enrollmentId, request.generation());
                jdbc.update("UPDATE enrollment_requests SET status='PENDING_CONFIRM',result_code='PENDING_CONFIRM',reservation_expires_at=? WHERE id=?", expiry, requestId);
                jdbc.update("INSERT INTO notifications(user_id,event_id,enrollment_id,type,payload) VALUES(?,?,?,'CONFIRMATION_REQUIRED',JSON_OBJECT('message','请在 5 分钟内确认报名'))",
                        request.userId(), event.id(), enrollmentId);
                enqueueOutbox(requestId, event.id(), "CONFIRMATION_TIMEOUT", new TimeoutCommand(enrollmentId));
            }
        }
        redis.opsForSet().add(marker, request.userId().toString());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) redis.opsForSet().remove(marker, request.userId().toString());
                }
            });
        }
        releaseReservation(request.eventId(), requestId);
        cache.invalidateAfterCommit(event.id());
    }

    @Transactional
    public void timeout(Long enrollmentId) {
        EnrollmentRow row;
        try { row = enrollmentForUpdate(enrollmentId); }
        catch (ApiException ex) { return; }
        if (!row.status().equals("PENDING_CONFIRM") || row.confirmExpires() == null || row.confirmExpires().isAfter(LocalDateTime.now())) return;
        expireAndPromote(row);
    }

    @Transactional
    public void recoverExpired() {
        List<Long> expired = jdbc.query("SELECT id FROM enrollments WHERE status='PENDING_CONFIRM' AND confirmation_expires_at<=NOW(3) ORDER BY id LIMIT 500",
                (rs, row) -> rs.getLong(1));
        for (Long id : expired) timeout(id);
    }

    private void expireAndPromote(EnrollmentRow row) {
        int changed = jdbc.update("UPDATE enrollments SET status='EXPIRED' WHERE id=? AND status='PENDING_CONFIRM' AND confirmation_expires_at<=NOW(3)", row.id());
        if (changed == 0) return;
        redis.opsForSet().remove("campuspass:enroll:active:" + row.eventId(), row.userId().toString());
        releaseSeat(row, "EXPIRE");
        updateRequest(row.requestId(), "EXPIRED", "CONFIRMATION_EXPIRED", "确认时间已过期");
        jdbc.update("INSERT INTO notifications(user_id,event_id,enrollment_id,type,payload) VALUES(?,?,?,'ENROLLMENT_EXPIRED',JSON_OBJECT('message','报名确认超时'))",
                row.userId(), row.eventId(), row.id());
        promoteNext(row.eventId());
        cache.invalidateAfterCommit(row.eventId());
    }

    private void promoteNext(Long eventId) {
        while (true) {
            List<EnrollmentRow> candidates = jdbc.query("SELECT id,event_id,user_id,generation,status,queue_sequence,request_id,confirmation_expires_at,checkin_code_hash FROM enrollments WHERE event_id=? AND status='WAITLIST' ORDER BY queue_sequence,id LIMIT 1 FOR UPDATE",
                    (rs, row) -> mapEnrollment(rs), eventId);
            if (candidates.isEmpty()) return;
            EnrollmentRow candidate = candidates.get(0);
            int capacity = jdbc.update("UPDATE events SET occupied_count=occupied_count+1 WHERE id=? AND occupied_count<capacity", eventId);
            if (capacity == 0) return;
            LocalDateTime expiry = LocalDateTime.now().plusMinutes(5);
            int changed = jdbc.update("UPDATE enrollments SET status='PENDING_CONFIRM',confirmation_expires_at=? WHERE id=? AND status='WAITLIST'", expiry, candidate.id());
            if (changed == 0) { jdbc.update("UPDATE events SET occupied_count=occupied_count-1 WHERE id=?", eventId); continue; }
            redis.opsForZSet().remove("campuspass:enroll:waitlist:" + eventId, candidate.requestId());
            jdbc.update("INSERT INTO capacity_ledger(event_id,request_id,enrollment_id,generation,delta,reason) VALUES(?,?,?,?,1,'PROMOTE')",
                    eventId, candidate.requestId(), candidate.id(), candidate.generation());
            jdbc.update("UPDATE enrollment_requests SET status='PENDING_CONFIRM',result_code='PENDING_CONFIRM',reservation_expires_at=? WHERE id=?",
                    expiry, candidate.requestId());
            jdbc.update("INSERT INTO notifications(user_id,event_id,enrollment_id,type,payload) VALUES(?,?,?,'WAITLIST_OFFER',JSON_OBJECT('message','候补名额已释放，请在 5 分钟内确认'))",
                    candidate.userId(), eventId, candidate.id());
            enqueueOutbox(candidate.requestId(), eventId, "CONFIRMATION_TIMEOUT", new TimeoutCommand(candidate.id()));
            break;
        }
    }

    private void releaseSeat(EnrollmentRow row, String reason) {
        int changed = jdbc.update("UPDATE events SET occupied_count=occupied_count-1 WHERE id=? AND occupied_count>0", row.eventId());
        if (changed != 1) throw new IllegalStateException("Capacity counter underflow for event " + row.eventId());
        jdbc.update("INSERT INTO capacity_ledger(event_id,request_id,enrollment_id,generation,delta,reason) VALUES(?,?,?,?, -1,?)",
                row.eventId(), row.requestId(), row.id(), row.generation(), reason);
    }

    private long nextQueueSequence(Long eventId) {
        jdbc.update("UPDATE events SET waitlist_sequence=waitlist_sequence+1 WHERE id=?", eventId);
        return jdbc.queryForObject("SELECT waitlist_sequence FROM events WHERE id=?", Long.class, eventId);
    }

    private void enqueueOutbox(String aggregateId, Long eventId, String type, Object payload) {
        try {
            jdbc.update("INSERT INTO outbox_messages(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ENROLLMENT',?,?,CAST(? AS JSON),'PENDING')",
                    aggregateId, type, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private void updateRequest(String id, String status, String code, String message) {
        jdbc.update("UPDATE enrollment_requests SET status=?,result_code=?,error_message=? WHERE id=?", status, code, message, id);
    }

    private void releaseReservation(Long eventId, String requestId) {
        redis.execute(RELEASE_RESERVATION_SCRIPT, List.of("campuspass:enroll:reservation:" + eventId + ":" + requestId,
                "campuspass:enroll:reserved-count:" + eventId));
    }

    private RequestRow requestForUpdate(String id) {
        return jdbc.query("SELECT id,user_id,event_id,idempotency_key,generation,status FROM enrollment_requests WHERE id=? FOR UPDATE",
                (rs, row) -> new RequestRow(rs.getString("id"), rs.getLong("user_id"), rs.getLong("event_id"), rs.getString("idempotency_key"), rs.getInt("generation"), rs.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "报名请求不存在"));
    }

    private EnrollmentRow enrollmentForUpdate(Long id) {
        Long eventId = jdbc.query("SELECT event_id FROM enrollments WHERE id=?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("ENROLLMENT_NOT_FOUND", "报名记录不存在"));
        events.getForUpdate(eventId);
        return jdbc.query("SELECT id,event_id,user_id,generation,status,queue_sequence,request_id,confirmation_expires_at,checkin_code_hash FROM enrollments WHERE id=? FOR UPDATE",
                (rs, row) -> mapEnrollment(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ENROLLMENT_NOT_FOUND", "报名记录不存在"));
    }

    private EnrollmentRow enrollment(Long id) {
        return jdbc.query("SELECT id,event_id,user_id,generation,status,queue_sequence,request_id,confirmation_expires_at,checkin_code_hash FROM enrollments WHERE id=?",
                (rs, row) -> mapEnrollment(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ENROLLMENT_NOT_FOUND", "报名记录不存在"));
    }

    private static EnrollmentRow mapEnrollment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EnrollmentRow(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("user_id"), rs.getInt("generation"),
                rs.getString("status"), (Long) rs.getObject("queue_sequence"), rs.getString("request_id"),
                rs.getTimestamp("confirmation_expires_at") == null ? null : rs.getTimestamp("confirmation_expires_at").toLocalDateTime(),
                rs.getString("checkin_code_hash"));
    }

    private EnrollmentView view(Long id) {
        return jdbc.query("SELECT e.id,e.event_id,e.generation,e.status,e.queue_sequence,e.confirmation_expires_at,e.created_at,v.title,v.location,v.starts_at,v.ends_at FROM enrollments e JOIN events v ON v.id=e.event_id WHERE e.id=?",
                (rs, row) -> new EnrollmentView(rs.getLong("id"), rs.getLong("event_id"), rs.getString("title"), rs.getString("location"),
                        rs.getTimestamp("starts_at").toLocalDateTime(), rs.getTimestamp("ends_at").toLocalDateTime(), rs.getInt("generation"),
                        rs.getString("status"), (Long) rs.getObject("queue_sequence"),
                        rs.getTimestamp("confirmation_expires_at") == null ? null : rs.getTimestamp("confirmation_expires_at").toLocalDateTime()), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("ENROLLMENT_NOT_FOUND", "报名记录不存在"));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public record AcceptedRequest(String requestId, String status, String resultCode) { }
    public record RequestResult(String requestId, String status, String resultCode, String message) { }
    public record EventCommand(String requestId) { }
    public record TimeoutCommand(Long enrollmentId) { }
    public record EnrollmentView(Long enrollmentId, Long eventId, String eventTitle, String location,
                                 LocalDateTime startsAt, LocalDateTime endsAt, int generation,
                                 String status, Long queuePosition, LocalDateTime confirmationExpiresAt) { }
    private record RequestRow(String id, Long userId, Long eventId, String idempotencyKey, int generation, String status) { }
    private record EnrollmentRow(Long id, Long eventId, Long userId, int generation, String status,
                                 Long queueSequence, String requestId, LocalDateTime confirmExpires, String checkinCodeHash) { }
}

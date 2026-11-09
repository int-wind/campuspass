package com.campuspass.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Component
public class EventCache implements MessageListener {
    private static final String CHANNEL = "campuspass:event-cache:invalidate";
    private static final String NULL_SENTINEL = "__CAMPUSPASS_NULL__";
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> UNLOCK =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);
    private final Cache<Long, EventDetail> local;
    private final Cache<Long, Boolean> missing;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Duration nullTtl;
    private final boolean localEnabled;

    public EventCache(StringRedisTemplate redis, ObjectMapper mapper,
                      @Value("${campuspass.cache.event-detail-ttl:90s}") Duration ttl,
                      @Value("${campuspass.cache.null-ttl:10s}") Duration nullTtl,
                      @Value("${campuspass.cache.local-max-size:10000}") long maxSize,
                      @Value("${campuspass.cache.local-enabled:true}") boolean localEnabled) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.nullTtl = nullTtl;
        this.localEnabled = localEnabled;
        this.local = Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(30, TimeUnit.SECONDS).build();
        this.missing = Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(nullTtl.toMillis(), TimeUnit.MILLISECONDS).build();
    }

    public EventDetail get(Long id, java.util.function.Supplier<EventDetail> loader) {
        if (localEnabled && Boolean.TRUE.equals(missing.getIfPresent(id))) throw notFound();
        EventDetail cached = localEnabled ? local.getIfPresent(id) : null;
        if (cached != null) return cached;
        String key = "campuspass:event:detail:" + id;
        String value;
        try { value = redis.opsForValue().get(key); }
        catch (RuntimeException ignored) { return required(loader.get()); }
        if (value != null) {
            if (value.equals(NULL_SENTINEL)) {
                if (localEnabled) missing.put(id, true);
                throw notFound();
            }
            try {
                EventDetail detail = mapper.readValue(value, EventDetail.class);
                if (localEnabled) local.put(id, detail);
                return detail;
            } catch (Exception ignored) {
                redis.delete(key);
            }
        }
        String lockKey = "campuspass:event:detail:lock:" + id;
        String lockToken = UUID.randomUUID().toString();
        Boolean leader;
        try { leader = redis.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(3)); }
        catch (RuntimeException ignored) { return required(loader.get()); }
        if (!Boolean.TRUE.equals(leader)) {
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(20); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                String waited;
                try { waited = redis.opsForValue().get(key); }
                catch (RuntimeException ignored) { return required(loader.get()); }
                if (waited != null) {
                    if (waited.equals(NULL_SENTINEL)) throw notFound();
                    try {
                        EventDetail detail = mapper.readValue(waited, EventDetail.class);
                        if (localEnabled) local.put(id, detail);
                        return detail;
                    } catch (Exception ex) { redis.delete(key); break; }
                }
            }
            return required(loader.get());
        }
        try {
        EventDetail detail = loader.get();
        if (detail != null) {
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(detail), ttl.plusSeconds(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 16)));
                if (localEnabled) local.put(id, detail);
            } catch (Exception ignored) {
                // Cache is an optimization; the database query remains authoritative.
            }
        } else {
            if (localEnabled) missing.put(id, true);
            try { redis.opsForValue().set(key, NULL_SENTINEL, nullTtl); } catch (RuntimeException ignored) { }
        }
        return detail;
        } finally {
            try { redis.execute(UNLOCK, java.util.List.of(lockKey), lockToken); }
            catch (RuntimeException ignored) { }
        }
    }

    public void invalidateAfterCommit(Long id) {
        Runnable invalidate = () -> {
            local.invalidate(id);
            missing.invalidate(id);
            try {
                redis.delete("campuspass:event:detail:" + id);
                redis.convertAndSend(CHANNEL, id.toString());
            } catch (RuntimeException ignored) { }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { invalidate.run(); }
                    });
        } else {
            invalidate.run();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Long id = Long.valueOf(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            local.invalidate(id);
            missing.invalidate(id);
        }
        catch (RuntimeException ignored) { }
    }

    private com.campuspass.common.ApiException notFound() {
        return com.campuspass.common.ApiException.notFound("EVENT_NOT_FOUND", "活动不存在");
    }

    private EventDetail required(EventDetail detail) {
        if (detail == null) throw notFound();
        return detail;
    }
}

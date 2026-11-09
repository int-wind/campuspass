package com.campuspass.enrollment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EnrollmentMessageConsumer {
    private final EnrollmentService service;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public EnrollmentMessageConsumer(EnrollmentService service, ObjectMapper mapper,
                                     StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.service = service;
        this.mapper = mapper;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = EnrollmentMessagingConfig.ENROLL_QUEUE, ackMode = "MANUAL")
    public void consume(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("requestId")) service.processEnrollment(node.get("requestId").asText());
            else if (node.has("enrollmentId")) service.timeout(node.get("enrollmentId").asLong());
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            String requestId = null;
            try { requestId = mapper.readTree(body).path("requestId").asText(null); } catch (Exception ignored) { }
            String attemptKey = "campuspass:mq:attempt:" + message.getMessageProperties().getMessageId();
            Long attempts = redis.opsForValue().increment(attemptKey);
            redis.expire(attemptKey, java.time.Duration.ofDays(1));
            if (attempts != null && attempts <= 3) {
                channel.basicNack(tag, false, true);
            } else {
                if (requestId != null) jdbc.update("UPDATE enrollment_requests SET status='FAILED',result_code='PROCESSING_FAILED',error_message='多次处理失败，请联系管理员' WHERE id=? AND status='PENDING'", requestId);
                channel.basicReject(tag, false);
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void expireFallback() { service.recoverExpired(); }
}

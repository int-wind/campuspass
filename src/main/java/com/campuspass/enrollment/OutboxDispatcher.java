package com.campuspass.enrollment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public OutboxDispatcher(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${campuspass.outbox-poll-ms:500}")
    public void dispatch() {
        jdbc.update("UPDATE outbox_messages SET status='PENDING' WHERE status='IN_FLIGHT' AND next_attempt_at<=NOW(3)");
        List<OutboxRow> rows = jdbc.query("SELECT id,aggregate_id,event_type,payload FROM outbox_messages WHERE status='PENDING' AND next_attempt_at<=NOW(3) ORDER BY id LIMIT 50",
                (rs, row) -> new OutboxRow(rs.getLong("id"), rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload")));
        for (OutboxRow row : rows) send(row);
    }

    private void send(OutboxRow row) {
        int claimed = jdbc.update("UPDATE outbox_messages SET status='IN_FLIGHT',attempts=attempts+1,next_attempt_at=DATE_ADD(NOW(3), INTERVAL 10 SECOND) WHERE id=? AND status='PENDING'", row.id());
        if (claimed != 1) return;
        String routingKey = row.type().equals("CONFIRMATION_TIMEOUT") ? "delay" : "enroll";
        CorrelationData correlation = new CorrelationData("outbox-" + row.id());
        Message message = new Message(row.payload().getBytes(StandardCharsets.UTF_8));
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        message.getMessageProperties().setMessageId(Long.toString(row.id()));
        message.getMessageProperties().setHeader("outbox-id", row.id());
        try {
            rabbit.send(EnrollmentMessagingConfig.EXCHANGE, routingKey, message, correlation);
            var future = correlation.getFuture();
            future.addCallback(new ListenableFutureCallback<>() {
                @Override public void onSuccess(CorrelationData.Confirm confirm) {
                    if (confirm != null && confirm.isAck() && correlation.getReturned() == null) {
                        jdbc.update("UPDATE outbox_messages SET status='SENT',published_at=NOW(3) WHERE id=? AND status='IN_FLIGHT'", row.id());
                    } else retry(row.id());
                }
                @Override public void onFailure(Throwable ex) { retry(row.id()); }
            });
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
                if (!future.isDone()) retry(row.id());
            });
        } catch (Exception ex) {
            retry(row.id());
        }
    }

    private void retry(Long id) {
        jdbc.update("UPDATE outbox_messages SET status='PENDING',next_attempt_at=DATE_ADD(NOW(3), INTERVAL LEAST(60, POW(2,attempts)) SECOND) WHERE id=? AND status='IN_FLIGHT'", id);
    }

    private record OutboxRow(Long id, String aggregateId, String type, String payload) { }
}

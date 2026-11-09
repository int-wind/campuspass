package com.campuspass.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventLifecycleJob {
    private final JdbcTemplate jdbc;
    public EventLifecycleJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 30_000)
    public void advanceStates() {
        jdbc.update("UPDATE events SET status='ENDED' WHERE status IN ('PUBLISHED','ENROLLMENT_CLOSED') AND ends_at<=NOW(3)");
        jdbc.update("UPDATE events SET status='ENROLLMENT_CLOSED' WHERE status='PUBLISHED' AND enrollment_closes_at<=NOW(3) AND ends_at>NOW(3)");
    }
}

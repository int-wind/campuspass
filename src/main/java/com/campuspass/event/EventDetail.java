package com.campuspass.event;

import java.time.LocalDateTime;

public record EventDetail(Long id, Long organizerId, String title, String description, String location,
                          LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime enrollmentOpensAt,
                          LocalDateTime enrollmentClosesAt, int capacity, int occupiedCount, String status,
                          long waitlistSequence) { }

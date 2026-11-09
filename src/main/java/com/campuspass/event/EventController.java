package com.campuspass.event;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.alibaba.csp.sentinel.annotation.SentinelResource;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService events;
    public EventController(EventService events) { this.events = events; }

    @GetMapping
    @SentinelResource("events:list")
    public List<EventDetail> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return events.list(page, size);
    }

    @GetMapping("/{id}")
    @SentinelResource("events:detail")
    public EventDetail get(@PathVariable Long id) { return events.get(id); }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventDetail create(Authentication auth, @Valid @RequestBody EventRequest request) {
        return events.create(actor(auth), request.toWrite());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventDetail update(Authentication auth, @PathVariable Long id, @Valid @RequestBody EventRequest request) {
        return events.update(actor(auth), isAdmin(auth), id, request.toWrite());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventDetail publish(Authentication auth, @PathVariable Long id) {
        return events.publish(actor(auth), isAdmin(auth), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public void delete(Authentication auth, @PathVariable Long id) {
        events.delete(actor(auth), isAdmin(auth), id);
    }

    private Long actor(Authentication auth) { return Long.valueOf(auth.getName()); }
    private boolean isAdmin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); }

    public record EventRequest(@NotBlank @Size(max = 160) String title, @Size(max = 10000) String description,
                               @NotBlank @Size(max = 200) String location, @NotNull java.time.LocalDateTime startsAt,
                               @NotNull java.time.LocalDateTime endsAt, @NotNull java.time.LocalDateTime enrollmentOpensAt,
                               @NotNull java.time.LocalDateTime enrollmentClosesAt,
                               @Min(1) @Max(100000) int capacity) {
        EventService.EventWriteRequest toWrite() {
            return new EventService.EventWriteRequest(title, description, location, startsAt, endsAt,
                    enrollmentOpensAt, enrollmentClosesAt, capacity);
        }
    }
}

package com.powerbill.notification.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {
    private final NotificationStore store;
    private final NotificationStream stream;

    NotificationController(NotificationStore store, NotificationStream stream) {
        this.store = store;
        this.stream = stream;
    }

    @GetMapping
    Inbox list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") int limit) {
        UUID user = user(jwt);
        return new Inbox(store.list(user, unreadOnly, Math.min(Math.max(limit, 1), 100)), store.unread(user));
    }

    @PatchMapping("/{id}/read")
    Map<String, Integer> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!store.markRead(user(jwt), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification was not found.");
        }
        return Map.of("unread", store.unread(user(jwt)));
    }

    @PatchMapping("/read-all")
    Map<String, Integer> readAll(@AuthenticationPrincipal Jwt jwt) {
        store.markAllRead(user(jwt));
        return Map.of("unread", 0);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt) {
        UUID user = user(jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(stream.open(user, store.unread(user)));
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record Inbox(List<NotificationStore.Notification> items, int unread) {}
}

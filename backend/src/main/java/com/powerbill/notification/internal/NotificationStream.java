package com.powerbill.notification.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory Server-Sent Events fan-out for one Core API instance. Each user may hold a few open streams (tabs).
 * Persisted notifications remain the source of truth, so a client that reconnects simply reloads the list.
 */
@Component
class NotificationStream {
    private static final int MAX_STREAMS_PER_USER = 5;
    private static final Duration TIMEOUT = Duration.ofMinutes(14);
    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

    SseEmitter open(UUID user, int unread) {
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        var userStreams = streams.computeIfAbsent(user, ignored -> new CopyOnWriteArraySet<>());
        if (userStreams.size() >= MAX_STREAMS_PER_USER) {
            userStreams.stream().findFirst().ifPresent(oldest -> {
                userStreams.remove(oldest);
                oldest.complete();
            });
        }
        userStreams.add(emitter);
        Runnable remove = () -> userStreams.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        send(user, emitter, "ready", Map.of("unread", unread));
        return emitter;
    }

    void push(UUID user, NotificationStore.Notification notification, int unread) {
        var userStreams = streams.get(user);
        if (userStreams == null) {
            return;
        }
        for (SseEmitter emitter : userStreams) {
            send(user, emitter, "notification", Map.of("notification", notification, "unread", unread));
        }
    }

    int openStreams() {
        return streams.values().stream().mapToInt(Set::size).sum();
    }

    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        streams.forEach((user, userStreams) -> userStreams.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException closed) {
                userStreams.remove(emitter);
            }
        }));
    }

    private void send(UUID user, SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException closed) {
            var userStreams = streams.get(user);
            if (userStreams != null) {
                userStreams.remove(emitter);
            }
        }
    }
}

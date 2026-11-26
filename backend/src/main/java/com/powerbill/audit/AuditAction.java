package com.powerbill.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.powerbill.common.RequestContext;

public record AuditAction(UUID eventId, UUID actorId, String actorRole, String action,
        String entityType, UUID entityId, String correlationId, String address,
        Map<String, Object> changes, Instant occurredAt) {

    public static AuditAction record(String action, String entityType, UUID entityId, UUID actorId,
            String actorRole, RequestContext request, Map<String, Object> changes) {
        return new AuditAction(UUID.randomUUID(), actorId, actorRole, action, entityType, entityId,
                request.correlationId(), request.remoteAddress(), Map.copyOf(changes), Instant.now());
    }
}

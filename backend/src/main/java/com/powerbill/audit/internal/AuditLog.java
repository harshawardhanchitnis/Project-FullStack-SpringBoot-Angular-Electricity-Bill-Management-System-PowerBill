package com.powerbill.audit.internal;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
class AuditLog {
    private final JdbcClient db;
    private final ObjectMapper json;

    AuditLog(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @ApplicationModuleListener
    void append(AuditAction event) {
        db.sql("""
                INSERT INTO audit_logs(event_id, actor_user_id, actor_role, action, entity_type,
                    entity_id, correlation_id, ip_address, after_state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), CAST(? AS jsonb), ?)
                ON CONFLICT (event_id) DO NOTHING
                """).params(event.eventId(), event.actorId(), event.actorRole(), event.action(),
                event.entityType(), event.entityId(), event.correlationId(), event.address(),
                json.writeValueAsString(event.changes()), Timestamp.from(event.occurredAt())).update();
    }

    @GetMapping("/api/v1/ops/audit")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String q) {
        if (page < 0 || size < 1 || size > 100 || q.length() > 80) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid page, size or filter.");
        }
        String filter = """
                 WHERE (:q='' OR strpos(lower(action), :q)>0 OR strpos(lower(entity_type), :q)>0
                    OR strpos(lower(coalesce(correlation_id,'')), :q)>0)
                """;
        String query = q.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        List<AuditEntry> rows = db.sql("""
                SELECT id, actor_user_id, actor_role, action, entity_type, entity_id, correlation_id, created_at
                FROM audit_logs""" + filter + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("q", query).param("size", size).param("offset", (long) page * size)
                .query(AuditEntry.class).list();
        long count = db.sql("SELECT count(*) FROM audit_logs" + filter).param("q", query).query(Long.class).single();
        return new Page(rows, page, size, count);
    }

    record AuditEntry(long id, UUID actorUserId, String actorRole, String action, String entityType,
            UUID entityId, String correlationId, OffsetDateTime createdAt) {}
    record Page(List<AuditEntry> content, int page, int size, long totalElements) {}
}

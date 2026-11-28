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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
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
                 WHERE (:q='' OR strpos(lower(l.action), :q)>0 OR strpos(lower(l.entity_type), :q)>0
                    OR strpos(lower(coalesce(l.correlation_id,'')), :q)>0)
                """;
        String query = q.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        // The actor's current name and email are joined in for display; after_state is the recorded change set.
        List<AuditEntry> rows = db.sql("""
                SELECT l.id, l.actor_user_id, l.actor_role, l.action, l.entity_type, l.entity_id, l.correlation_id,
                    l.created_at, l.after_state::text AS after_state, coalesce(l.actor_email, u.email) AS actor_email,
                    u.full_name AS actor_name
                FROM audit_logs l LEFT JOIN users u ON u.id = l.actor_user_id""" + filter
                + " ORDER BY l.created_at DESC, l.id DESC LIMIT :size OFFSET :offset")
                .param("q", query).param("size", size).param("offset", (long) page * size)
                .query((row, index) -> new AuditEntry(row.getLong("id"), row.getObject("actor_user_id", UUID.class),
                        row.getString("actor_name"), row.getString("actor_email"), row.getString("actor_role"),
                        row.getString("action"), row.getString("entity_type"), row.getObject("entity_id", UUID.class),
                        row.getString("correlation_id"), row.getObject("created_at", OffsetDateTime.class),
                        state(row.getString("after_state"))))
                .list();
        long count = db.sql("SELECT count(*) FROM audit_logs l" + filter).param("q", query).query(Long.class).single();
        return new Page(rows, page, size, count);
    }

    private JsonNode state(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (JacksonException error) {
            return null;
        }
    }

    record AuditEntry(long id, UUID actorUserId, String actorName, String actorEmail, String actorRole, String action,
            String entityType, UUID entityId, String correlationId, OffsetDateTime createdAt, JsonNode afterState) {}
    record Page(List<AuditEntry> content, int page, int size, long totalElements) {}
}

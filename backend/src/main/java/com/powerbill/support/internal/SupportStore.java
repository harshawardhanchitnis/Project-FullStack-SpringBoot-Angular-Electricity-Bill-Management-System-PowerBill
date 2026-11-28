package com.powerbill.support.internal;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.support.SupportDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SupportStore {
    private static final String COMPLAINT = """
            SELECT id,ticket_number,consumer_account_id,meter_id,bill_id,complaint_type,priority,status,subject,
                description,contact_number,assigned_agent_id,sla_due_at,sla_breached,resolution_summary,resolved_at,
                csat_rating,csat_feedback,created_at,updated_at,version
            FROM complaints c
            """;
    private static final String OUTAGE = """
            SELECT id,incident_code,region_zone,city,affected_pincodes,title,description,severity,status,starts_at,
                estimated_restoration_at,resolved_at,created_at,updated_at,version
            FROM outage_incidents
            """;
    private final JdbcClient db;

    SupportStore(JdbcClient db) {
        this.db = db;
    }

    void insert(Complaint complaint) {
        db.sql("""
                INSERT INTO complaints(id,ticket_number,consumer_account_id,meter_id,bill_id,complaint_type,priority,status,
                    subject,description,contact_number,sla_due_at,created_at)
                VALUES (:id,:ticketNumber,:consumerAccountId,:meterId,:billId,:complaintType,:priority,:status,:subject,
                    :description,:contactNumber,:slaDueAt,:createdAt)
                """).paramSource(complaint).update();
    }

    Optional<Complaint> complaint(UUID id, boolean lock) {
        return db.sql(COMPLAINT + " WHERE id=?" + (lock ? " FOR UPDATE" : "")).param(id).query(Complaint.class).optional();
    }

    Page search(UUID accountId, String status, String priority, Boolean breached, UUID agent, String query,
            int page, int size) {
        String filter = """
                 WHERE (CAST(:account AS uuid) IS NULL OR c.consumer_account_id=:account)
                   AND (CAST(:status AS text) IS NULL OR c.status=:status)
                   AND (CAST(:priority AS text) IS NULL OR c.priority=:priority)
                   AND (CAST(:breached AS boolean) IS NULL OR c.sla_breached=:breached)
                   AND (CAST(:agent AS uuid) IS NULL OR c.assigned_agent_id=:agent)
                   AND (:query='' OR strpos(lower(c.ticket_number),:query)>0 OR strpos(lower(c.subject),:query)>0)
                """;
        var rows = db.sql(COMPLAINT + filter + """
                 ORDER BY CASE WHEN c.status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END,
                    CASE c.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                    c.sla_due_at, c.id LIMIT :size OFFSET :offset
                """).param("account", accountId, Types.OTHER).param("status", status, Types.VARCHAR)
                .param("priority", priority, Types.VARCHAR).param("breached", breached, Types.BOOLEAN)
                .param("agent", agent, Types.OTHER).param("query", query).param("size", size)
                .param("offset", (long) page * size).query(Complaint.class).list();
        long total = db.sql("SELECT count(*) FROM complaints c" + filter).param("account", accountId, Types.OTHER)
                .param("status", status, Types.VARCHAR).param("priority", priority, Types.VARCHAR)
                .param("breached", breached, Types.BOOLEAN).param("agent", agent, Types.OTHER).param("query", query)
                .query(Long.class).single();
        return new Page(rows, page, size, total);
    }

    boolean update(UUID id, long version, String status, UUID agent, String priority, Instant slaDue,
            String resolution) {
        return db.sql("""
                UPDATE complaints SET status=:status, assigned_agent_id=:agent, priority=:priority, sla_due_at=:due,
                    resolution_summary=:resolution,
                    resolved_at=CASE WHEN :status IN ('RESOLVED','CLOSED') THEN coalesce(resolved_at, now()) ELSE NULL END,
                    sla_breached=sla_breached OR (:status='RESOLVED' AND now()>:due),
                    version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("status", status).param("agent", agent, Types.OTHER).param("priority", priority)
                .param("due", Timestamp.from(slaDue)).param("resolution", resolution, Types.VARCHAR)
                .param("id", id).param("version", version).update() == 1;
    }

    void csat(UUID id, int rating, String feedback) {
        db.sql("""
                UPDATE complaints SET csat_rating=?, csat_feedback=?, status='CLOSED', version=version+1, updated_at=now()
                WHERE id=?
                """).params(rating, feedback, id).update();
    }

    void reopen(UUID id) {
        db.sql("""
                UPDATE complaints SET status='IN_PROGRESS', resolved_at=NULL, version=version+1, updated_at=now()
                WHERE id=? AND status='RESOLVED'
                """).param(id).update();
    }

    void history(UUID complaint, String from, String to, UUID actor) {
        db.sql("INSERT INTO complaint_status_history(complaint_id,from_status,to_status,changed_by) VALUES (?,?,?,?)")
                .params(complaint, from, to, actor).update();
    }

    List<StatusChange> history(UUID complaint) {
        return db.sql("""
                SELECT from_status,to_status,changed_at FROM complaint_status_history WHERE complaint_id=?
                ORDER BY changed_at,id
                """).param(complaint).query(StatusChange.class).list();
    }

    void comment(UUID complaint, UUID author, String role, boolean internal, String body) {
        db.sql("""
                INSERT INTO complaint_comments(id,complaint_id,author_user_id,author_role,is_internal,body)
                VALUES (?,?,?,?,?,?)
                """).params(UUID.randomUUID(), complaint, author, role, internal, body).update();
    }

    List<Comment> comments(UUID complaint, boolean includeInternal) {
        return db.sql("""
                SELECT c.id,c.author_role,u.full_name AS author_name,c.is_internal,c.body,c.created_at
                FROM complaint_comments c JOIN users u ON u.id=c.author_user_id
                WHERE c.complaint_id=? AND (? OR NOT c.is_internal) ORDER BY c.created_at,c.id
                """).params(complaint, includeInternal).query(Comment.class).list();
    }

    List<Complaint> markBreaches() {
        return db.sql("""
                UPDATE complaints SET sla_breached=true, updated_at=now()
                WHERE NOT sla_breached AND status NOT IN ('RESOLVED','CLOSED') AND sla_due_at<now()
                RETURNING id,ticket_number,consumer_account_id,meter_id,bill_id,complaint_type,priority,status,subject,
                    description,contact_number,assigned_agent_id,sla_due_at,sla_breached,resolution_summary,resolved_at,
                    csat_rating,csat_feedback,created_at,updated_at,version
                """).query(Complaint.class).list();
    }

    int openFor(UUID accountId) {
        return db.sql("SELECT count(*) FROM complaints WHERE consumer_account_id=? AND status NOT IN ('RESOLVED','CLOSED')")
                .param(accountId).query(Integer.class).single();
    }

    SupportDirectory.SlaStatistics statistics() {
        var summary = db.sql("""
                SELECT count(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED')) AS open_tickets,
                    count(*) FILTER (WHERE status NOT IN ('RESOLVED','CLOSED') AND sla_breached) AS breached_open,
                    count(*) FILTER (WHERE resolved_at > now()-interval '90 days') AS resolved,
                    -- Open tickets that have already breached count as misses, so a backlog of overdue work can never
                    -- show as 100% compliance.
                    coalesce(round(100.0 * count(*) FILTER (WHERE resolved_at > now()-interval '90 days' AND NOT sla_breached)
                        / nullif(count(*) FILTER (WHERE resolved_at > now()-interval '90 days'
                            OR (status NOT IN ('RESOLVED','CLOSED') AND sla_breached)),0), 1), 100) AS compliance,
                    coalesce(round(avg(extract(epoch FROM resolved_at-created_at)/3600)
                        FILTER (WHERE resolved_at > now()-interval '90 days'), 1), 0) AS resolution_hours,
                    coalesce(round(avg(csat_rating), 2), 0) AS csat
                FROM complaints
                """).query((row, index) -> new Object[] {row.getInt("open_tickets"), row.getInt("breached_open"),
                row.getInt("resolved"), row.getBigDecimal("compliance"), row.getBigDecimal("resolution_hours"),
                row.getBigDecimal("csat")}).single();
        var byPriority = db.sql("""
                SELECT priority, count(*) AS count FROM complaints WHERE status NOT IN ('RESOLVED','CLOSED')
                GROUP BY priority ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END
                """).query(SupportDirectory.PriorityCount.class).list();
        return new SupportDirectory.SlaStatistics((int) summary[0], (int) summary[1], (int) summary[2],
                (BigDecimal) summary[3], (BigDecimal) summary[4], (BigDecimal) summary[5], byPriority);
    }

    void insertOutage(UUID id, String code, String zone, String city, List<String> pincodes, String title,
            String description, String severity, Instant restoration, UUID actor) {
        db.sql("""
                INSERT INTO outage_incidents(id,incident_code,region_zone,city,affected_pincodes,title,description,severity,
                    status,estimated_restoration_at,created_by)
                VALUES (?,?,?,?,CAST(? AS text[]),?,?,?,?,?,?)
                """).params(id, code, zone, city, "{" + String.join(",", pincodes) + "}", title, description, severity,
                severity.equals("PLANNED") ? "SCHEDULED" : "INVESTIGATING",
                restoration == null ? null : Timestamp.from(restoration), actor).update();
    }

    boolean updateOutage(UUID id, long version, String status, Instant restoration, String description) {
        return db.sql("""
                UPDATE outage_incidents SET status=?, estimated_restoration_at=?, description=coalesce(?,description),
                    resolved_at=CASE WHEN ?='RESOLVED' THEN now() ELSE NULL END, version=version+1, updated_at=now()
                WHERE id=? AND version=?
                """).params(status, restoration == null ? null : Timestamp.from(restoration), description, status, id,
                version).update() == 1;
    }

    Optional<Outage> outage(UUID id) {
        return db.sql(OUTAGE + " WHERE id=?").param(id).query(SupportStore::outage).optional();
    }

    List<Outage> outages(boolean includeResolved, Collection<String> pincodes) {
        String filter = (includeResolved ? " WHERE true" : " WHERE status<>'RESOLVED'")
                + (pincodes == null ? "" : " AND affected_pincodes && CAST(:pins AS text[])");
        var query = db.sql(OUTAGE + filter + " ORDER BY status='RESOLVED', starts_at DESC LIMIT 50");
        if (pincodes != null) {
            query = query.param("pins", "{" + String.join(",", pincodes) + "}");
        }
        return query.query(SupportStore::outage).list();
    }

    private static Outage outage(java.sql.ResultSet row, int index) throws SQLException {
        Array pins = row.getArray("affected_pincodes");
        return new Outage(row.getObject("id", UUID.class), row.getString("incident_code"), row.getString("region_zone"),
                row.getString("city"), Arrays.asList((String[]) pins.getArray()), row.getString("title"),
                row.getString("description"), row.getString("severity"), row.getString("status"),
                row.getObject("starts_at", OffsetDateTime.class),
                row.getObject("estimated_restoration_at", OffsetDateTime.class),
                row.getObject("resolved_at", OffsetDateTime.class), row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class), row.getLong("version"));
    }

    record Complaint(UUID id, String ticketNumber, UUID consumerAccountId, UUID meterId, UUID billId,
            String complaintType, String priority, String status, String subject, String description,
            String contactNumber, UUID assignedAgentId, OffsetDateTime slaDueAt, boolean slaBreached,
            String resolutionSummary, OffsetDateTime resolvedAt, Integer csatRating, String csatFeedback,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {}
    record Comment(UUID id, String authorRole, String authorName, boolean isInternal, String body,
            OffsetDateTime createdAt) {}
    record StatusChange(String fromStatus, String toStatus, OffsetDateTime changedAt) {}
    record Outage(UUID id, String incidentCode, String regionZone, String city, List<String> affectedPincodes,
            String title, String description, String severity, String status, OffsetDateTime startsAt,
            OffsetDateTime estimatedRestorationAt, OffsetDateTime resolvedAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, long version) {}
    record Page(List<Complaint> content, int page, int size, long totalElements) {}
}

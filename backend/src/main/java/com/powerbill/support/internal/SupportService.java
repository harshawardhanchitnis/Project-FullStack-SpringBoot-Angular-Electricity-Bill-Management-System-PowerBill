package com.powerbill.support.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.billing.BillingLedger;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.identity.StaffDirectory;
import com.powerbill.support.SupportDirectory;
import com.powerbill.support.SupportEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class SupportService implements SupportDirectory {
    static final Map<String, Duration> SLA = Map.of("CRITICAL", Duration.ofHours(4), "HIGH", Duration.ofHours(24),
            "MEDIUM", Duration.ofHours(48), "LOW", Duration.ofHours(72));
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED"),
            "ASSIGNED", Set.of("OPEN", "IN_PROGRESS", "RESOLVED"),
            "IN_PROGRESS", Set.of("ASSIGNED", "RESOLVED"),
            "RESOLVED", Set.of("IN_PROGRESS", "CLOSED"),
            "CLOSED", Set.of());
    private static final Set<String> AGENT_ROLES = Set.of("SUPPORT_AGENT", "FIELD_ENGINEER", "SUPER_ADMIN");
    private final SupportStore store;
    private final AccountDirectory accounts;
    private final BillingLedger billing;
    private final StaffDirectory staff;
    private final ApplicationEventPublisher events;

    SupportService(SupportStore store, AccountDirectory accounts, BillingLedger billing, StaffDirectory staff,
            ApplicationEventPublisher events) {
        this.store = store;
        this.accounts = accounts;
        this.billing = billing;
        this.staff = staff;
        this.events = events;
    }

    static String defaultPriority(String type) {
        return switch (type) {
            case "POWER_OUTAGE" -> "CRITICAL";
            case "VOLTAGE_FLUCTUATION", "METER_FAULT" -> "HIGH";
            case "BILLING", "SOLAR_NET_METERING", "CONNECTION" -> "MEDIUM";
            default -> "LOW";
        };
    }

    @Transactional
    SupportStore.Complaint create(AccountDirectory.Account account, UUID actor, SupportController.NewComplaint input,
            Instant now, RequestContext request) {
        if (input.meterId() != null && accounts.servicePoints(account.id()).stream()
                .noneMatch(point -> point.meterId().equals(input.meterId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected meter was not found.");
        }
        if (input.billId() != null && billing.header(input.billId())
                .filter(bill -> bill.accountId().equals(account.id())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected bill was not found.");
        }
        String priority = defaultPriority(input.complaintType());
        UUID id = UUID.randomUUID();
        String ticket = "TKT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        OffsetDateTime created = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        store.insert(new SupportStore.Complaint(id, ticket, account.id(), input.meterId(), input.billId(),
                input.complaintType(), priority, "OPEN", input.subject().strip(), input.description().strip(),
                input.contactNumber() == null ? account.mobileNumber() : input.contactNumber(), null,
                created.plus(SLA.get(priority)), false, null, null, null, null, created, created, 0));
        store.history(id, null, "OPEN", actor);
        events.publishEvent(AuditAction.record("COMPLAINT_CREATED", "COMPLAINT", id, actor, "CUSTOMER", request,
                Map.of("ticket", ticket, "type", input.complaintType())));
        events.publishEvent(new SupportEvents.ComplaintUpdated(id, ticket, account.id(), account.userId(), null,
                "OPEN", "Complaint " + ticket + " was registered.", true, false));
        return store.complaint(id, false).orElseThrow();
    }

    SupportStore.Complaint owned(UUID accountId, UUID id) {
        return store.complaint(id, false).filter(complaint -> complaint.consumerAccountId().equals(accountId))
                .orElseThrow(SupportService::missing);
    }

    SupportStore.Complaint find(UUID id) {
        return store.complaint(id, false).orElseThrow(SupportService::missing);
    }

    @Transactional
    void customerComment(AccountDirectory.Account account, UUID actor, UUID id, String body, RequestContext request) {
        var complaint = store.complaint(id, true).filter(item -> item.consumerAccountId().equals(account.id()))
                .orElseThrow(SupportService::missing);
        if (complaint.status().equals("CLOSED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed complaints cannot receive new messages.");
        }
        store.comment(id, actor, "CUSTOMER", false, body.strip());
        if (complaint.status().equals("RESOLVED")) {
            store.reopen(id);
            store.history(id, "RESOLVED", "IN_PROGRESS", actor);
        }
        events.publishEvent(AuditAction.record("COMPLAINT_COMMENTED", "COMPLAINT", id, actor, "CUSTOMER", request,
                Map.of()));
        events.publishEvent(new SupportEvents.ComplaintUpdated(id, complaint.ticketNumber(), account.id(),
                account.userId(), complaint.assignedAgentId(), complaint.status().equals("RESOLVED") ? "IN_PROGRESS"
                        : complaint.status(), "The customer replied on " + complaint.ticketNumber() + ".", false, true));
    }

    @Transactional
    void rate(AccountDirectory.Account account, UUID actor, UUID id, int rating, String feedback,
            RequestContext request) {
        var complaint = store.complaint(id, true).filter(item -> item.consumerAccountId().equals(account.id()))
                .orElseThrow(SupportService::missing);
        if (!complaint.status().equals("RESOLVED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rate the complaint after it is resolved.");
        }
        store.csat(id, rating, feedback == null ? null : feedback.strip());
        store.history(id, "RESOLVED", "CLOSED", actor);
        events.publishEvent(AuditAction.record("COMPLAINT_RATED", "COMPLAINT", id, actor, "CUSTOMER", request,
                Map.of("rating", rating)));
    }

    @Transactional
    SupportStore.Complaint staffUpdate(UUID id, SupportController.StaffUpdate input, UUID actor, String role,
            RequestContext request) {
        var complaint = store.complaint(id, true).orElseThrow(SupportService::missing);
        if (complaint.version() != input.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The complaint changed. Reload it and try again.");
        }
        UUID agent = input.unassign() ? null : input.assignedAgentId() != null ? input.assignedAgentId()
                : complaint.assignedAgentId();
        if (agent != null && !agent.equals(complaint.assignedAgentId()) && staff.staffMember(agent)
                .filter(member -> member.status().equals("ACTIVE") && AGENT_ROLES.contains(member.role())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assign an active support agent or field engineer.");
        }
        String status = input.status() != null ? input.status()
                : agent != null && complaint.status().equals("OPEN") ? "ASSIGNED"
                : agent == null && complaint.status().equals("ASSIGNED") ? "OPEN" : complaint.status();
        if (!status.equals(complaint.status()) && !TRANSITIONS.get(complaint.status()).contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A complaint cannot move from " + complaint.status() + " to " + status + ".");
        }
        String resolution = input.resolutionSummary() != null ? input.resolutionSummary().strip()
                : complaint.resolutionSummary();
        if (status.equals("RESOLVED") && (resolution == null || resolution.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Describe the resolution before resolving.");
        }
        String priority = input.priority() == null ? complaint.priority() : input.priority();
        Instant due = complaint.createdAt().toInstant().plus(SLA.get(priority));
        if (!store.update(id, complaint.version(), status, agent, priority, due, resolution)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The complaint changed. Reload it and try again.");
        }
        if (!status.equals(complaint.status())) {
            store.history(id, complaint.status(), status, actor);
        }
        events.publishEvent(AuditAction.record("COMPLAINT_UPDATED", "COMPLAINT", id, actor, role, request,
                Map.of("from", complaint.status(), "to", status, "priority", priority)));
        var account = accounts.findAccount(complaint.consumerAccountId()).orElseThrow();
        String summary = status.equals("RESOLVED") ? "Complaint " + complaint.ticketNumber() + " was resolved."
                : !status.equals(complaint.status()) ? "Complaint " + complaint.ticketNumber() + " is now "
                        + status.replace('_', ' ').toLowerCase(Locale.ROOT) + "."
                : "Complaint " + complaint.ticketNumber() + " was updated.";
        boolean reassigned = agent != null && !agent.equals(complaint.assignedAgentId());
        events.publishEvent(new SupportEvents.ComplaintUpdated(id, complaint.ticketNumber(), account.id(),
                account.userId(), agent, status, summary, !status.equals(complaint.status()), reassigned));
        return store.complaint(id, false).orElseThrow();
    }

    @Transactional
    void staffComment(UUID id, UUID actor, String role, boolean internal, String body, RequestContext request) {
        var complaint = store.complaint(id, true).orElseThrow(SupportService::missing);
        store.comment(id, actor, role, internal, body.strip());
        events.publishEvent(AuditAction.record("COMPLAINT_COMMENTED", "COMPLAINT", id, actor, role, request,
                Map.of("internal", internal)));
        if (!internal) {
            var account = accounts.findAccount(complaint.consumerAccountId()).orElseThrow();
            events.publishEvent(new SupportEvents.ComplaintUpdated(id, complaint.ticketNumber(), account.id(),
                    account.userId(), complaint.assignedAgentId(), complaint.status(),
                    "Support replied on " + complaint.ticketNumber() + ".", true, false));
        }
    }

    @Scheduled(fixedDelayString = "${gridiq.support.sla-sweep:PT5M}", initialDelayString = "PT30S")
    @Transactional
    public void flagBreaches() {
        for (var complaint : store.markBreaches()) {
            events.publishEvent(new SupportEvents.SlaBreached(complaint.id(), complaint.ticketNumber(),
                    complaint.priority(), complaint.assignedAgentId()));
        }
    }

    // ----- Outages -----

    @Transactional
    SupportStore.Outage declare(SupportController.NewOutage input, UUID actor, String role, RequestContext request) {
        UUID id = UUID.randomUUID();
        String code = "OUT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        var pins = input.affectedPincodes().stream().distinct().sorted().toList();
        store.insertOutage(id, code, input.regionZone().strip(), input.city().strip(), pins, input.title().strip(),
                input.description().strip(), input.severity(), input.estimatedRestorationAt(), actor);
        var outage = store.outage(id).orElseThrow();
        events.publishEvent(AuditAction.record("OUTAGE_DECLARED", "OUTAGE", id, actor, role, request,
                Map.of("code", code, "severity", input.severity())));
        publish(outage, true);
        return outage;
    }

    @Transactional
    SupportStore.Outage changeOutage(UUID id, SupportController.OutageUpdate input, UUID actor, String role,
            RequestContext request) {
        var current = store.outage(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Outage was not found."));
        if (current.status().equals("RESOLVED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolved outages cannot change.");
        }
        Instant restoration = input.estimatedRestorationAt() != null ? input.estimatedRestorationAt()
                : current.estimatedRestorationAt() == null ? null : current.estimatedRestorationAt().toInstant();
        if (!store.updateOutage(id, input.version(), input.status(), restoration,
                input.description() == null ? null : input.description().strip())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The outage changed. Reload it and try again.");
        }
        var outage = store.outage(id).orElseThrow();
        events.publishEvent(AuditAction.record("OUTAGE_UPDATED", "OUTAGE", id, actor, role, request,
                Map.of("status", input.status())));
        publish(outage, false);
        return outage;
    }

    private void publish(SupportStore.Outage outage, boolean declared) {
        events.publishEvent(new SupportEvents.OutageChanged(outage.id(), outage.incidentCode(), outage.title(),
                outage.severity(), outage.status(), outage.affectedPincodes(),
                outage.estimatedRestorationAt() == null ? null : outage.estimatedRestorationAt().toInstant(), declared));
    }

    // ----- Shared directory -----

    @Override
    public Optional<ComplaintContext> complaint(UUID complaintId) {
        return store.complaint(complaintId, false).map(complaint -> new ComplaintContext(complaint.id(),
                complaint.ticketNumber(), complaint.consumerAccountId(), complaint.meterId(), complaint.billId(),
                complaint.complaintType(), complaint.priority(), complaint.status(), complaint.subject(),
                complaint.description(), complaint.createdAt(), complaint.slaDueAt(), complaint.slaBreached(),
                store.comments(complaint.id(), false).stream().map(SupportStore.Comment::body).toList()));
    }

    @Override
    public int openComplaints(UUID accountId) {
        return store.openFor(accountId);
    }

    @Override
    public List<Outage> activeOutages(Collection<String> pincodes) {
        return store.outages(false, pincodes).stream().map(outage -> new Outage(outage.id(), outage.incidentCode(),
                outage.title(), outage.description(), outage.severity(), outage.status(), outage.regionZone(),
                outage.city(), outage.affectedPincodes(), outage.startsAt(), outage.estimatedRestorationAt())).toList();
    }

    @Override
    public SlaStatistics slaStatistics() {
        return store.statistics();
    }

    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint was not found.");
    }
}

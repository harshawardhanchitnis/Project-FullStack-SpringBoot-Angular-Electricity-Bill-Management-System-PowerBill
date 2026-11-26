package com.powerbill.support;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Support data shared with analytics and the AI triage assistant. */
public interface SupportDirectory {

    Optional<ComplaintContext> complaint(UUID complaintId);

    int openComplaints(UUID accountId);

    List<Outage> activeOutages(Collection<String> pincodes);

    SlaStatistics slaStatistics();

    record ComplaintContext(UUID id, String ticketNumber, UUID accountId, UUID meterId, UUID billId,
            String complaintType, String priority, String status, String subject, String description,
            OffsetDateTime createdAt, OffsetDateTime slaDueAt, boolean slaBreached, List<String> publicComments) {}

    record Outage(UUID id, String incidentCode, String title, String description, String severity, String status,
            String regionZone, String city, List<String> affectedPincodes, OffsetDateTime startsAt,
            OffsetDateTime estimatedRestorationAt) {}

    record SlaStatistics(int openTickets, int breachedOpen, int resolvedLast90Days, BigDecimal compliancePct,
            BigDecimal averageResolutionHours, BigDecimal averageCsat, List<PriorityCount> openByPriority) {}

    record PriorityCount(String priority, int count) {}
}

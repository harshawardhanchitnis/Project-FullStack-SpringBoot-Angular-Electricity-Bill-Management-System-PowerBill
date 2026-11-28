package com.powerbill.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SupportEvents {
    private SupportEvents() {}

    /** A complaint changed. {@code notifyCustomer} is false when the customer made the change. */
    public record ComplaintUpdated(UUID complaintId, String ticketNumber, UUID accountId, UUID customerUserId,
            UUID assignedAgentId, String status, String summary, boolean notifyCustomer, boolean notifyAgent) {}

    public record SlaBreached(UUID complaintId, String ticketNumber, String priority, UUID assignedAgentId) {}

    public record OutageChanged(UUID incidentId, String incidentCode, String title, String severity, String status,
            List<String> affectedPincodes, Instant estimatedRestorationAt, boolean declared) {}
}

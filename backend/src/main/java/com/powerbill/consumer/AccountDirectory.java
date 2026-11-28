package com.powerbill.consumer;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only account and service-point lookups shared with billing, payments, support and notifications. */
public interface AccountDirectory {

    Optional<Account> accountForUser(UUID userId);

    Optional<Account> findAccount(UUID accountId);

    ServicePoint servicePoint(UUID meterId);

    List<ServicePoint> billableServicePoints();

    List<ServicePoint> servicePoints(UUID accountId);

    /** Whether the account holds an auto-pay mandate for newly issued bills. */
    boolean autoPayEnabled(UUID accountId);

    /** Active accounts with an auto-pay mandate. */
    List<UUID> autoPayAccounts();

    /** Meter serials by meter ID, fetched in one query. Unknown IDs are absent from the map. */
    java.util.Map<UUID, String> meterSerials(Collection<UUID> meterIds);

    List<UUID> usersInPincodes(Collection<String> pincodes);

    List<String> pincodes(UUID accountId);

    record Account(UUID id, UUID userId, String consumerNumber, String fullName, String accountType,
            String billingEmail, String mobileNumber, String status, BigDecimal monthlyBudgetInr) {}

    /** A billable meter with its premise and account. {@code tariffPlanId} is an explicit plan override, or null. */
    record ServicePoint(UUID meterId, String meterSerial, String connectionType, BigDecimal sanctionedLoadKw,
            boolean solarNetMetering, BigDecimal solarCapacityKw, String meterStatus, UUID premiseId,
            String premiseLabel, String addressLine, String city, String state, String pincode,
            String regionZone, UUID tariffPlanId, Account account) {}
}

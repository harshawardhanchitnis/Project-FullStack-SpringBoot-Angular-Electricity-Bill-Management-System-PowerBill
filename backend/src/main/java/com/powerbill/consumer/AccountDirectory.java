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

    List<UUID> usersInPincodes(Collection<String> pincodes);

    List<String> pincodes(UUID accountId);

    record Account(UUID id, UUID userId, String consumerNumber, String fullName, String accountType,
            String billingEmail, String mobileNumber, String status, BigDecimal monthlyBudgetInr) {}

    record ServicePoint(UUID meterId, String meterSerial, String connectionType, BigDecimal sanctionedLoadKw,
            boolean solarNetMetering, BigDecimal solarCapacityKw, String meterStatus, UUID premiseId,
            String premiseLabel, String addressLine, String city, String state, String pincode,
            String regionZone, Account account) {}
}

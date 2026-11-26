package com.powerbill.identity;

import java.util.Optional;
import java.util.UUID;

/** Contact details and notification preferences for any signed-up user. */
public interface UserDirectory {

    Optional<Contact> contact(UUID userId);

    record Contact(UUID id, String email, String fullName, String role, boolean budgetAlerts) {}
}

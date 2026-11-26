package com.powerbill.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of utility staff for assignment, notification fan-out and display names. */
public interface StaffDirectory {

    Optional<StaffMember> staffMember(UUID id);

    Optional<StaffMember> staffByEmail(String email);

    List<StaffMember> activeStaff(Collection<String> roles);

    record StaffMember(UUID id, String email, String fullName, String role, String status) {}
}

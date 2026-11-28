package com.powerbill.consumer.internal;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

final class ConsumerRequests {
    static final String PHONE = "[6-9][0-9]{9}";
    static final String TYPE = "RESIDENTIAL|COMMERCIAL|INDUSTRIAL";
    static final String STATUS = "ACTIVE|SUSPENDED|DISCONNECTED";

    record Registration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = PHONE) String mobileNumber,
            @NotNull @Valid Premise premise) {}

    record OpsRegistration(@NotNull @Valid Registration customer,
            @NotBlank @Pattern(regexp = TYPE) String accountType) {}

    record Premise(@NotBlank @Size(max = 100) String label, @NotBlank @Size(max = 255) String addressLine,
            @NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = "[1-9][0-9]{5}") String pincode,
            @NotBlank @Size(max = 100) String regionZone,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude) {}

    record Update(@NotBlank @Email @Size(max = 254) String billingEmail,
            @NotBlank @Pattern(regexp = PHONE) String mobileNumber,
            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal monthlyBudgetInr,
            @NotNull @PositiveOrZero Long version) {}

    record StatusChange(@NotBlank @Pattern(regexp = STATUS) String status,
            @NotNull @PositiveOrZero Long version) {}

    record AutoPay(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) {}

    record NewMeter(@NotNull UUID premiseId,
            @NotBlank @Pattern(regexp = "DOMESTIC|COMMERCIAL|INDUSTRIAL") String connectionType,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 4)
                    BigDecimal sanctionedLoadKw,
            boolean solarNetMetering,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 4)
                    BigDecimal solarCapacityKw) {}
}

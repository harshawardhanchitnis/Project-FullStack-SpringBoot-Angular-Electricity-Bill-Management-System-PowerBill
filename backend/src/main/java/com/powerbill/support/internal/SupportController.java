package com.powerbill.support.internal;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class SupportController {
    static final String TYPES = "BILLING|POWER_OUTAGE|METER_FAULT|VOLTAGE_FLUCTUATION|SOLAR_NET_METERING|CONNECTION|TECHNICAL|OTHER";
    static final String STATUSES = "OPEN|ASSIGNED|IN_PROGRESS|RESOLVED|CLOSED";
    static final String PRIORITIES = "CRITICAL|HIGH|MEDIUM|LOW";
    private static final Set<String> STAFF = Set.of("BILLING_OFFICER", "SUPPORT_AGENT", "FIELD_ENGINEER", "SUPER_ADMIN");
    private final SupportService support;
    private final SupportStore store;
    private final AccountDirectory accounts;

    SupportController(SupportService support, SupportStore store, AccountDirectory accounts) {
        this.support = support;
        this.store = store;
        this.accounts = accounts;
    }

    // ----- Customer -----

    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    Detail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewComplaint input, HttpServletRequest request) {
        var account = self(jwt);
        var complaint = support.create(account, actor(jwt), input, Instant.now(), RequestContext.from(request));
        return detail(complaint, false);
    }

    @GetMapping("/complaints")
    SupportStore.Page mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        check(status, STATUSES, q, page, size);
        return store.search(self(jwt).id(), status, null, null, null, q.strip().toLowerCase(Locale.ROOT), page, size);
    }

    @GetMapping("/complaints/{id}")
    Detail complaint(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return detail(support.owned(self(jwt).id(), id), false);
    }

    @PostMapping("/complaints/{id}/comments")
    Detail comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody NewComment input,
            HttpServletRequest request) {
        var account = self(jwt);
        support.customerComment(account, actor(jwt), id, input.body(), RequestContext.from(request));
        return detail(support.owned(account.id(), id), false);
    }

    @PostMapping("/complaints/{id}/csat")
    Detail rate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Rating input,
            HttpServletRequest request) {
        var account = self(jwt);
        support.rate(account, actor(jwt), id, input.rating(), input.feedback(), RequestContext.from(request));
        return detail(support.owned(account.id(), id), false);
    }

    @GetMapping("/outages/active")
    List<SupportStore.Outage> activeOutages(@AuthenticationPrincipal Jwt jwt) {
        if (STAFF.contains(jwt.getClaimAsString("role"))) {
            return store.outages(false, null);
        }
        var pins = accounts.accountForUser(actor(jwt)).map(account -> accounts.pincodes(account.id())).orElse(List.of());
        return pins.isEmpty() ? List.of() : store.outages(false, pins);
    }

    // ----- Operations -----

    @GetMapping("/ops/complaints")
    SupportStore.Page queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) String priority, @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(required = false) UUID assignedTo, @RequestParam(required = false) UUID accountId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        check(status, STATUSES, q, page, size);
        if (priority != null && !priority.matches(PRIORITIES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown priority.");
        }
        return store.search(accountId, status, priority, slaBreached, assignedTo, q.strip().toLowerCase(Locale.ROOT),
                page, size);
    }

    @GetMapping("/ops/complaints/{id}")
    Detail opsComplaint(@PathVariable UUID id) {
        return detail(support.find(id), true);
    }

    @PatchMapping("/ops/complaints/{id}")
    Detail update(@PathVariable UUID id, @Valid @RequestBody StaffUpdate input, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return detail(support.staffUpdate(id, input, actor(jwt), jwt.getClaimAsString("role"),
                RequestContext.from(request)), true);
    }

    @PostMapping("/ops/complaints/{id}/comments")
    Detail staffComment(@PathVariable UUID id, @Valid @RequestBody StaffComment input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        support.staffComment(id, actor(jwt), jwt.getClaimAsString("role"), input.internal(), input.body(),
                RequestContext.from(request));
        return detail(support.find(id), true);
    }

    @GetMapping("/ops/outages")
    List<SupportStore.Outage> outages(@RequestParam(defaultValue = "false") boolean includeResolved) {
        return store.outages(includeResolved, null);
    }

    @PostMapping("/ops/outages")
    @ResponseStatus(HttpStatus.CREATED)
    SupportStore.Outage declare(@Valid @RequestBody NewOutage input, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return support.declare(input, actor(jwt), jwt.getClaimAsString("role"), RequestContext.from(request));
    }

    @PatchMapping("/ops/outages/{id}")
    SupportStore.Outage changeOutage(@PathVariable UUID id, @Valid @RequestBody OutageUpdate input,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return support.changeOutage(id, input, actor(jwt), jwt.getClaimAsString("role"), RequestContext.from(request));
    }

    private Detail detail(SupportStore.Complaint complaint, boolean staff) {
        return new Detail(complaint, store.comments(complaint.id(), staff), store.history(complaint.id()));
    }

    private AccountDirectory.Account self(Jwt jwt) {
        return accounts.accountForUser(actor(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consumer account was not found."));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static void check(String status, String allowed, String query, int page, int size) {
        if ((status != null && !status.matches(allowed)) || query.length() > 80 || page < 0 || size < 1 || size > 100
                || page > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filters or paging.");
        }
    }

    record NewComplaint(@NotBlank @Pattern(regexp = TYPES) String complaintType,
            @NotBlank @Size(min = 5, max = 160) String subject, @NotBlank @Size(min = 10, max = 4000) String description,
            @Pattern(regexp = "[6-9][0-9]{9}") String contactNumber, UUID meterId, UUID billId) {}
    record NewComment(@NotBlank @Size(max = 4000) String body) {}
    record Rating(@Min(1) @Max(5) int rating, @Size(max = 1000) String feedback) {}
    record StaffUpdate(@Pattern(regexp = STATUSES) String status, UUID assignedAgentId, boolean unassign,
            @Pattern(regexp = PRIORITIES) String priority, @Size(max = 2000) String resolutionSummary,
            @NotNull @PositiveOrZero Long version) {}
    record StaffComment(@NotBlank @Size(max = 4000) String body, boolean internal) {}
    record NewOutage(@NotBlank @Size(max = 100) String regionZone, @NotBlank @Size(max = 100) String city,
            @NotEmpty @Size(max = 50) List<@Pattern(regexp = "[1-9][0-9]{5}") String> affectedPincodes,
            @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String description,
            @NotBlank @Pattern(regexp = "PLANNED|UNPLANNED|EMERGENCY") String severity, Instant estimatedRestorationAt) {}
    record OutageUpdate(@NotBlank @Pattern(regexp = "INVESTIGATING|CREW_DISPATCHED|RESTORING|RESOLVED") String status,
            Instant estimatedRestorationAt, @Size(max = 2000) String description, @NotNull @PositiveOrZero Long version) {}
    record Detail(SupportStore.Complaint complaint, List<SupportStore.Comment> comments,
            List<SupportStore.StatusChange> history) {}
}

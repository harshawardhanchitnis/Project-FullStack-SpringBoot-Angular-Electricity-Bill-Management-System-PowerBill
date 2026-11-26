package com.powerbill.consumer.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.powerbill.audit.AuditAction;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.MeterDirectory;
import com.powerbill.identity.CustomerIdentity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ConsumerService implements MeterDirectory, AccountDirectory {
    private final ConsumerStore store;
    private final CustomerIdentity identity;
    private final ApplicationEventPublisher events;

    ConsumerService(ConsumerStore store, CustomerIdentity identity, ApplicationEventPublisher events) {
        this.store = store;
        this.identity = identity;
        this.events = events;
    }

    @Transactional
    Details register(ConsumerRequests.Registration input, String type, Actor actor, RequestContext request) {
        UUID userId = actor == null
                ? identity.registerCustomer(input.email(), input.password(), input.fullName(), input.mobileNumber(), request)
                : identity.provisionCustomer(input.email(), input.password(), input.fullName(), input.mobileNumber(), request);
        Actor effectiveActor = actor == null ? new Actor(userId, "CUSTOMER") : actor;
        UUID id = UUID.randomUUID();
        store.insertAccount(id, userId, number("PB-"), input.fullName().strip(), type,
                input.email().strip().toLowerCase(Locale.ROOT),
                input.mobileNumber());
        UUID premiseId = UUID.randomUUID();
        store.insertPremise(premiseId, id, input.premise());
        String connection = type.equals("RESIDENTIAL") ? "DOMESTIC" : type;
        store.insertMeter(UUID.randomUUID(), premiseId, number("M-"), connection, BigDecimal.valueOf(3), false, null);
        audit("CONSUMER_CREATED", "CONSUMER", id, effectiveActor, request, Map.of("accountType", type));
        return details(account(id));
    }

    ConsumerStore.Account account(UUID id) {
        return store.account(id).orElseThrow(ConsumerService::missing);
    }

    ConsumerStore.Account self(UUID userId) {
        return store.forUser(userId).orElseThrow(ConsumerService::missing);
    }

    Details details(ConsumerStore.Account account) {
        return new Details(account, store.premises(account.id()), store.meters(account.id()));
    }

    ConsumerStore.Page search(String query, String type, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || query.length() > 254
                || (type != null && !type.matches(ConsumerRequests.TYPE))
                || (status != null && !status.matches(ConsumerRequests.STATUS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid consumer filters or paging.");
        }
        return store.search(query.strip().toLowerCase(Locale.ROOT), type, status, page, size);
    }

    @Transactional
    ConsumerStore.Account update(ConsumerStore.Account account, ConsumerRequests.Update input, Actor actor,
            RequestContext request) {
        if (!store.update(account.id(), input.billingEmail().strip().toLowerCase(Locale.ROOT), input.mobileNumber(),
                input.monthlyBudgetInr(), input.version())) {
            throw conflict();
        }
        audit("CONSUMER_PROFILE_UPDATED", "CONSUMER", account.id(), actor, request, Map.of());
        return account(account.id());
    }

    @Transactional
    ConsumerStore.Account changeStatus(UUID id, ConsumerRequests.StatusChange input, Actor actor,
            RequestContext request) {
        ConsumerStore.Account current = account(id);
        if (!store.status(id, input.status(), input.version())) {
            throw conflict();
        }
        audit("CONSUMER_STATUS_CHANGED", "CONSUMER", id, actor, request,
                Map.of("from", current.status(), "to", input.status()));
        return account(id);
    }

    @Transactional
    ConsumerStore.Premise addPremise(ConsumerStore.Account account, ConsumerRequests.Premise input, Actor actor,
            RequestContext request) {
        UUID id = UUID.randomUUID();
        store.insertPremise(id, account.id(), input);
        audit("PREMISE_CREATED", "PREMISE", id, actor, request, Map.of("accountId", account.id()));
        return store.premises(account.id()).stream().filter(premise -> premise.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    Meter addMeter(UUID accountId, ConsumerRequests.NewMeter input, Actor actor, RequestContext request) {
        account(accountId);
        if (!store.accountHasPremise(accountId, input.premiseId())) {
            throw missing();
        }
        if (input.solarNetMetering() != (input.solarCapacityKw() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Supply solar capacity only when solar net metering is enabled.");
        }
        UUID id = UUID.randomUUID();
        store.insertMeter(id, input.premiseId(), number("M-"), input.connectionType(), input.sanctionedLoadKw(),
                input.solarNetMetering(), input.solarCapacityKw());
        audit("METER_CREATED", "METER", id, actor, request, Map.of("accountId", accountId));
        return store.meters(accountId).stream().filter(meter -> meter.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public Meter customerMeter(UUID userId, UUID meterId) {
        return store.meterForUser(userId, meterId).orElseThrow(ConsumerService::missing);
    }

    @Override
    public Meter meter(UUID meterId) {
        return store.meter(meterId).orElseThrow(ConsumerService::missing);
    }

    @Override
    public List<Meter> demoMeters() {
        return store.demoMeters();
    }

    @Override
    public java.util.Optional<AccountDirectory.Account> accountForUser(UUID userId) {
        return store.forUser(userId).map(ConsumerService::view);
    }

    @Override
    public java.util.Optional<AccountDirectory.Account> findAccount(UUID accountId) {
        return store.account(accountId).map(ConsumerService::view);
    }

    @Override
    public ServicePoint servicePoint(UUID meterId) {
        return store.servicePoint(meterId).orElseThrow(ConsumerService::missing);
    }

    @Override
    public List<ServicePoint> billableServicePoints() {
        return store.billableServicePoints();
    }

    @Override
    public List<ServicePoint> servicePoints(UUID accountId) {
        return store.servicePoints(accountId);
    }

    @Override
    public List<UUID> usersInPincodes(java.util.Collection<String> pincodes) {
        return store.usersInPincodes(pincodes);
    }

    @Override
    public List<String> pincodes(UUID accountId) {
        return store.pincodes(accountId);
    }

    @Transactional
    ConsumerStore.Account autoPay(ConsumerStore.Account account, boolean enabled, Actor actor, RequestContext request) {
        store.autoPay(account.id(), enabled);
        audit("AUTO_PAY_CHANGED", "CONSUMER", account.id(), actor, request, Map.of("enabled", enabled));
        return account(account.id());
    }

    private static AccountDirectory.Account view(ConsumerStore.Account account) {
        return new AccountDirectory.Account(account.id(), account.userId(), account.consumerNumber(),
                account.fullName(), account.accountType(), account.billingEmail(), account.mobileNumber(),
                account.status(), account.monthlyBudgetInr());
    }

    private void audit(String action, String entity, UUID id, Actor actor, RequestContext request,
            Map<String, Object> changes) {
        events.publishEvent(AuditAction.record(action, entity, id, actor.id(), actor.role(), request, changes));
    }

    private static String number(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Consumer resource was not found.");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The account changed. Reload it and try again.");
    }

    record Details(ConsumerStore.Account account, List<ConsumerStore.Premise> premises, List<Meter> meters) {}
    record Actor(UUID id, String role) {
        static Actor from(Jwt jwt) {
            return new Actor(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"));
        }
    }
}

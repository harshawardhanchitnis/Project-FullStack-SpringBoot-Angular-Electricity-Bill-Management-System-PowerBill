package com.powerbill.consumer.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.MeterDirectory.Meter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ConsumerStore {
    private static final String ACCOUNT = """
            SELECT a.id, a.user_id, a.consumer_number, a.full_name, a.account_type, a.billing_email,
                a.mobile_number, a.status, a.auto_pay_enabled, a.monthly_budget_inr, a.created_at, a.version
            FROM consumer_accounts a
            """;
    private static final String FILTER = """
            WHERE (:type IS NULL OR a.account_type=:type)
              AND (:status IS NULL OR a.status=:status)
              AND (:query='' OR strpos(lower(a.full_name),:query)>0
                   OR strpos(lower(a.billing_email),:query)>0 OR strpos(lower(a.consumer_number),:query)>0
                   OR strpos(a.mobile_number,:query)>0)
            """;
    private static final String METER = """
            SELECT m.id,m.premise_id,m.meter_serial,m.connection_type,m.sanctioned_load_kw,m.solar_net_metering,
                m.solar_capacity_kw,m.tariff_plan_id,m.status,m.installed_at,m.version
            FROM smart_meters m JOIN service_premises p ON p.id=m.premise_id
                JOIN consumer_accounts a ON a.id=p.consumer_account_id
            """;
    private final JdbcClient db;

    ConsumerStore(JdbcClient db) {
        this.db = db;
    }

    Optional<Account> account(UUID id) {
        return db.sql(ACCOUNT + " WHERE a.id=?").param(id).query(Account.class).optional();
    }

    Optional<Account> forUser(UUID userId) {
        return db.sql(ACCOUNT + " WHERE a.user_id=?").param(userId).query(Account.class).optional();
    }

    Page search(String query, String type, String status, int page, int size) {
        var matches = db.sql(ACCOUNT + FILTER
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT :size OFFSET :offset")
                .param("query", query).param("type", type, java.sql.Types.VARCHAR)
                .param("status", status, java.sql.Types.VARCHAR).param("size", size)
                .param("offset", (long) page * size);
        List<Account> rows = matches.query(Account.class).list();
        long total = db.sql("SELECT count(*) FROM consumer_accounts a " + FILTER)
                .param("query", query).param("type", type, java.sql.Types.VARCHAR)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Page(rows, page, size, total);
    }

    void insertAccount(UUID id, UUID userId, String number, String name, String accountType,
            String email, String phone) {
        db.sql("""
                INSERT INTO consumer_accounts(id,user_id,consumer_number,full_name,account_type,billing_email,mobile_number)
                VALUES (?,?,?,?,?,?,?)
                """).params(id, userId, number, name, accountType, email, phone).update();
    }

    boolean exists(String consumerNumber) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM consumer_accounts WHERE consumer_number=?)")
                .param(consumerNumber).query(Boolean.class).single();
    }

    boolean update(UUID id, String email, String phone, BigDecimal budget, long version) {
        return db.sql("""
                UPDATE consumer_accounts SET billing_email=?, mobile_number=?, monthly_budget_inr=?,
                    version=version+1, updated_at=now() WHERE id=? AND version=?
                """).params(email, phone, budget, id, version).update() == 1;
    }

    boolean status(UUID id, String status, long version) {
        return db.sql("""
                UPDATE consumer_accounts SET status=?, version=version+1, updated_at=now()
                WHERE id=? AND version=?
                """).params(status, id, version).update() == 1;
    }

    void insertPremise(UUID id, UUID accountId, ConsumerRequests.Premise request) {
        db.sql("""
                INSERT INTO service_premises(id,consumer_account_id,label,address_line,city,state,pincode,
                    region_zone,latitude,longitude)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """).params(id, accountId, request.label().strip(), request.addressLine().strip(),
                request.city().strip(), request.state().strip(), request.pincode(), request.regionZone().strip(),
                request.latitude(), request.longitude()).update();
    }

    boolean accountHasPremise(UUID accountId, UUID premiseId) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM service_premises WHERE consumer_account_id=? AND id=?)")
                .params(accountId, premiseId).query(Boolean.class).single();
    }

    List<Premise> premises(UUID accountId) {
        return db.sql("""
                SELECT id,label,address_line,city,state,pincode,region_zone,latitude,longitude
                FROM service_premises WHERE consumer_account_id=? ORDER BY created_at,id
                """).param(accountId).query(Premise.class).list();
    }

    void insertMeter(UUID id, UUID premiseId, String serial, String type, BigDecimal load,
            boolean solar, BigDecimal capacity) {
        db.sql("""
                INSERT INTO smart_meters(id,premise_id,meter_serial,connection_type,
                    sanctioned_load_kw,solar_net_metering,solar_capacity_kw)
                VALUES (?,?,?,?,?,?,?)
                """).params(id, premiseId, serial, type, load, solar, capacity).update();
    }

    List<Meter> meters(UUID accountId) {
        return db.sql(METER + " WHERE a.id=? ORDER BY m.installed_at,m.id")
                .param(accountId).query(Meter.class).list();
    }

    Optional<Meter> meterForUser(UUID userId, UUID meterId) {
        return db.sql(METER + " WHERE a.user_id=? AND m.id=?").params(userId, meterId)
                .query(Meter.class).optional();
    }

    Optional<Meter> meter(UUID meterId) {
        return db.sql(METER + " WHERE m.id=?").param(meterId).query(Meter.class).optional();
    }

    List<Meter> searchMeters(String text, int limit) {
        // strpos keeps user text literal: % and _ are not wildcards here.
        return db.sql(METER + " WHERE strpos(lower(m.meter_serial), lower(?)) > 0 ORDER BY m.meter_serial LIMIT ?")
                .params(text, limit).query(Meter.class).list();
    }

    List<Meter> demoMeters() {
        return db.sql(METER + " WHERE m.meter_serial LIKE 'M-DEMO-%' ORDER BY m.meter_serial")
                .query(Meter.class).list();
    }

    private static final String SERVICE_POINT = """
            SELECT m.id AS meter_id, m.meter_serial, m.connection_type, m.sanctioned_load_kw, m.solar_net_metering,
                m.solar_capacity_kw, m.tariff_plan_id, m.status AS meter_status, p.id AS premise_id, p.label AS premise_label,
                p.address_line, p.city, p.state, p.pincode, p.region_zone,
                a.id AS account_id, a.user_id, a.consumer_number, a.full_name, a.account_type, a.billing_email,
                a.mobile_number, a.status AS account_status, a.monthly_budget_inr
            FROM smart_meters m JOIN service_premises p ON p.id=m.premise_id
                JOIN consumer_accounts a ON a.id=p.consumer_account_id
            """;

    Optional<AccountDirectory.ServicePoint> servicePoint(UUID meterId) {
        return db.sql(SERVICE_POINT + " WHERE m.id=?").param(meterId).query(ConsumerStore::servicePoint).optional();
    }

    List<AccountDirectory.ServicePoint> billableServicePoints() {
        return db.sql(SERVICE_POINT + " WHERE m.status='ACTIVE' AND a.status<>'DISCONNECTED' ORDER BY a.consumer_number,m.meter_serial")
                .query(ConsumerStore::servicePoint).list();
    }

    List<AccountDirectory.ServicePoint> servicePoints(UUID accountId) {
        return db.sql(SERVICE_POINT + " WHERE a.id=? ORDER BY m.installed_at,m.id").param(accountId)
                .query(ConsumerStore::servicePoint).list();
    }

    List<UUID> usersInPincodes(java.util.Collection<String> pincodes) {
        if (pincodes.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                SELECT DISTINCT a.user_id FROM consumer_accounts a JOIN service_premises p ON p.consumer_account_id=a.id
                WHERE p.pincode IN (:pincodes) AND a.status='ACTIVE'
                """).param("pincodes", List.copyOf(pincodes)).query(UUID.class).list();
    }

    java.util.Map<UUID, String> meterSerials(java.util.Collection<UUID> meterIds) {
        if (meterIds.isEmpty()) {
            return java.util.Map.of();
        }
        var serials = new java.util.HashMap<UUID, String>();
        db.sql("SELECT id, meter_serial FROM smart_meters WHERE id IN (:ids)").param("ids", List.copyOf(meterIds))
                .query((row, index) -> serials.put(row.getObject("id", UUID.class), row.getString("meter_serial")))
                .list();
        return serials;
    }

    List<String> pincodes(UUID accountId) {
        return db.sql("SELECT DISTINCT pincode FROM service_premises WHERE consumer_account_id=? ORDER BY pincode")
                .param(accountId).query(String.class).list();
    }

    private static AccountDirectory.ServicePoint servicePoint(java.sql.ResultSet row, int index)
            throws java.sql.SQLException {
        var account = new AccountDirectory.Account(row.getObject("account_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getString("consumer_number"), row.getString("full_name"),
                row.getString("account_type"), row.getString("billing_email"), row.getString("mobile_number"),
                row.getString("account_status"), row.getBigDecimal("monthly_budget_inr"));
        return new AccountDirectory.ServicePoint(row.getObject("meter_id", UUID.class), row.getString("meter_serial"),
                row.getString("connection_type"), row.getBigDecimal("sanctioned_load_kw"),
                row.getBoolean("solar_net_metering"), row.getBigDecimal("solar_capacity_kw"),
                row.getString("meter_status"), row.getObject("premise_id", UUID.class), row.getString("premise_label"),
                row.getString("address_line"), row.getString("city"), row.getString("state"), row.getString("pincode"),
                row.getString("region_zone"), row.getObject("tariff_plan_id", UUID.class), account);
    }

    /** Changes the auto-pay mandate if the account is still at the version the customer saw. */
    boolean autoPay(UUID id, boolean enabled, long version) {
        return db.sql("""
                UPDATE consumer_accounts SET auto_pay_enabled=?, version=version+1, updated_at=now()
                WHERE id=? AND version=?
                """).params(enabled, id, version).update() == 1;
    }

    boolean autoPayEnabled(UUID id) {
        return db.sql("SELECT auto_pay_enabled FROM consumer_accounts WHERE id=?").param(id).query(Boolean.class)
                .optional().orElse(false);
    }

    List<UUID> autoPayAccounts() {
        return db.sql("SELECT id FROM consumer_accounts WHERE auto_pay_enabled AND status='ACTIVE'")
                .query(UUID.class).list();
    }

    record Account(UUID id, UUID userId, String consumerNumber, String fullName, String accountType,
            String billingEmail, String mobileNumber, String status, boolean autoPayEnabled,
            BigDecimal monthlyBudgetInr, OffsetDateTime createdAt, long version) {}
    record Premise(UUID id, String label, String addressLine, String city, String state, String pincode,
            String regionZone, BigDecimal latitude, BigDecimal longitude) {}
    record Page(List<Account> content, int page, int size, long totalElements) {}
}

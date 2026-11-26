package com.powerbill.consumer.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    record Account(UUID id, UUID userId, String consumerNumber, String fullName, String accountType,
            String billingEmail, String mobileNumber, String status, boolean autoPayEnabled,
            BigDecimal monthlyBudgetInr, OffsetDateTime createdAt, long version) {}
    record Premise(UUID id, String label, String addressLine, String city, String state, String pincode,
            String regionZone, BigDecimal latitude, BigDecimal longitude) {}
    record Page(List<Account> content, int page, int size, long totalElements) {}
}

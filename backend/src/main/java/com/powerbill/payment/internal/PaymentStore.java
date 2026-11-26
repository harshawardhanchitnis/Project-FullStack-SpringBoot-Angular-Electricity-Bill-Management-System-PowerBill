package com.powerbill.payment.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentStore {
    private static final String TRANSACTION = """
            SELECT id,payment_reference,idempotency_key,request_hash,consumer_account_id,initiated_by,gateway_provider,
                gateway_order_id,gateway_payment_id,payment_method,amount,currency,status,failure_reason,webhook_verified,
                expires_at,created_at,completed_at,version
            FROM payment_transactions
            """;
    private final JdbcClient db;

    PaymentStore(JdbcClient db) {
        this.db = db;
    }

    Optional<Transaction> byKey(UUID accountId, UUID key) {
        return db.sql(TRANSACTION + " WHERE consumer_account_id=? AND idempotency_key=?").params(accountId, key)
                .query(Transaction.class).optional();
    }

    Optional<Transaction> find(UUID id, boolean lock) {
        return db.sql(TRANSACTION + " WHERE id=?" + (lock ? " FOR UPDATE" : "")).param(id)
                .query(Transaction.class).optional();
    }

    boolean billHasOpenPayment(List<UUID> billIds, Instant now) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM payment_allocations a JOIN payment_transactions t ON t.id=a.payment_transaction_id
                    WHERE a.bill_id IN (:bills) AND t.status IN ('INITIATED','PENDING') AND t.expires_at>:now)
                """).param("bills", billIds).param("now", Timestamp.from(now)).query(Boolean.class).single();
    }

    void insert(Transaction transaction) {
        db.sql("""
                INSERT INTO payment_transactions(id,payment_reference,idempotency_key,request_hash,consumer_account_id,
                    initiated_by,gateway_provider,gateway_order_id,payment_method,amount,status,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'INITIATED',?,?)
                """).params(transaction.id(), transaction.paymentReference(), transaction.idempotencyKey(),
                transaction.requestHash(), transaction.consumerAccountId(), transaction.initiatedBy(),
                transaction.gatewayProvider(), transaction.gatewayOrderId(), transaction.paymentMethod(),
                transaction.amount(), transaction.expiresAt(), transaction.createdAt()).update();
    }

    void allocate(UUID paymentId, UUID billId, BigDecimal principal, BigDecimal lateFee) {
        db.sql("""
                INSERT INTO payment_allocations(payment_transaction_id,bill_id,principal_amount,late_fee_amount)
                VALUES (?,?,?,?)
                """).params(paymentId, billId, principal, lateFee).update();
    }

    List<Allocation> allocations(UUID paymentId) {
        return db.sql("""
                SELECT a.bill_id, b.bill_number, a.principal_amount, a.late_fee_amount
                FROM payment_allocations a JOIN bills b ON b.id=a.bill_id
                WHERE a.payment_transaction_id=? ORDER BY b.due_date, b.id
                """).param(paymentId).query(Allocation.class).list();
    }

    boolean recordWebhook(String eventId, String provider, UUID paymentId, String digest) {
        return db.sql("""
                INSERT INTO payment_webhook_events(event_id,provider,payment_transaction_id,payload_sha256)
                VALUES (?,?,?,?) ON CONFLICT (event_id) DO NOTHING
                """).params(eventId, provider, paymentId, digest).update() == 1;
    }

    void succeed(UUID id, String gatewayPaymentId, Instant completedAt) {
        db.sql("""
                UPDATE payment_transactions SET status='SUCCEEDED', gateway_payment_id=?, webhook_verified=true,
                    completed_at=?, version=version+1 WHERE id=? AND status IN ('INITIATED','PENDING')
                """).params(gatewayPaymentId, Timestamp.from(completedAt), id).update();
    }

    void fail(UUID id, String status, String reason, boolean verified) {
        db.sql("""
                UPDATE payment_transactions SET status=?, failure_reason=?, webhook_verified=?,
                    completed_at=now(), version=version+1 WHERE id=? AND status IN ('INITIATED','PENDING')
                """).params(status, reason, verified, id).update();
    }

    int expireStale(Instant now) {
        return db.sql("""
                UPDATE payment_transactions SET status='EXPIRED', failure_reason='Checkout expired', completed_at=now(),
                    version=version+1 WHERE status IN ('INITIATED','PENDING') AND expires_at<=?
                """).param(Timestamp.from(now)).update();
    }

    Page page(UUID accountId, String status, int page, int size) {
        String filter = """
                 WHERE (CAST(:account AS uuid) IS NULL OR consumer_account_id=:account)
                   AND (CAST(:status AS text) IS NULL OR status=:status)
                """;
        var rows = db.sql(TRANSACTION + filter + " ORDER BY created_at DESC, id LIMIT :size OFFSET :offset")
                .param("account", accountId, Types.OTHER).param("status", status, Types.VARCHAR)
                .param("size", size).param("offset", (long) page * size).query(Transaction.class).list();
        long total = db.sql("SELECT count(*) FROM payment_transactions " + filter)
                .param("account", accountId, Types.OTHER).param("status", status, Types.VARCHAR)
                .query(Long.class).single();
        return new Page(rows, page, size, total);
    }

    record Transaction(UUID id, String paymentReference, UUID idempotencyKey, String requestHash,
            UUID consumerAccountId, UUID initiatedBy, String gatewayProvider, String gatewayOrderId,
            String gatewayPaymentId, String paymentMethod, BigDecimal amount, String currency, String status,
            String failureReason, boolean webhookVerified, OffsetDateTime expiresAt, OffsetDateTime createdAt,
            OffsetDateTime completedAt, long version) {}

    record Allocation(UUID billId, String billNumber, BigDecimal principalAmount, BigDecimal lateFeeAmount) {}

    record Page(List<Transaction> content, int page, int size, long totalElements) {}
}

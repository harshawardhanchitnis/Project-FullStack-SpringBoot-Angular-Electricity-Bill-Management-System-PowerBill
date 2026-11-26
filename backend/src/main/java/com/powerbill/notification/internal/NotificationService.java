package com.powerbill.notification.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.powerbill.analytics.AnalyticsEvents;
import com.powerbill.billing.BillingEvents;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.identity.StaffDirectory;
import com.powerbill.identity.UserDirectory;
import com.powerbill.metering.MeteringEvents;
import com.powerbill.payment.PaymentEvents;
import com.powerbill.support.SupportEvents;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

/** Turns domain events into in-app notifications, live SSE pushes and transactional emails. */
@Service
class NotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private final NotificationStore store;
    private final NotificationStream stream;
    private final AccountDirectory accounts;
    private final StaffDirectory staff;
    private final UserDirectory users;
    private final JavaMailSender mail;
    private final ObjectMapper json;
    private final String from;
    private final String appUrl;

    NotificationService(NotificationStore store, NotificationStream stream, AccountDirectory accounts,
            StaffDirectory staff, UserDirectory users, JavaMailSender mail, ObjectMapper json,
            @Value("${gridiq.auth.mail-from}") String from, @Value("${gridiq.auth.frontend-url}") String appUrl) {
        this.store = store;
        this.stream = stream;
        this.accounts = accounts;
        this.staff = staff;
        this.users = users;
        this.mail = mail;
        this.json = json;
        this.from = from;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillIssued event) {
        String period = YearMonth.parse(event.period()).format(PERIOD);
        String message = inr(event.totalAmount()) + " is due by " + DATE.format(event.dueDate()) + ".";
        boolean created = notify(event.userId(), "BILLING", "Your " + period + " bill is ready", message,
                "/app/bills?bill=" + event.billId(), Map.of("billId", event.billId()), "bill-issued:" + event.billId());
        if (created && !event.dueDate().isBefore(LocalDate.now(INDIA))) {
            email(event.billingEmail(), "Your " + period + " electricity bill " + event.billNumber(),
                    "Your electricity bill for " + period + " is ready.",
                    List.of("Invoice: " + event.billNumber(), "Amount: " + inr(event.totalAmount()),
                            "Due date: " + DATE.format(event.dueDate())),
                    "/app/bills?bill=" + event.billId(), "View and pay your bill");
        }
    }

    @ApplicationModuleListener
    void on(BillingEvents.BillOverdue event) {
        notify(event.userId(), "BILLING", "Bill " + event.billNumber() + " is overdue",
                "The due date was " + DATE.format(event.dueDate()) + ". A late payment surcharge now accrues daily.",
                "/app/bills?bill=" + event.billId(), Map.of("billId", event.billId()), "bill-overdue:" + event.billId());
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentSucceeded event) {
        boolean created = notify(event.userId(), "PAYMENT", "Payment received",
                inr(event.amount()) + " settled " + String.join(", ", event.billNumbers()) + ".",
                "/app/bills?tab=payments", Map.of("paymentId", event.paymentId()), "payment:" + event.paymentId());
        boolean recent = event.occurredAt() == null
                || Duration.between(event.occurredAt(), Instant.now()).compareTo(Duration.ofDays(1)) < 0;
        if (created && recent) {
            accounts.findAccount(event.accountId()).ifPresent(account -> email(account.billingEmail(),
                    "Payment received " + event.paymentReference(), "Thank you. We received your payment.",
                    List.of("Receipt: " + event.paymentReference(), "Amount: " + inr(event.amount()),
                            "Bills: " + String.join(", ", event.billNumbers()),
                            "Method: " + event.method().replace('_', ' ')),
                    "/app/bills?tab=payments", "Download your receipt"));
        }
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PaymentFailed event) {
        notify(event.userId(), "PAYMENT", "Payment " + event.paymentReference() + " failed", event.reason(),
                "/app/bills", Map.of("paymentId", event.paymentId()), "payment-failed:" + event.paymentId());
    }

    @ApplicationModuleListener
    void on(SupportEvents.ComplaintUpdated event) {
        String key = "complaint:" + event.complaintId() + ":" + event.status() + ":" + Instant.now().toEpochMilli();
        if (event.notifyCustomer()) {
            notify(event.customerUserId(), "COMPLAINT", "Complaint " + event.ticketNumber(), event.summary(),
                    "/app/support?ticket=" + event.complaintId(), Map.of("complaintId", event.complaintId()), key);
        }
        if (event.notifyAgent() && event.assignedAgentId() != null) {
            notify(event.assignedAgentId(), "COMPLAINT", "Ticket " + event.ticketNumber(), event.summary(),
                    "/ops/support?ticket=" + event.complaintId(), Map.of("complaintId", event.complaintId()),
                    key + ":agent");
        }
    }

    @ApplicationModuleListener
    void on(SupportEvents.SlaBreached event) {
        var recipients = event.assignedAgentId() != null ? List.of(event.assignedAgentId())
                : staff.activeStaff(Set.of("SUPPORT_AGENT")).stream().map(StaffDirectory.StaffMember::id).toList();
        for (UUID recipient : recipients) {
            notify(recipient, "COMPLAINT", "SLA breached on " + event.ticketNumber(),
                    event.priority() + " priority ticket passed its resolution deadline.",
                    "/ops/support?ticket=" + event.complaintId(), Map.of("complaintId", event.complaintId()),
                    "sla:" + event.complaintId());
        }
    }

    @ApplicationModuleListener
    void on(SupportEvents.OutageChanged event) {
        String restoration = event.estimatedRestorationAt() == null ? ""
                : " Estimated restoration: " + DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
                        .withZone(INDIA).format(event.estimatedRestorationAt()) + " IST.";
        String title = event.status().equals("RESOLVED") ? "Power restored: " + event.title()
                : (event.declared() ? "" : "Update: ") + event.title();
        String message = event.status().equals("RESOLVED") ? "Supply has been restored in your area."
                : "Status: " + event.status().replace('_', ' ').toLowerCase(Locale.ROOT) + "." + restoration;
        for (UUID user : new HashSet<>(accounts.usersInPincodes(event.affectedPincodes()))) {
            notify(user, "OUTAGE", title, message, "/app/support", Map.of("incidentId", event.incidentId()),
                    "outage:" + event.incidentId() + ":" + event.status());
        }
    }

    @ApplicationModuleListener
    void on(MeteringEvents.AnomalyFlagged event) {
        if (!event.severity().equals("HIGH") && !event.severity().equals("CRITICAL")) {
            return;
        }
        for (var member : staff.activeStaff(Set.of("BILLING_OFFICER", "FIELD_ENGINEER"))) {
            notify(member.id(), "ANOMALY", event.anomalyType().replace('_', ' ') + " anomaly flagged", event.summary(),
                    "/ops/anomalies?anomaly=" + event.anomalyId(), Map.of("anomalyId", event.anomalyId()),
                    "anomaly:" + event.anomalyId());
        }
    }

    @ApplicationModuleListener
    void on(AnalyticsEvents.BudgetAlert event) {
        var contact = users.contact(event.userId());
        if (contact.isEmpty() || !contact.get().budgetAlerts()) {
            return;
        }
        String message = "Your " + event.meterSerial() + " bill is projected at " + inr(event.projectedBillInr())
                + ". " + event.reason();
        boolean created = notify(event.userId(), "BUDGET_ALERT", "Bill-shock alert for " + event.cycle(), message,
                "/app/dashboard", Map.of("meterId", event.meterId()),
                "budget:" + event.meterId() + ":" + event.cycle() + ":" + event.reason().hashCode());
        if (created) {
            accounts.findAccount(event.accountId()).ifPresent(account -> email(account.billingEmail(),
                    "Bill-shock alert for " + event.cycle(), "Your usage is trending above your plan.",
                    List.of("Meter: " + event.meterSerial(), "Projected bill: " + inr(event.projectedBillInr()),
                            event.reason()), "/app/dashboard", "See ways to save"));
        }
    }

    private boolean notify(UUID user, String category, String title, String message, String link,
            Map<String, Object> metadata, String dedupeKey) {
        if (user == null) {
            return false;
        }
        var created = store.insert(user, "IN_APP", category, truncate(title, 160), truncate(message, 1000), link,
                json.writeValueAsString(metadata), truncate(dedupeKey, 120));
        created.ifPresent(notification -> afterCommit(() -> stream.push(user, notification, store.unread(user))));
        return created.isPresent();
    }

    /**
     * Defers live pushes and emails until the notification row is committed, so a rolled-back listener never shows
     * a notification that the inbox does not have, and a slow mail server never holds the transaction open.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void email(String to, String subject, String heading, List<String> lines, String path, String action) {
        afterCommit(() -> send(to, subject, heading, lines, path, action));
    }

    private void send(String to, String subject, String heading, List<String> lines, String path, String action) {
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            String items = lines.stream().map(line -> "<li style=\"margin:4px 0\">" + HtmlUtils.htmlEscape(line) + "</li>")
                    .reduce("", String::concat);
            String text = heading + "\n\n" + String.join("\n", lines) + "\n\n" + action + ": " + appUrl + path;
            String html = """
                    <div style="font-family:Inter,Arial,sans-serif;max-width:560px;margin:auto;color:#0f172a">
                      <p style="color:#1d4ed8;font-weight:700;margin:0 0 12px">PowerBill GridIQ</p>
                      <h1 style="font-size:20px;margin:0 0 12px">%s</h1>
                      <ul style="padding-left:18px;font-size:14px">%s</ul>
                      <p><a href="%s" style="display:inline-block;background:#1d4ed8;color:#fff;padding:10px 16px;
                        border-radius:6px;text-decoration:none;font-weight:600">%s</a></p>
                      <p style="color:#64748b;font-size:12px">You receive this message because you have an electricity
                        account with PowerBill GridIQ.</p>
                    </div>
                    """.formatted(HtmlUtils.htmlEscape(heading), items, HtmlUtils.htmlEscape(appUrl + path),
                    HtmlUtils.htmlEscape(action));
            helper.setText(text, html);
            mail.send(message);
        } catch (MessagingException | RuntimeException error) {
            // The in-app notification is already committed; a mail failure must never undo it.
            LOG.warn("Could not deliver a notification email ({})", error.getClass().getSimpleName());
        }
    }

    private static String inr(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    private static String truncate(String value, int length) {
        return value.length() > length ? value.substring(0, length - 1) + "…" : value;
    }
}

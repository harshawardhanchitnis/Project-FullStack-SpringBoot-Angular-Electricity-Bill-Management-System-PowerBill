package com.powerbill.billing.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.powerbill.billing.BillingLedger;
import com.powerbill.billing.internal.BillingStore.BillRow;
import com.powerbill.common.RequestContext;
import com.powerbill.consumer.AccountDirectory;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class BillingController {
    private static final String STATUS = "ISSUED|PARTIALLY_PAID|PAID|OVERDUE|DISPUTED|VOID|OPEN|SETTLED";
    private final BillingService billing;
    private final BillingStore store;
    private final AccountDirectory accounts;
    private final InvoicePdf invoices;

    BillingController(BillingService billing, BillingStore store, AccountDirectory accounts, InvoicePdf invoices) {
        this.billing = billing;
        this.store = store;
        this.accounts = accounts;
        this.invoices = invoices;
    }

    // ----- Customer self-service -----

    @GetMapping("/bills")
    Page bills(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID meterId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var account = self(jwt);
        return page(store.search(account.id(), meterId, filter(status), year, null, "", checkPage(page, size), size),
                serials(account.id()));
    }

    @GetMapping("/bills/summary")
    BillingLedger.Receivables summary(@AuthenticationPrincipal Jwt jwt) {
        return billing.receivables(self(jwt).id());
    }

    @GetMapping("/bills/{id}")
    Detail bill(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return detail(owned(jwt, id));
    }

    @GetMapping("/bills/{id}/pdf")
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return pdf(owned(jwt, id));
    }

    @GetMapping("/consumers/me/ledger")
    List<BillingStore.LedgerLine> ledger(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") int limit) {
        return store.ledger(self(jwt).id(), Math.min(Math.max(limit, 1), 200));
    }

    // ----- Operations -----

    @GetMapping("/ops/bills")
    Page opsBills(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (q.length() > 40) {
            throw bad("The search text is too long.");
        }
        return page(store.search(accountId, null, filter(status), year, month, q.strip().toLowerCase(Locale.ROOT),
                checkPage(page, size), size), Map.of());
    }

    @GetMapping("/ops/bills/{id}")
    Detail opsBill(@PathVariable UUID id) {
        return detail(store.bill(id, false).orElseThrow(BillingService::missing));
    }

    @GetMapping("/ops/bills/{id}/pdf")
    ResponseEntity<byte[]> opsPdf(@PathVariable UUID id) {
        return pdf(store.bill(id, false).orElseThrow(BillingService::missing));
    }

    @PostMapping("/ops/bills/generate")
    @ResponseStatus(HttpStatus.CREATED)
    Detail generate(@Valid @RequestBody Generate input, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return detail(billing.generateOne(input.meterId(), YearMonth.of(input.year(), input.month()), actor(jwt),
                role(jwt), RequestContext.from(request)));
    }

    @PostMapping("/ops/bills/runs")
    BillingService.RunResult run(@Valid @RequestBody RunRequest input,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        UUID idempotency = null;
        if (key != null) {
            try {
                idempotency = UUID.fromString(key);
            } catch (IllegalArgumentException invalid) {
                throw bad("The Idempotency-Key must be a UUID.");
            }
        }
        return billing.run(YearMonth.of(input.year(), input.month()), input.dryRun(), idempotency, actor(jwt),
                role(jwt), RequestContext.from(request));
    }

    @GetMapping("/ops/bills/runs")
    List<BillingStore.Run> runs() {
        return store.runs(20);
    }

    @PostMapping("/ops/bills/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    Detail adjust(@PathVariable UUID id, @Valid @RequestBody Adjustment input, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return detail(billing.adjust(id, input.noteType(), input.amount(), input.reason(), actor(jwt), role(jwt),
                RequestContext.from(request)));
    }

    @GetMapping("/ops/consumers/{id}/ledger")
    List<BillingStore.LedgerLine> opsLedger(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        return store.ledger(id, Math.min(Math.max(limit, 1), 200));
    }

    // ----- Helpers -----

    private AccountDirectory.Account self(Jwt jwt) {
        return accounts.accountForUser(actor(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consumer account was not found."));
    }

    private BillRow owned(Jwt jwt, UUID id) {
        var account = self(jwt);
        return store.bill(id, false).filter(bill -> bill.consumerAccountId().equals(account.id()))
                .orElseThrow(BillingService::missing);
    }

    private Map<UUID, String> serials(UUID accountId) {
        return accounts.servicePoints(accountId).stream()
                .collect(Collectors.toMap(ServicePoint::meterId, ServicePoint::meterSerial));
    }

    private Page page(BillingStore.Page rows, Map<UUID, String> serials) {
        LocalDate today = LocalDate.now(BillingService.INDIA);
        return new Page(rows.content().stream().map(row -> summary(row, serials.get(row.meterId()), today)).toList(),
                rows.page(), rows.size(), rows.totalElements());
    }

    private Detail detail(BillRow bill) {
        ServicePoint point = accounts.servicePoint(bill.meterId());
        return new Detail(summary(bill, point.meterSerial(), LocalDate.now(BillingService.INDIA)),
                bill.tariffCode(), bill.tariffVersion(), bill.tariffName(), bill.openingCreditKwh(),
                bill.carriedForwardCreditKwh(), bill.energyCharge(), bill.todAdjustment(), bill.fixedCharge(),
                bill.facCharge(), bill.solarExportCredit(), bill.taxAmount(), bill.creditNoteTotal(),
                bill.debitNoteTotal(), bill.intervalCount(), store.lines(bill.id()), store.notes(bill.id()),
                point.account().consumerNumber(), point.account().fullName(), point.premiseLabel());
    }

    private ResponseEntity<byte[]> pdf(BillRow bill) {
        ServicePoint point = accounts.servicePoint(bill.meterId());
        byte[] document = invoices.render(bill, point, store.lines(bill.id()), store.notes(bill.id()),
                BillingService.accruedLateFee(bill, LocalDate.now(BillingService.INDIA)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(bill.billNumber() + ".pdf").build().toString())
                .body(document);
    }

    private static Summary summary(BillRow bill, String serial, LocalDate today) {
        BigDecimal fee = BillingService.accruedLateFee(bill, today);
        return new Summary(bill.id(), bill.billNumber(), bill.consumerAccountId(), bill.meterId(), serial,
                YearMonth.of(bill.billingYear(), bill.billingMonth()), bill.periodStart(), bill.periodEnd(),
                bill.importKwh(), bill.exportKwh(), bill.netBillableKwh(), bill.totalAmount(), bill.paidAmount(),
                bill.lateFee(), bill.principalOutstanding(), fee, bill.principalOutstanding().add(fee), bill.issueDate(),
                bill.dueDate(), bill.status(), bill.paidAt());
    }

    private static String filter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!status.matches(STATUS)) {
            throw bad("Unknown bill status.");
        }
        return status;
    }

    private static int checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > 10_000) {
            throw bad("Invalid page or size.");
        }
        return page;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String role(Jwt jwt) {
        return jwt.getClaimAsString("role");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Generate(@NotNull UUID meterId, @Min(2000) @Max(2100) int year, @Min(1) @Max(12) int month) {}
    record RunRequest(@Min(2000) @Max(2100) int year, @Min(1) @Max(12) int month, boolean dryRun) {}
    record Adjustment(@NotBlank @Pattern(regexp = "CREDIT|DEBIT") String noteType,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {}
    record Summary(UUID id, String billNumber, UUID consumerAccountId, UUID meterId, String meterSerial,
            YearMonth period, LocalDate periodStart, LocalDate periodEnd, BigDecimal importKwh, BigDecimal exportKwh,
            BigDecimal netBillableKwh, BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal lateFeePaid,
            BigDecimal principalDue, BigDecimal accruedLateFee, BigDecimal totalDue, LocalDate issueDate,
            LocalDate dueDate, String status, OffsetDateTime paidAt) {}
    record Detail(Summary bill, String tariffCode, int tariffVersion, String tariffName, BigDecimal openingCreditKwh,
            BigDecimal carriedForwardCreditKwh, BigDecimal energyCharge, BigDecimal todAdjustment,
            BigDecimal fixedCharge, BigDecimal facCharge, BigDecimal solarExportCredit, BigDecimal taxAmount,
            BigDecimal creditNoteTotal, BigDecimal debitNoteTotal, int intervalCount, List<BillingStore.Line> lineItems,
            List<BillingStore.Note> notes, String consumerNumber, String consumerName, String premiseLabel) {}
    record Page(List<Summary> content, int page, int size, long totalElements) {}
}

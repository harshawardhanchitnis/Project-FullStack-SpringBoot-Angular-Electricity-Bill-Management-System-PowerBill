package com.powerbill.billing.internal;

import static com.powerbill.common.PdfKit.inr;
import static com.powerbill.common.PdfKit.kwh;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.powerbill.billing.internal.BillingStore.BillRow;
import com.powerbill.common.PdfKit;
import com.powerbill.consumer.AccountDirectory.ServicePoint;
import org.springframework.stereotype.Component;

@Component
class InvoicePdf {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    byte[] render(BillRow bill, ServicePoint point, List<BillingStore.Line> lines, List<BillingStore.Note> notes,
            BigDecimal accruedLateFee) {
        String period = bill.periodStart().format(PERIOD);
        return PdfKit.render("Electricity invoice " + bill.billNumber(), "Electricity bill for " + period, page -> {
            page.qr(verification(bill), 84);
            page.heading("PowerBill GridIQ · Electricity distribution", "Tax invoice");
            var header = new LinkedHashMap<String, String>();
            header.put("Invoice number", bill.billNumber());
            header.put("Billing period", DATE.format(bill.periodStart()) + " – " + DATE.format(bill.periodEnd()));
            header.put("Issue date", DATE.format(bill.issueDate()));
            header.put("Due date", DATE.format(bill.dueDate()));
            header.put("Status", bill.status().replace('_', ' '));
            page.details(header);

            page.section("Consumer");
            var consumer = new LinkedHashMap<String, String>();
            consumer.put("Name", point.account().fullName());
            consumer.put("Consumer number", point.account().consumerNumber());
            consumer.put("Service address", point.addressLine() + ", " + point.city() + ", " + point.state() + " "
                    + point.pincode());
            consumer.put("Meter", point.meterSerial() + " · " + point.connectionType() + " · "
                    + point.sanctionedLoadKw().stripTrailingZeros().toPlainString() + " kW sanctioned");
            consumer.put("Tariff", bill.tariffName() + " (" + bill.tariffCode() + " v" + bill.tariffVersion() + ")");
            page.details(consumer);

            page.section("Energy");
            var energy = new LinkedHashMap<String, String>();
            energy.put("Imported", kwh(bill.importKwh()));
            if (point.solarNetMetering()) {
                energy.put("Exported (solar)", kwh(bill.exportKwh()));
                energy.put("Credit brought forward", kwh(bill.openingCreditKwh()));
                energy.put("Credit carried forward", kwh(bill.carriedForwardCreditKwh()));
            }
            energy.put("Net billable", kwh(bill.netBillableKwh()));
            energy.put("Validated intervals", Integer.toString(bill.intervalCount()));
            page.details(energy);

            page.section("Charges");
            var table = page.table(new float[] {3.2f, 1.2f, 1.1f, 1.3f}, "Description", "Units", "Rate", "Amount");
            for (var line : lines) {
                page.row(table, false, line.description(), line.units() == null ? "" : line.units().stripTrailingZeros()
                        .toPlainString(), line.rate() == null ? "" : line.rate().stripTrailingZeros().toPlainString(),
                        inr(line.amount()));
            }
            page.row(table, true, "Invoice total", "", "", inr(bill.totalAmount()));
            for (var note : notes) {
                page.row(table, false, (note.noteType().equals("CREDIT") ? "Credit note " : "Debit note ")
                        + note.noteNumber() + " · " + note.reason(), "", "",
                        inr(note.noteType().equals("CREDIT") ? note.amount().negate() : note.amount()));
            }
            if (bill.lateFee().signum() > 0) {
                page.row(table, false, "Late payment surcharge", "", "", inr(bill.lateFee()));
            }
            if (bill.paidAmount().signum() > 0) {
                page.row(table, false, "Payments received", "", "", inr(bill.paidAmount().negate()));
            }
            BigDecimal due = bill.principalOutstanding().add(accruedLateFee);
            if (accruedLateFee.signum() > 0) {
                page.row(table, false, "Late payment surcharge accrued to date", "", "", inr(accruedLateFee));
            }
            page.row(table, true, "Amount due", "", "", inr(due));
            page.add(table);
            page.note("Electricity duty is levied by the state government. Late payment surcharge accrues daily after "
                    + bill.graceDays() + " grace days at " + bill.lateFeeDailyPct().stripTrailingZeros().toPlainString()
                    + "% of the unpaid amount per day, capped at "
                    + bill.lateFeeCapPct().stripTrailingZeros().toPlainString() + "%.");
            page.note("The code encodes the invoice number, amount and due date. This document was generated "
                    + "from validated smart meter intervals.");
        });
    }

    private static String verification(BillRow bill) {
        return "GRIDIQ-INVOICE|" + bill.billNumber() + "|" + bill.totalAmount().toPlainString() + "|"
                + bill.dueDate();
    }
}

# Five-minute walkthrough

Start the stack with demo data as described in the [README](../README.md), open http://127.0.0.1:8080 and keep
[Mailpit](http://127.0.0.1:8025) in a second tab.

## 1. The customer view (2 minutes)

1. Sign in as `customer01@demo.powerbill.invalid`. The dashboard shows the projected bill with a bill-shock badge, the
   slab progress bar, 12 months of usage against billed amounts, the cycle forecast band and the hour-of-day profile
   coloured by tariff window. Point out that the forecast comes from the ML service and is priced by the same tariff
   engine that issues invoices.
2. Open **Copilot** and ask "Why did my bill change?". Every figure matches the invoice line items. The answer names
   the deterministic provider; with the optional Ollama profile the wording changes but the numbers cannot.
3. Go to **Bills & payments**, open a bill and walk through "How this bill was calculated": slab by slab, the
   time-of-day adjustment, fixed charge, FAC and duty. Download the PDF invoice.
4. Select two overdue bills and pay them. The late fee is computed on the server. Approve in the sandbox gateway,
   then show the live "Payment received" notification, the receipt PDF and the email in Mailpit.
5. In **Energy & simulator**, run the what-if simulator with 30% load shifting and 3 kW of rooftop solar.

## 2. The utility view (2 minutes)

1. Sign in as `admin@demo.powerbill.invalid`. The executive overview shows billed versus collected revenue, collection
   efficiency, receivables aging, the slab mix, open anomalies and SLA compliance.
2. Open **VEE & anomalies**. Each finding has a score and plain-language reasons, such as a 72-hour near-zero draw
   below the meter's own 28-day median. Confirm one as tamper.
3. Open **Support desk & outages**, choose the critical outage ticket and run **AI triage**. It links the ticket to the
   declared feeder trip and drafts a reply. Use it, then resolve the ticket with a summary.
4. In **Billing & runs**, preview last month's run, then issue a credit note on a bill and show it on the invoice.
5. Finish in **Audit trail**: the payment, credit note and ticket changes are all recorded with correlation IDs.

## 3. Engineering points (1 minute)

- Spring Modulith boundaries are verified on every build, and modules talk through events.
- Issued invoices and ledger entries cannot change; a deferred trigger rejects unbalanced journals.
- Multi-bill payments settle completely or not at all, idempotency keys make retries safe, and webhooks are signed.
- The ML service holds no data, falls back within eight seconds, and the copilot rejects ungrounded numbers.

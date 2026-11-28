# Local demo accounts

Set `APP_PROFILE=dev`, `DEMO_MODE=true` and a locally chosen `DEMO_PASSWORD` of 12 to 128 characters in the ignored
`.env` file before the first start. Every demo account uses that password. Demo accounts can sign in only while the
development profile and demo flag are both enabled, and the sign-in page then offers persona buttons that fill in the
email.

| Persona | Email | What to look at |
| --- | --- | --- |
| Residential customer with two meters and a budget | `customer01@demo.powerbill.invalid` | Bill-shock alert, two meters, slab progress, Copilot |
| Rooftop solar prosumer | `customer06@demo.powerbill.invalid` | Solar export, kWh credit carried forward, an outage ticket |
| Commercial account | `customer10@demo.powerbill.invalid` | Higher tariff, voltage complaint assigned to a field engineer |
| Industrial account | `customer15@demo.powerbill.invalid` | Industrial tariff, outage notifications for Mumbai 400001 |
| Billing officer | `billing@demo.powerbill.invalid` | Billing runs, adjustments, tariff studio, anomaly queue |
| Support agent | `support@demo.powerbill.invalid` | Support desk, AI triage, outages |
| Field engineer | `field@demo.powerbill.invalid` | Anomaly investigations, outage updates |
| Super administrator | `admin@demo.powerbill.invalid` | Executive overview, audit trail, staff and access |

Customers `customer01` to `customer15` exist; 1 to 9 are residential, 10 to 13 commercial and 14 to 15 industrial.
Accounts 3, 6, 9, 12 and 15 have rooftop solar, and accounts 1 to 10 have a second meter. Accounts 1 to 6 have a
monthly budget.

## Seeded data

- Thirteen months of hourly readings for 25 meters. Each start adds the hours since the latest reading, without
  filling older gaps. Households of the same size use different amounts, so peer comparisons have a real spread.
- Three anomaly scenarios for the detector: a 72-hour near-zero draw on `M-DEMO-04-1`, a night baseload jump on
  `M-DEMO-07-1` and a 48-hour zero-read flatline on `M-DEMO-12-1`. Scenarios are injected only into hours inserted
  on that start, so a fresh volume shows all three.
- VEE exceptions for the staff tools on `M-DEMO-03-1`: a three-hour voltage sag the day before yesterday (flagged)
  and a four-hour communications outage yesterday (missing hours to estimate). Open **VEE & anomalies → VEE &
  telemetry** and search for the meter.
- A four-day current-transformer fault on `M-DEMO-14-1` two months ago. Its flagged readings take that month below
  the 90% coverage rule, so the bill is held, and the executive overview's regional loss proxy shows the unbilled energy.
- Invoices for the last twelve months (about 300), paid on time up to three months ago (and for even-numbered
  accounts two months ago), so recent bills are open or overdue with accrued late fees.
- Six complaints in every lifecycle state, a feeder trip in Pune 411001 and planned maintenance in Mumbai 400001.

Data persists across restarts. Run `docker compose down -v` to start again from an empty database.

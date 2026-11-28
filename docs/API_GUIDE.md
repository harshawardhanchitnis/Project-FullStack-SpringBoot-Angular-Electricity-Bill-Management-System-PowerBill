# GridIQ API guide

All endpoints live under `http://127.0.0.1:8080/api/v1` behind Nginx. Bodies are JSON unless noted. Errors use RFC 9457
Problem Details with a `correlationId` and, for validation failures, `fieldErrors`. Send `X-Request-Id` to choose the
correlation ID. With the development profile, Swagger UI is available inside the Compose network at
`/swagger-ui.html` on the Core API; Nginx does not expose it.

Send `Authorization: Bearer <accessToken>` on protected requests. Lists accept `page` and `size` (at most 100) and
return `{content, page, size, totalElements}`.

## Identity

| Method and path | Who | Result |
| --- | --- | --- |
| `POST /auth/register` | Anyone | Creates a residential customer with a premise and default meter, and emails a verification link |
| `POST /auth/login` | Anyone | Returns a 15-minute access token and sets the `gridiq_refresh` HttpOnly cookie. Limited to 5 attempts a minute per IP and email |
| `POST /auth/refresh` | Cookie holder | Rotates the refresh cookie. Requires `X-GridIQ-Session: 1`. Returns 204 when there is no cookie; reusing a rotated cookie revokes the session family |
| `POST /auth/logout` | Cookie holder | Revokes the session family. Requires `X-GridIQ-Session: 1` |
| `POST /auth/forgot-password`, `POST /auth/reset-password`, `POST /auth/verify-email` | Anyone | Email-token flows. Links carry the token in the URL fragment |
| `POST /auth/change-password`, `GET /auth/me`, `PATCH /auth/me/preferences` | Signed in | Password change signs out other sessions; preferences are `theme` and `budgetAlerts` |
| `GET /ops/staff` | Staff | Active agents and engineers for assignment |
| `GET, POST /ops/users`, `PATCH /ops/users/{id}` | Super admin | Create staff, change role or status. Changes revoke the user's sessions |

## Consumers and meters

| Method and path | Who | Result |
| --- | --- | --- |
| `GET, PUT /consumers/me`, `GET /consumers/me/meters`, `POST /consumers/me/premises` | Customer | Own account, premises and meters. `PUT` needs the current `version` |
| `PATCH /consumers/me/auto-pay` | Customer | `{enabled, version}`. Enrolled accounts have each issued bill paid through the gateway, and open bills at the daily 06:30 IST run |
| `GET /consumers/me/ledger` | Customer | Receivable statement with running balance |
| `GET, POST /ops/consumers`, `GET, PUT /ops/consumers/{id}`, `PATCH /ops/consumers/{id}/status`, `POST /ops/consumers/{id}/meters` | Billing, support, admin | Directory search (`q`, `type`, `status`), 360° details and audited changes. `PUT` takes `{billingEmail, mobileNumber, monthlyBudgetInr, version}`; a new meter takes `{premiseId, connectionType, sanctionedLoadKw, solarNetMetering, solarCapacityKw}` and gets its serial assigned |
| `GET /ops/consumers/{id}/ledger` | Billing, support, admin | Account statement |

## Metering

| Method and path | Who | Result |
| --- | --- | --- |
| `GET /meters/{id}/readings?from&to&granularity=hourly\|daily\|monthly` | Owning customer | Daily and monthly ranges must start and end at midnight IST |
| `GET /meters/{id}/export/csv`, `GET /meters/{id}/export/green-button` | Owning customer | Downloads; flagged hours are omitted |
| `POST /meters/{id}/readings` | Billing, field, admin | One reading or an array of up to 2,000 hourly readings, each checked by VEE |
| `POST /meters/{id}/import/csv` (text/csv), `POST /meters/{id}/import/green-button` (Atom XML) | Billing, field, admin | Imports with duplicate detection |
| `POST /meters/{id}/readings/estimate`, `PATCH /meters/{id}/readings` | Billing, field, admin | Estimate a missing hour from the same hour on prior days, or correct a flagged reading with a reason |
| `GET /ops/anomalies?status`, `PATCH /ops/anomalies/{id}` | Billing, field, admin | Investigation queue with reason codes. Outcomes: `INVESTIGATING`, `CONFIRMED_FAULT`, `CONFIRMED_TAMPER`, `FALSE_POSITIVE` |
| `GET /ops/metering/meters?q` | Billing, field, admin | Up to 20 meters whose serial contains `q` (at least two characters) |
| `GET /ops/metering/meters/{id}/intervals?from&to` | Billing, field, admin | Hourly readings with quality flags, the flagged count, and the missing hours (aligned to IST hours) in the window |
| `POST /ops/metering/simulate` | Billing, field, admin | Fills missing hours for a demo meter with simulated readings; existing hours are kept |

## Tariffs

| Method and path | Who | Result |
| --- | --- | --- |
| `GET /tariffs` | Signed in | Plans in force today |
| `POST /tariffs/simulate` | Customer | Replays a completed month with `loadShiftPct`, `rooftopSolarKw` and `installedCostInr`; returns both quotes, annual savings and payback |
| `GET, POST /ops/tariffs`, `PUT /ops/tariffs/{id}`, `PATCH /ops/tariffs/{id}/activate` | Billing, admin | Draft, edit and activate effective-dated versions, including slabs, GST, solar policy and export rate, grace days and late-fee rate and cap. Active versions cannot be edited. A meter's own `tariff_plan_id` overrides the plan for its connection type |

## Billing

| Method and path | Who | Result |
| --- | --- | --- |
| `GET /bills?status&meterId&year` | Customer | `status` accepts a bill status, `OPEN` (anything unpaid) or `SETTLED` |
| `GET /bills/summary`, `GET /bills/{id}`, `GET /bills/{id}/pdf` | Customer | Receivables, itemized detail with notes, and the PDF invoice |
| `GET /ops/bills?accountId&status&year&month&q`, `GET /ops/bills/{id}`, `GET /ops/bills/{id}/pdf` | Billing, support, admin | Bill explorer |
| `POST /ops/bills/generate` | Billing, admin | `{meterId, year, month}` for a completed month |
| `POST /ops/bills/runs`, `GET /ops/bills/runs` | Billing, admin | `{year, month, dryRun}`. Real runs need an `Idempotency-Key` UUID header; repeating it returns the same run |
| `POST /ops/bills/{id}/adjustments` | Billing, admin | `{noteType: CREDIT\|DEBIT, amount, reason}` |

## Payments

| Method and path | Who | Result |
| --- | --- | --- |
| `POST /payments/checkout` | Customer | `{billIds, paymentMethod: UPI\|CREDIT_CARD\|DEBIT_CARD\|NET_BANKING}` (`AUTO_PAY` is reserved for mandate charges) with an `Idempotency-Key` UUID header. Returns 201, or 200 for a replay; 409 if a bill already has an open checkout; 422 if the key was used for a different request |
| `POST /payments/sandbox/{id}/confirm` | Customer | `{outcome: SUCCEEDED\|FAILED}`. The sandbox signs a gateway event and posts it to the webhook handler |
| `POST /webhooks/payments/{provider}` | Gateway | Requires `X-GridIQ-Signature: t=<unix>,v1=<hex HMAC-SHA256 of "t.body">` within five minutes |
| `GET /payments`, `GET /payments/{id}`, `GET /payments/{id}/receipt/pdf` | Customer | History, allocations and receipts |
| `GET /ops/payments?status&accountId` | Billing, admin | Payment ledger |
| `POST /ops/payments/auto-pay/run` | Billing, admin | Runs the auto-pay sweep now and returns `{settledBills}`. Each bill's idempotency key is derived from its ID, so it can never be charged twice |

## Support, outages and notifications

| Method and path | Who | Result |
| --- | --- | --- |
| `POST, GET /complaints`, `GET /complaints/{id}`, `POST /complaints/{id}/comments`, `POST /complaints/{id}/csat` | Customer | Complaints linked to an own meter or bill. Replying reopens a resolved ticket; rating closes it |
| `GET /ops/complaints?status&priority&slaBreached&assignedTo&accountId&q`, `GET /ops/complaints/{id}` | Staff | Queue ordered by priority and SLA deadline |
| `PATCH /ops/complaints/{id}`, `POST /ops/complaints/{id}/comments` | Support, field, admin | Assign, change status or priority, resolve with a summary, and reply or add internal notes. Requires `version` |
| `GET /outages/active` | Signed in | Customers see incidents for their pincodes; staff see all |
| `GET, POST /ops/outages`, `PATCH /ops/outages/{id}` | Support, field, admin | Declare and update incidents; affected customers are notified |
| `GET /notifications`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all` | Signed in | Inbox and unread count |
| `GET /notifications/stream` | Signed in | Server-Sent Events: `ready` with the unread count, then `notification` events |

## Analytics and AI

| Method and path | Who | Result |
| --- | --- | --- |
| `GET /analytics/consumer/overview?meterId` | Customer | Cycle usage, slab position, 12-month history, hourly profile, time-of-day split, receivables, outages and `peers`: the last 30 days against the median and quartiles of meters with the same connection type and load band (null when fewer than four peers) |
| `GET /ai/forecast/{meterId}` | Customer | P10/P50/P90 energy and bill for the cycle, net days to the next slab after any solar credit, model name and `source` (`ml` or `fallback`). 30 requests a minute per user |
| `POST /ai/copilot/chat` | Customer | `{message, meterId?, history?}`. 20 requests a minute per user, shared with triage; over the limit returns 429 with `Retry-After` |
| `GET /ops/analytics/executive` | Staff | Revenue, collection efficiency, aging, slab mix, anomalies, SLA statistics (compliance is tickets resolved within SLA in the last 90 days over those resolved plus open tickets already breached) and `regions` (metered vs billed kWh as a loss proxy, collection efficiency, overdue amount and open anomalies per region for the last three billed months) |
| `POST /ops/ai/complaints/{id}/triage` | Support, field, admin | Summary, likely cause, evidence, next steps, a suggested priority and reply, and `providerUsed` |
| `GET /ops/audit?q` | Super admin | Audit trail filtered by action, entity or correlation ID. Entries include `actorName`, `actorEmail` and the recorded `afterState` |

## Internal ML service

Reachable only on the Compose network with `X-Internal-Api-Key`: `POST /ml/v1/forecast`, `POST /ml/v1/anomalies/detect`,
`POST /ml/v1/copilot/respond` and `POST /ml/v1/copilot/triage`, plus `GET /health` and `GET /metrics`.

# Security

This page lists the controls GridIQ implements and how each one is verified. Report vulnerabilities privately to the
repository owner rather than in a public issue.

## Controls

| Risk (OWASP 2021) | Control | Verified by |
| --- | --- | --- |
| A01 Broken access control | Default-deny route rules per role, `@PreAuthorize` on staff operations, and ownership checks on every customer resource (bills, meters, payments, complaints) that return 404 for other customers' IDs | Integration tests for cross-customer access, 403 for wrong roles, and the Playwright staff journeys |
| A02 Cryptographic failures | Argon2id password hashing, HS256 JWTs from a 256-bit secret, SHA-256 digests for stored refresh, reset and verification tokens, HMAC-SHA256 webhooks with constant-time comparison | Tests assert `$argon2id$` hashes and that no password or token appears in responses |
| A03 Injection | Parameterized SQL only, Bean Validation on every request record, DTD and external entities disabled for Green Button XML, Angular escaping, and Copilot markdown escaped before rendering | XXE rejection test and a frontend test for HTML escaping |
| A04 Insecure design | Server-side prices and late fees, `Idempotency-Key` on payments and billing runs, row locks, optimistic versions on accounts and complaints, state machines for bills and complaints | Concurrent confirmation, idempotent replay, stale-version 409 and atomic multi-bill rollback tests |
| A05 Misconfiguration | CSP with `style-src 'self'`, `X-Frame-Options: DENY`, `nosniff`, a strict referrer policy, CORS limited to `CORS_ALLOWED_ORIGINS`, management and API docs blocked at the edge, and generated service credentials | Playwright header and edge tests |
| A06 Vulnerable components | Pinned versions and image digests, weekly Dependabot updates, OS security updates applied when images are built, and CI checks with `npm audit --audit-level=high`, `pip-audit`, and Trivy configuration and image scans. Accepted image exceptions live in `.trivyignore.yaml`, each with its reason and an expiry date. | Latest local run: no high or critical npm findings, no known Python vulnerabilities, and no fixable high or critical CVEs in the three images beyond six nginx CVEs for features GridIQ does not enable |
| A07 Authentication failures | Generic login errors, a dummy hash for unknown emails, lockout after five failures, Redis rate limits on login, registration, refresh and reset, 15-minute access tokens, refresh rotation with family revocation on reuse, and session-version checks after password or role changes | Tests for throttling (429 with `Retry-After`), reuse revocation, disabled staff and password reset |
| A08 Integrity failures | Immutable issued invoices, append-only line items, notes, ledger entries and audit logs enforced by triggers, and journals that must balance at commit | Test that updating an issued bill fails and that every journal balances |
| A09 Logging and monitoring | Structured ECS JSON logs with a correlation ID across Nginx, Core API and ML service, append-only audit entries for sensitive actions, Micrometer business metrics, and the optional Prometheus and Grafana profile | Audit trail test and correlated problem responses |
| A10 SSRF | The Core API calls only the configured ML service URL. The ML service calls only the configured LLM provider. | Configuration review |

## Secrets

All secrets come from the environment or from files Compose generates on first start. `.env` is ignored by Git, and
`.env.example` contains no values. Outside the development profile the Core API refuses to start without
`JWT_SECRET` and `PAYMENT_WEBHOOK_SECRET`. Demo accounts can sign in only when the development profile and
`DEMO_MODE=true` are both active, and their password is chosen locally.

## Production notes

- Terminate TLS in front of Nginx, set `APP_PROFILE=prod` and provide explicit secrets for JWT signing, payment
  webhooks and the internal ML key.
- Keep PostgreSQL, Redis and the ML service on private networks, as the Compose file does.
- Replace the sandbox gateway with a provider adapter that posts to `/api/v1/webhooks/payments/{provider}`.

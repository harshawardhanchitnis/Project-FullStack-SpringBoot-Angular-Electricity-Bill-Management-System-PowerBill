# Contributing to PowerBill GridIQ

Thank you for helping improve GridIQ. This guide covers the local setup, the rules every change must keep, and the
checks a pull request must pass. Report security issues privately as described in [SECURITY.md](docs/SECURITY.md),
not in a public issue.

## Local setup

You need Docker, Java 21, Node 22 and Python 3.12 or later.

1. Copy `.env.example` to `.env`, set `DEMO_MODE=true` and choose a `DEMO_PASSWORD` of 12 or more characters.
   `.env` is ignored by Git; never commit it or paste its values into issues.
2. Start the stack with `docker compose up --build --wait` and open http://127.0.0.1:8080. Mailpit at
   http://127.0.0.1:8025 shows every email the platform sends.
3. For a fast frontend loop, run `npm start` in `frontend/`; it proxies `/api` to the stack on port 8080.

The [README](README.md) explains the optional observability and local LLM profiles.

## Rules every change keeps

- **Module boundaries.** Each backend module exposes only the types at its package root; everything in `internal/`
  is private. Cross-module side effects go through domain events and `@ApplicationModuleListener`.
  `ModularityTests` fails the build when a boundary is crossed.
- **Money and authorization stay on the server.** Prices, late fees, payment totals and ownership checks are
  computed by the Core API. Never trust an amount or an account ID sent by the browser.
- **Financial records are immutable.** Correct an issued invoice with a credit or debit note. Database triggers
  reject updates to issued bills, ledger entries and audit logs.
- **Migrations are append-only.** Never edit a merged Flyway migration; add the next `V<n>__description.sql`.
- **No secrets in source.** Read secrets from the environment and document new ones in `.env.example` with an
  empty value.
- **The strict Content-Security-Policy stays strict.** Do not add inline styles or scripts, `unsafe-inline`, or
  libraries that inject them. Chart tooltips render as canvas rich text for this reason.
- **No mock data in production code.** The UI shows only what the API returns. Demo data comes from the dev
  seeders and the AMI simulator, which run only in the dev profile with demo mode on.
- **Accessibility.** Use semantic elements, keep visible focus, label every control, and check layouts at 375,
  768 and 1440 pixels wide.

## Checks before opening a pull request

| Component | Run from | Commands |
| --- | --- | --- |
| Core API | `backend/` | `./mvnw verify` (`mvnw.cmd verify` on Windows), which runs Testcontainers suites, the tariff tests, the module check and JaCoCo coverage |
| ML service | `ml-service/` in a virtual environment | `pip install -e '.[dev]'`, `ruff check .`, `pytest --cov=app` |
| Frontend | `frontend/` | `npm ci`, `npm run lint`, `npm test`, `npm run build -- --configuration production` |
| End to end | `frontend/`, with the demo stack running | `E2E_DEMO_PASSWORD=<your DEMO_PASSWORD> npm run e2e` |

Add or update tests with every behaviour change. Backend and ML line coverage must stay at 80% or above, and the
production frontend build must stay within its budgets.

## Pull requests

- Branch from `main` and keep each pull request to one concern.
- Write commit messages in the imperative mood, with a short subject line and a body that explains why.
- Describe what changed, how you verified it, and any follow-up work.
- CI must pass: backend, ML service, frontend, the Compose stack with Playwright, the Trivy configuration and
  image scans, and the dependency audits.

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).

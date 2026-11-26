# PowerBill GridIQ

GridIQ is a utility customer information and billing system for the Indian electricity market. The current foundation runs an Angular frontend, a Spring Modulith Core API, and a FastAPI service on a local Compose stack backed by PostgreSQL, Redis, and Mailpit.

## Run locally

Start Docker and run the stack from the repository root.

```sh
docker compose up --build --wait
```

Open [GridIQ](http://127.0.0.1:8080/) to check the live Core API connection. The [readiness probe](http://127.0.0.1:8080/actuator/health/readiness) checks that the API can reach PostgreSQL and Redis. [Mailpit](http://127.0.0.1:8025/) is the local email inbox.

Compose generates separate local database and Redis passwords on first startup. Copy `.env.example` to `.env` before the first run to override port settings or local credentials. The database name, database username, and generated credentials persist with the Compose volumes.

Stop the stack with `docker compose down`. This retains the local volumes.

## Run checks

| Component | Commands |
| --- | --- |
| Core API, from `backend` | `./mvnw verify` or `mvnw.cmd verify` on Windows |
| ML service, from `ml-service` in a Python 3.12+ venv | `pip install -e '.[dev]'`, `ruff check .`, `pytest` |
| Frontend, from `frontend` with Node 22 | `npm ci`, `npm run lint`, `npm test`, `npm run build` |
| Compose browser smoke tests, from `frontend` | With Compose running, `npx playwright install chromium` then `npm run e2e` |

See [architecture and dependency versions](docs/ARCHITECTURE.md). The original tracked baseline is preserved at the `legacy-ilp-archive` Git tag.

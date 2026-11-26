# Local demo accounts

Set `APP_PROFILE=dev`, `DEMO_MODE=true`, and a locally chosen `DEMO_PASSWORD` of 12–128 characters in the ignored `.env` file before starting Compose. On startup, GridIQ seeds 15 demo consumers and 25 meters across residential, commercial, industrial and solar connections.

| Portal role | Login email |
| --- | --- |
| Customer | `customer01@demo.powerbill.invalid` through `customer15@demo.powerbill.invalid` |
| Billing officer | `billing@demo.powerbill.invalid` |
| Support agent | `support@demo.powerbill.invalid` |
| Field engineer | `field@demo.powerbill.invalid` |
| Super administrator | `admin@demo.powerbill.invalid` |

Each account starts with the local password you configured when the account was first seeded. Demo data persists across restarts. The reserved demo accounts can sign in only while the development profile and demo flag are enabled.

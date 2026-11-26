# GridIQ API guide

The local Core API is available at `http://127.0.0.1:8080/api/v1` through Nginx. Send request bodies as JSON. Error responses use Problem Details, with a correlation ID and field validation errors when applicable.

## Identity

| Method and path | Result |
| --- | --- |
| `POST /auth/register` | Registers a residential customer with a premise and default meter. Send email, password, fullName, mobileNumber, and premise. |
| `POST /auth/login` | Exchanges email and password for a short-lived access token and refresh cookie. |
| `POST /auth/refresh` | Rotates the refresh cookie and returns a new access token. |
| `POST /auth/logout` | Revokes the session and clears its cookie. |
| `POST /auth/verify-email` | Accepts a token from the welcome email. |
| `POST /auth/forgot-password` | Accepts email and returns HTTP 202 for known and unknown addresses. |
| `POST /auth/reset-password` | Accepts token and password. It revokes the account's existing sessions. |
| `POST /auth/change-password` | Accepts currentPassword and newPassword with bearer authentication. |
| `GET /auth/me` | Returns the authenticated profile. |
| `PATCH /auth/me/preferences` | Updates theme or budgetAlerts with bearer authentication. |

Send `Authorization: Bearer <accessToken>` on protected requests. Keep the refresh cookie in the client cookie jar. For refresh and logout, send it with `X-GridIQ-Session: 1`. The refresh cookie uses HttpOnly and SameSite=Lax, with Secure enabled outside the local development profile. Email links carry their one-time token in a URL fragment for the Angular authentication screens planned in Phase 6. During API development, take the token from the Mailpit message and submit it to the corresponding API.

The premise fields are label, addressLine, city, state, pincode, regionZone, and optional latitude and longitude. Submit Indian mobile numbers as ten digits and pincodes as six digits. Passwords for registration and password changes require 12–128 characters.

## Consumers and meters

Customers can get or update `/consumers/me`, list `/consumers/me/meters`, add `/consumers/me/premises`, and look up a meter by ID at `/meters/{meterId}`. Meter lookups use the authenticated account and return HTTP 404 for missing or inaccessible meters.

Billing officers, support agents, and super administrators can list and create accounts at `/ops/consumers`, get or update an account at `/ops/consumers/{id}`, patch its status, and add meters. Directory search accepts `q`, `type`, `status`, `page`, and `size`. The count and page use the same filters, with newest accounts first and a stable ID tie-break. Account updates and status changes require the current version. A stale version returns HTTP 409.

Super administrators can read `/ops/audit` with `page` and `size`.

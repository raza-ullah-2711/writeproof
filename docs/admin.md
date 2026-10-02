# Admin side (Task 13)

The admin side is for running Writeproof: a dashboard, managing accounts and admins, moderating
open letters, and system controls. It is built in five parts (13a–13e); this document grows with
each one.

## What admins can and can't do

- **Can see** counts and metadata the server holds anyway: accounts, when things happened, ledger
  state, handwriting accept and reject rates, rate-limit rejections, system health.
- **Can't see** sealed letter bodies, contact books or handwriting templates. They are encrypted
  with users' keys (or the handwriting data key, which only the matcher uses). That is the design,
  not a missing feature.
- **Can't rewrite** letters or the ledger. Browsers and outside witnesses would detect a rewrite
  anyway (see [ledger.md](ledger.md)).
- **Every admin action is recorded** permanently in the audit log (`admin_audit_log`, append-only).

## Roles and sign-in

Admins sign in with their wallet, like everyone else. There are no admin passwords. The role is
looked up on every request rather than stored in the 15-minute token, so a revoked role stops
working immediately.

| Role      | Can                                                                    |
| --------- | ---------------------------------------------------------------------- |
| ADMIN     | everything: dashboard, audit log, accounts, system, moderation, admins |
| MODERATOR | moderation of open letters only (13c)                                  |

**Becoming the first admin.**

1. Create a wallet in the app.
2. Copy its address from the Letters page.
3. Add it to `ADMIN_PUBLIC_KEYS` in `deploy/.env`. Use commas to separate several addresses.
4. Run `docker compose -f deploy/compose.yml up -d`.

Addresses listed there are always ADMIN while listed. Other admins and moderators are granted in
the admin area (13e) and stored in `admin_roles`.

Access rules (in `SecurityConfig`):

- `GET /api/admin/me`: any signed-in account; returns `{role}`, which is null for ordinary
  accounts.
- `/api/admin/moderation/**`: ADMIN or MODERATOR.
- every other `/api/admin/**` endpoint: ADMIN.

In the app, the **Admin** link appears only for admins and moderators, and `/admin` is guarded.
The server still checks every request.

## Dashboard (13a)

`GET /api/admin/dashboard`, shown at `/admin`:

- **Accounts:**
  - total, plus new this week and in the last 30 days;
  - handwriting enrolled;
  - can receive letters;
  - wallet backed up;
  - using contacts;
  - calibration contributors.
- **Letters:**
  - sealed letters (today and this week), replies, open letters;
  - a 30-day chart of sealed and open letters per day, with hover detail and a table view.
- **Ledger:** entries, the last published checkpoint, and entries not yet in a published
  checkpoint (highlighted).
- **Handwriting verification since server start:** signatures on letters and practice checks,
  with the share rejected, and the match threshold.
- **Rate limits since server start:** rejected requests per rule.
- **System:** version, uptime, database size.

The handwriting and rate-limit numbers are in-memory counters (Micrometer). They reset when the
server restarts, and the dashboard says so.

## Audit log (13a)

`GET /api/admin/audit?before&limit`, shown at `/admin/audit`, newest first. Each entry records
when, the actor's address and role, the action, the target and details. The table can't be
updated, deleted or truncated (database triggers). The actions that write to it arrive with
13b–13e.

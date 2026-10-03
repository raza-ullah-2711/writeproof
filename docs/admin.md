# Admin side (Task 13)

The admin side is for running Writeproof: a dashboard, managing accounts and admins, moderating
open letters, and system controls. It was built in five parts (13a–13e).

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

Addresses listed there are always ADMIN while listed. Other admins and moderators are granted on
the Admins page (13e) and stored in `admin_roles`.

## A separate app on its own host

The admin side is its own app (`frontend/projects/admin`) on its own origin, `ADMIN_DOMAIN`
(`admin.<your domain>` by default). The public app contains no admin code and no admin link.
Keeping them apart means a bug in a public page (say, an XSS in a shared open letter) runs on the
public origin, away from an admin's session, and can't drive the admin API.

**Signing in.** Browsers keep a wallet per origin, so the admin site has its own copy:

1. In the app, open the Wallet page and create a recovery code for your admin wallet.
2. Open `https://admin.<your domain>` and enter the code. The wallet is restored on this origin,
   and you sign in with it from then on.

Only admins and moderators can sign in there; other wallets get "not an admin or moderator".

**Two kinds of session.** The proxy marks requests that arrive on the admin host
(`X-Writeproof-Surface: admin`, set by Caddy on the admin host and stripped on the public one).
The server stamps each token with the app it was issued for (`aud`: `writeproof-app` or
`writeproof-admin`, see `Surface`):

- An admin-app token is issued only to admins and moderators, carries their role, and works only
  on `/api/admin/**`. It can't send letters or act as the user.
- A public-app token never carries a role, so it opens nothing under `/api/admin/**`, even for an
  admin.

**Network rules** (`frontend/Caddyfile`):

- The public host answers `404` for `/api/admin/**`.
- The admin host routes only `/api/admin/**`, sign-in (`/api/auth/challenge`, `/api/auth/verify`),
  wallet restore (`GET /api/backups/*`) and `/api/system/status`. Every other API path is `404`.
- Optionally, `ADMIN_ALLOWED_IPS` (space-separated addresses or CIDR ranges) limits who can reach
  the admin host at all; everyone else gets `403`. This is the strongest setting: a stolen wallet
  then still needs one of those networks. See [deployment.md](deployment.md).

Access rules (in `SecurityConfig`):

- `GET /api/admin/me`: any admin-app session; returns `{role}`.
- `/api/admin/moderation/**`: ADMIN or MODERATOR.
- every other `/api/admin/**` endpoint: ADMIN.
- everything else that needs a login: public-app sessions only.

In the admin app, `/admin` is guarded and unauthenticated visits go to `/sign-in`. The server
still checks every request.

**Locally**, `npm run start:admin` (in `frontend/`) serves the admin app on `:4300`; its dev proxy
adds the admin header the way Caddy does. The public app stays on `:4200`, a different origin.

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
updated, deleted or truncated (database triggers).

## Accounts (13b)

`/admin/accounts` lists the newest accounts. You can also find one by the first characters of its
address (at least 4). Each account shows its join date, whether handwriting is enrolled, letters
sent and received, open letters, and its status.

The detail page also shows whether it can receive letters, its wallet backup, its last letter,
whether it uses contacts and calibration, and its last forced sign-out. Below that is the
account's admin history: every audit entry targeting it.

| Action              | Effect                                                                                                                                                          | Audit action                |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- |
| Suspend (reason)    | Can't send letters, reply or publish open letters (403 with the reason). Can still sign in, read and back up. The account holder sees a banner with the reason. | `account.suspended`         |
| Reinstate           | Can send again                                                                                                                                                  | `account.reinstated`        |
| Sign out everywhere | Every token issued up to now is rejected (401). The app returns to the sign-in. The account holder can sign in again with their wallet.                         | `account.signed-out`        |
| Clear rate limits   | Drops the account's per-account rate-limit buckets. Per-address limits (sign-up, sign-in, backups) are untouched.                                               | `account.rate-limits-reset` |

**Guard rails**

- Admins and moderators can't be suspended; remove their role first.
- A reason (1–500 characters) is required to suspend.
- Suspending twice, or reinstating an account that isn't suspended, is refused (409).

Suspension and sign-out live in `account_status` (migration V14), apart from `accounts`. Both are
checked on every request: `AccountAuthenticationConverter` rejects revoked tokens, and
`AccountStatus.requireCanSend` guards sending.

API (ADMIN only):

- `GET /api/admin/accounts?query&limit`
- `GET /api/admin/accounts/{id}`
- `POST` and `DELETE /api/admin/accounts/{id}/suspension`
- `POST /api/admin/accounts/{id}/sign-out`
- `POST /api/admin/accounts/{id}/rate-limits/reset`

Users can see their own status at `GET /api/me/status`.

## Moderation (13c)

Open letters are public, so moderation reads them. Sealed letters can't be moderated: nobody but
their two parties can read them.

**Reporting.** Any reader can report an open letter from its page, signed in or not. A report
has a category (spam, harassment, illegal content, impersonation, something else) and an optional
note of up to 500 characters.

- Reports are limited to 20 per hour per IP.
- A signed-in reader can report each letter once.
- A removed letter can't be reported (410).

**The queue.** `/admin/moderation` is open to moderators and admins. It lists letters with open
reports, most reported first. Each entry shows the letter's text, the counts per category, and
each report with its note and whether it came from a signed-in reader. Any letter can also be
looked up by pasting its link, to act on letters nobody reported.

| Decision | Effect                                                                                                                                                                             | Audit action           |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| Dismiss  | The letter stays up; its open reports are resolved as dismissed                                                                                                                    | `moderation.dismissed` |
| Remove   | The text is deleted permanently, and open reports are resolved as removed. The link shows "Removed by Writeproof" with the date and category, plus the ledger check of its record. | `moderation.removed`   |

The audit entry records the decision, the category, the number of reports and an optional note.
It **never records the removed text**. It also records whether a moderator or an admin decided.

**How a removal works.** `open_letter_removals` (migration V15, append-only) holds the hash, the
category, when, and who.

The `open_letters` trigger was replaced by `writeproof_open_letter_takedown_only()`. It allows
exactly one change: setting `body` to NULL, for a letter with a removal record, with every other
column unchanged. Deletes are still refused.

The ledger is untouched. The letter's hash stays there, so a removal can't be used to rewrite
history, and anyone can still prove the record existed.

The dashboard shows open reports (highlighted when there are any), how many letters they concern,
and the number of removals.

API (MODERATOR or ADMIN):

- `GET /api/admin/moderation/queue`
- `GET /api/admin/moderation/letters/{hash}`
- `POST /api/admin/moderation/letters/{hash}/dismiss` with `{note?}`
- `POST /api/admin/moderation/letters/{hash}/remove` with `{category, note?}`

Readers report with `POST /api/open-letters/{hash}/reports`, which is public.

## System controls (13d)

`/admin/system` (ADMIN only):

| Control                    | When off                                                                    | Enforced in                 |
| -------------------------- | --------------------------------------------------------------------------- | --------------------------- |
| New accounts               | `POST /api/accounts` → 403. Existing accounts sign in and restore as usual. | `AccountService.register`   |
| Sending letters            | Sending and replying → 503. Everyone can still read.                        | `LetterService.send`        |
| Publishing open letters    | Publishing → 503. Existing open letters stay readable and reportable.       | `OpenLetterService.publish` |
| Announcement (≤ 280 chars) | (empty: none) A banner at the top of every page, for everyone.              | shown by the app            |

- Switches live in `system_settings` (migration V16) and are read on every check, so a change takes
  effect at once.
- Pausing asks for confirmation and says what will stop.
- Every change is audited as `system.setting-changed` with `{from, to}`.
- The app reads `GET /api/system/status` (public) to show the announcement and explain a paused
  feature before anyone tries. The server enforces the switches regardless.

**Ledger tools:**

- **Publish a checkpoint now:** publishes immediately if the ledger grew, rather than waiting for
  the interval. Audited as `ledger.checkpoint-published`.
- **Run a ledger audit:** walks the hash chain from genesis. It then checks the latest 200 published
  checkpoints, each of which must be signed by the ledger key and still match the Merkle root of the
  ledger as it is now. A mismatch means history was rewritten after that checkpoint was published.
  Audited as `ledger.audited` with the result. This complements outside witnesses (`AuditLedger`),
  which don't trust the server at all.

**Read-only** (set in configuration, shown here):

- the handwriting match threshold, and whether scores are exposed;
- calibration counts;
- every rate-limit rule, at its effective capacity.

API (ADMIN only):

- `GET /api/admin/system`
- `PATCH /api/admin/system/settings` with `{registrationOpen?, sendingEnabled?, openLettersEnabled?, announcement?}`
- `POST /api/admin/system/ledger/checkpoint`
- `POST /api/admin/system/ledger/audit`

The admin area is lazy-loaded, so ordinary visitors never download it.

## Admin management (13e)

`/admin/admins` (ADMIN only) lists everyone with a role. For each: their address (linked to their
account, or marked "no account yet"), their role, and where the role came from, either "set in
configuration" or granted on a date by an admin.

- **Give a role:** enter a wallet address and choose Moderator or Admin. A role can be given before
  the account exists; it applies once they sign up.
- **Change** between Moderator and Admin, or **Remove**. Both ask for confirmation.
- **Effect is immediate:** roles are looked up on every request.

**Guard rails** (enforced by the server, reflected in the page):

- **Configured admins are read-only here:** admins in `ADMIN_PUBLIC_KEYS` are listed but can't be
  changed on this page (409). Change them in the server configuration.
- **Nobody can change or remove their own role** (409), so an admin can't lock themselves out, and
  at least one admin always remains.
- **No roles for suspended accounts:** reinstate the account first (409). Likewise, an admin must
  lose their role before they can be suspended (13b).

| Change            | Audit action         | Details                         |
| ----------------- | -------------------- | ------------------------------- |
| Give a role       | `admin.role-granted` | role, whether an account exists |
| Moderator ↔ Admin | `admin.role-changed` | role, previous role             |
| Remove            | `admin.role-revoked` | the role removed                |

Giving someone the role they already have is not audited.

API (ADMIN only):

- `GET /api/admin/admins`
- `PUT /api/admin/admins/{address}` with `{role}`
- `DELETE /api/admin/admins/{address}`

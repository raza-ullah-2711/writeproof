# Launch policies (Task 7)

Two policies Writeproof needs before launch: **takedowns** and **account deletion**. Decided so
far (October 2026): the operator and most users are in the **US**, with a GDPR-style baseline in
case EU users join, and deletion means **"close and forget"**. Everything else below is a
recommendation waiting for a decision. Each one is marked **Decide:**.

> **This is not legal advice.** It is an engineering draft that lays out what the system can and
> can't do, so a US lawyer can turn it into a Terms of Service, a Privacy Policy and an internal
> moderation procedure. Every legal point is marked *confirm* for them.

## What the system can and can't do

The policies have to fit the design ([architecture.md](architecture.md), "Rules"). The relevant
facts:

| Data | Stored as | Moderators can read? | Can be deleted? |
| --- | --- | --- | --- |
| Sealed letter bodies | ciphertext only, readable by the two parties' keys | **no** | no: letters are immutable (append-only table) |
| Letter metadata (who wrote to whom, when) | plaintext, pseudonymous: wallet keys, no names | yes | no (same row as the letter) |
| Open letter bodies | plaintext, public to anyone with the link | yes | yes: a takedown blanks the text, the record stays |
| Ledger | hashes only; checkpoints anchored publicly in Rekor | – | no, but it identifies nobody |
| Handwriting (enrolment, history) | encrypted at rest | no | yes: the user can already delete it |
| Calibration samples (opt-in) | encrypted at rest, pseudonymous | no | yes: the user can already withdraw |
| Wallet backup, contact book | ciphertext only the user can open | no | yes, but no endpoint for it yet |
| Account | wallet public key, creation time | yes | the row is referenced by letters |
| IP addresses | **not stored**: no access log, rate limits in memory | – | – |

Two consequences shape everything:

- **Moderation covers open letters only.** Sealed letters are end-to-end encrypted; abuse there is
  handled through the recipient (see T5).
- **"Deleting" a sealed letter is impossible by design, but the content can be made unreadable
  forever.** It's encrypted to keys only the parties hold, so destroying a user's keys and backup
  destroys their ability, and anyone's, to open their copy. The other party keeps theirs.

## 1. Takedown policy

What exists today ([admin.md](admin.md), "Moderation"):

- Anyone can report an open letter (spam, harassment, illegal content, impersonation, something
  else). Moderators work a queue, and every decision is audited without the removed text.
- **Remove** blanks the text permanently. The public link then says "Removed by Writeproof"
  with the date and category, and the author sees the same in their list.
- Admins can suspend an account (no sending) and sign it out everywhere.

### Legal frame (US; confirm each)

- **Section 230** (47 U.S.C. § 230) generally shields platforms from liability for users'
  content and protects good-faith moderation. It doesn't cover federal criminal law, intellectual
  property, or sex-trafficking content (FOSTA-SESTA).
- **Copyright: DMCA § 512 safe harbor.** It needs a designated agent registered with the US
  Copyright Office (renewed every 3 years), a notice-and-takedown process, counter-notices, and a
  repeat-infringer policy. *None of this exists yet.*
- **Child sexual abuse material and exploitation: 18 U.S.C. § 2258A.** On actual knowledge,
  report to NCMEC's CyberTipline, and **preserve** the content and related data for **1 year**
  (the REPORT Act, 2024, raised this from 90 days). Today's "Remove" deletes the text at once.
  *That conflicts with preservation and must change before launch* (see T1).
- **TAKE IT DOWN Act** (FTC enforcement since May 19, 2026): remove non-consensual intimate
  *images* within 48 hours of a valid request. Writeproof has no image uploads, so exposure is
  likely low. *Confirm whether it applies*, and accept such requests anyway.
- **Stored Communications Act**: government requests need a subpoena for basic subscriber data, a
  court order for other records, and a warrant for content (see T7).
- **GDPR-style baseline** (if EU users): transparent reasons for removals, an appeal route, and a
  contact point. The steps below already cover these.

### Decisions

**T1. What gets removed, and what is preserved first.**
Recommended grounds: content that is illegal where you operate; child sexual abuse or
exploitation; harassment or threats; impersonation; spam; copyright infringement (through
DMCA notices).
**Decide:** the list, and whether "something else" can lead to removal.
*Needs code:* for the child-abuse category, the removal must first copy the text and its
metadata to a restricted, encrypted preservation store kept for 1 year, then blank the public
text. NCMEC reporting is a manual step in the procedure.

**T2. How people can ask.** Recommended: the report button for users. Add a legal-notices
email address (copyright notices, legal requests, image requests under the TAKE IT DOWN Act) and
a registered DMCA agent. **Decide:** the address and who reads it. *Needs code:* add "copyright"
as a report category, or route it to the email.

**T3. Response times.** Recommended:
- child abuse: immediately, and reported the same day;
- valid legal notices and intimate-image requests: within 48 hours;
- other reports: within 3 days.

**Decide:** the targets, and who is on call.

**T4. Telling the author, and appeals.** The author already sees the removal and its category.
Recommended: an appeal route (an email, or a sealed letter to an official account), answered
within 7 days. **Decide:** whether removals stay permanent, or become "hidden, then deleted after
14 days unless appealed" so an appeal can restore the text. The second is fairer but *needs code*:
today the text is gone at once. Child-abuse removals are never restored.

**T5. Abuse in sealed letters.** Nobody but the recipient can read them. Recommended:
- the recipient can **block** a sender;
- the recipient can **report** a sender, choosing to disclose the decrypted letter, since the
  recipient is the only one who can;
- moderators can suspend the sender.

**Decide:** whether that is the policy. *Needs code:* blocking and recipient-disclosed reports
don't exist yet.

**T6. Repeat offenders.** Recommended: suspension after 3 upheld removals in 90 days, or at once
for child abuse, credible threats or impersonation. DMCA requires a repeat-infringer policy.
**Decide:** the thresholds.

**T7. Law-enforcement and legal requests.** What exists to hand over: account public key and
creation time, letter metadata (who, to whom, when), open letters, and moderation records.
What doesn't: sealed contents, IP addresses, and names (there are none). Recommended:
- require valid legal process;
- tell the user unless the law forbids it;
- publish a transparency report twice a year (counts of requests, removals by category, NCMEC
  reports).

**Decide:** whether to commit to the transparency report.

**T8. Minimum age.** COPPA covers under-13s. Recommended: 13+ (or 18+, which is simpler). There
is no age check: wallets have no identity. **Decide:** the age, and state it in the Terms.

## 2. Account deletion: "close and forget" (decided)

**What happens** when a user deletes their account:

| Data | On deletion |
| --- | --- |
| Handwriting enrolment and history | deleted (the user can already do this) |
| Calibration samples and consent | deleted (the user can already do this) |
| Wallet backup blob | deleted, so the recovery code no longer works |
| Contact book | deleted |
| Registered encryption key | deleted, so nobody can send them letters |
| Sessions | revoked at once; the account can't sign in again |
| Their open letters | text withdrawn (blanked), shown as "Withdrawn by its author"; the record and ledger hash stay |
| Sealed letters, sent and received | rows kept: the other party's copy is in the same row, and letters are immutable. The deleted user's side becomes unreadable for good once their wallet and backup are gone |
| Letter metadata | kept as part of the letter, pseudonymous (a public key, no name). The Privacy Policy must say so |
| Reports they filed | reporter link removed; the report stays for the moderation record |
| Ledger and Rekor | unchanged: hashes and checkpoint roots identify no one |
| Admin audit log | kept: a legitimate interest and possibly a legal requirement; *confirm retention* |

### Smaller decisions

**D1. Proof of ownership.** Recommended: a deletion request signed by the wallet, the root of
trust, with a typed confirmation. Someone who lost both their wallet and their recovery code
can't prove ownership, and support can't either, since there is no other identity. The Terms
must say so. **Decide.**

**D2. Undo window.** Recommended: none. Deletion is immediate and final, after an explicit
warning. A grace period would mean keeping the backup and keys, which defeats "forget".
**Decide.**

**D3. What correspondents see.** Recommended: past letters from a deleted account show "This
account was deleted", and new letters to it are refused. **Decide.**

**D4. The same wallet coming back.** Recommended: a deleted wallet key can't register again, so
it can't reappear as if it were the same person with no history. Use a new wallet. **Decide.**

**D5. Data retention elsewhere.** Database backups (`deploy/backup.sh`) still contain deleted
data until they age out. Recommended: keep backups at most 30 days, and say so in the Privacy
Policy. **Decide** the retention period.

*Needs code:* a signed `DELETE /api/me` endpoint and the steps above. That means
an "author-withdrawn" kind of open-letter removal, a deleted status that blocks sign-in,
registration and new letters, the correspondent-facing label, and a Wallet-page button with a
clear warning.

## What to do next

1. Take this document to a US lawyer. Ask for Terms of Service, a Privacy Policy and a written
   moderation procedure, and have them confirm every *confirm* above.
2. Register a DMCA agent, set up a legal-notices address, and sign up as an NCMEC CyberTipline
   reporter.
3. Make the decisions marked **Decide:**.
4. Build the code they need. Two pieces should land before launch:
   - **Task 7a, account deletion:** "close and forget" as above.
   - **Task 7b, moderation compliance:** preservation before removing child-abuse content, a
     copyright route, and appeals with hide-then-delete. Blocking and recipient-disclosed
     reports for sealed letters can follow.

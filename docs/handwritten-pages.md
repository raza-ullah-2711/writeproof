# Handwritten pages (Task 14)

**Status: 14a and 14b built** (open and sealed letters with handwritten pages). 14c is next.

Today a letter is typed, and only its signature is written by hand; the signature strokes are
never published. This task lets people **write the letter itself by hand** and share it: stories,
letters to family, calligraphy. The ink is the content, and its strokes prove a person wrote it
live. That proof is "something a robot can't do".

## Decisions

| Question                                | Decision                                                                                                                          |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Can readers watch a page being written? | **The author opts in per letter.** Otherwise readers see the finished ink only, and the stroke timing is never published or kept. |
| Typed text                              | **Optional transcript.** It's signed with the pages; moderators see both.                                                         |
| First PR                                | **Open letters (14a).** Sealed letters reuse the page format in 14b.                                                              |
| Pages per letter                        | **Up to 4.**                                                                                                                      |
| Later, separate decisions               | Family archive: photos of old paper letters, labelled as archived copies (14c).                                                   |

## What changes, and what doesn't

- **Unchanged:**
  - The wallet signs every letter.
  - Writing your **signature** at the end still unlocks sending, and is still verified against
    your enrolment (match, liveness, freshness, no replay).
  - Signature strokes stay private, exactly as today.
- **New:** a letter may carry up to 4 **pages** of handwriting. On an open letter, a page's ink
  is public. Its timing is public only if the author opts in to replay.
- **Rules in architecture.md still hold:**
  - Rule 3: pages are captured as stroke dynamics, not images.
  - Rule 5: pages are immutable like the rest of the letter.
  - Rule 6: a sealed letter's pages travel inside its ciphertext (14b).
- **Wording to update in open-letters.md:** "the handwritten strokes are not public" becomes
  "the signature strokes are not public; page ink is".

## The page format (`writeproof.page` v1)

A page is a portrait sheet, 1000 × 1414 units (the A-series ratio). Its pen is part of the page,
so readers see it as the author chose:

```jsonc
{
  "format": "writeproof.page",
  "version": 1,
  "capturedAt": "2026-10-13T09:12:44.120Z",  // first pen-down
  "device": "pen",                            // "pen" | "touch" | "mouse"
  "pen": { "nib": "fountain", "ink": "ink" }, // nib: fountain | brush | pencil | ballpoint
                                               // ink: ink | blue-black | sepia | seal
  "ink":    [[x0, y0, p0, dx1, dy1, dp1, ...], ...], // per stroke, delta-encoded integers
  "timing": [[t0, dt1, dt2, ...], ...]               // per stroke, ms; omitted when not published
}
```

- **Integers only:**
  - `x` and `y` in tenths of a unit;
  - pressure in 0–255;
  - time in whole milliseconds since the page's first pen-down.

  The first point of each stroke is absolute and the rest are deltas. This keeps a busy page
  around 150–300 KB of JSON, instead of several MB with the signature format.

- **Ink colours are named, not hex.** They map to theme tokens, so a page stays readable in dark
  mode.
- **Limits:**
  - 25,000 points and 500 strokes per page (about 300–400 KB of JSON at most);
  - 4 pages per letter;
  - transcript 10,000 characters.

  A full letter stays within the 4 MiB request cap.

### Two hashes per page, so timing can stay private

```
strokes(s) = each stroke's integers joined by ",", the strokes joined by ";"
inkHash    = SHA-256("writeproof/page-ink/v1\n" device "\n" nib "\n" ink "\n" strokes(ink))
timingHash = SHA-256("writeproof/page-timing/v1\n" capturedAt "\n" strokes(timing))
pageHash   = SHA-256("writeproof/page/v1\n" b64url(inkHash) "\n" b64url(timingHash))
```

The format and version are fixed by the domain strings. Integers make the bytes identical in every
language: `HandwrittenPage` (Java) and `page-format.ts` share a test vector.

- The letter commits to every `pageHash`.
- **A reader always gets the ink plus `timingHash`.** That lets them recompute `pageHash` and
  check it against the signed letter.
- **With replay on, they also get the timing**, and check it against `timingHash` too.
- With replay off, `timingHash` reveals nothing. The timing is full of high-entropy
  measurements, so it can't be guessed back from the hash.

## Open letter v2

```
pagesHash  = SHA-256("writeproof/open-letter-pages/v1\n" replay "\n" b64url(pageHash_1) "\n" ...)
letterHash = SHA-256("writeproof/open-letter/v2\n" author "\n" sentAt "\n" handwritingHash "\n"
                     b64url(bodyHash) "\n" b64url(pagesHash))
```

- `body` becomes the transcript. It's optional when there are pages (an empty body hashes as
  usual), and required when there are none.
- `replay` (`0` or `1`) is signed. A server can't publish timing the author kept private, or hide
  timing the author shared, without breaking the hash.
- **v1 letters are unchanged** and keep verifying with the v1 format. The version is part of the
  hashed domain, so a v1 letter's hash can never equal a v2 one's.

## Proving a person wrote it

- **On publish, the server checks each page:**
  - the format and limits;
  - the same liveness heuristics as signatures: timing variance, no unnaturally constant
    velocity, plausible pressure for the device;
  - freshness: the first pen-down is no more than 12 hours before publishing;
  - a page can't reuse strokes already seen.
- **Then the signature** at the end is verified as today. One verified signature seals the whole
  letter.
- **The server keeps:**
  - the ink;
  - the `timingHash`;
  - the timing only if replay is on.

  With replay off, the timing is used for the checks and then discarded.

- **Honest limits:** a drawing robot (a plotter) driven by a careful program could pass these
  checks. "Written live by a hand" stays a claim Writeproof vouches for, as the "signed by hand"
  check is today. The page's integrity, by contrast, is checked by the reader.

## Writing, reading and the look

- **Write.**
  - A full-size sheet on the writing paper, with ruled guide lines that are not part of the ink.
  - Page tabs (up to 4), undo of the last stroke, and clear page.
  - A pen picker: nib and ink.
  - Transcript box (optional), and the replay checkbox (off by default).
  - Then the signature, as now.
- **Read.** `/open/:hash` renders each page on a canvas, from the ink:
  - width follows pressure and the nib model (e.g. a fountain nib is thinner on upstrokes, a
    brush swells with pressure, a pencil is grainy);
  - if replay is on, a ▶ _Watch it being written_ control redraws the page at its real speed;
  - motion is off under `prefers-reduced-motion`, which shows the finished page.
- **Accessibility.** Each page canvas has a text alternative:
  - the transcript, when given;
  - otherwise "Handwritten page 2 of 3, no transcript".

## Moderation and safety

- **Moderators see the rendered pages**, the transcript and any reports. A transcript that
  disagrees with its ink is a reportable abuse ("misleading transcript").
- **A takedown blanks the pages as well as the transcript.** This extends the single permitted
  change to `open_letters` to also allow blanking page ink. The hashes stay on the ledger.
- **A child-safety removal** preserves the pages with the text (encrypted, one year), then
  blanks them, as today.
- **No uploaded images** in this task: everything is vector strokes drawn in the app. There are
  no camera photos and no EXIF metadata to leak. Hash-matching services for known abuse images
  (e.g. PhotoDNA) work on photos, so they don't apply. Drawn abuse is handled by reports and
  moderation, like text.

## API (14a)

| Endpoint                       | Change                                                                                                   |
| ------------------------------ | -------------------------------------------------------------------------------------------------------- |
| `POST /api/me/open-letters`    | Adds `pages: Page[]` (0–4, with timing) and `replay: boolean`. `body` may be empty when there are pages. |
| `GET /api/open-letters/{hash}` | Adds `version`, `pages` (ink, `timingHash`, and timing if `replay`) and `replay`.                        |
| Admin case view                | Adds pages, rendered for moderators.                                                                     |

Storage is a new migration `V21__handwritten_pages.sql`. It adds `open_letter_pages` (letter
hash, index, ink JSON, timing hash, timing JSON nullable), append-only like `open_letters`, with
the same blanking exception for removed letters.

## Phases

1. **14a — Open letters with pages** (this design):
   - the format, hashing and test vectors shared between Java and TypeScript;
   - server checks;
   - writing UI with pens, and reader rendering with replay;
   - moderation, takedown and preservation.
2. **14b — Sealed letters with pages** (built; see below). The same pages go inside the
   letter's ciphertext. The recipient can always replay, since only they can read it.
3. **14c — Family archive** (private entries built; see [family-archive.md](family-archive.md)). Photos of old paper letters, labelled as archived copies (provably
   unchanged since added, not provably handwritten by the person named). This needs its own
   decisions: image storage, stripping metadata, scanning for known abuse images.

## Sealed letters (14b)

A sealed letter's pages are its private content, so **the server never sees them** (architecture
rule 6). That moves the "written live" check to the one place that can see the pages: the
recipient's browser.

- **Sealed inside.** The plaintext becomes `{"body", "handwriting", "pages"}`. Pages keep their
  timing, so the recipient (and the sender, re-reading) can always replay them. The body is an
  optional typed note. The envelope may hold up to 2.5 MiB of ciphertext when there are pages
  (64 KiB otherwise).
- **The v4 header** (`writeproof/letter/v4`) is v2 plus the letter answered (an empty line if
  none) and each page's ink hash, comma-separated, in order. The wallet signs it as usual.
  Shared test vector: `FormatVectorsTest.pagesHeader` and `letter-format.spec.ts`.
- **The server** knows only those ink hashes. It stores them in `letter_pages` (migration V22,
  append-only) and refuses a page that already sealed any letter, sealed or open (409). The
  hashes reveal nothing about the ink.
- **The recipient's browser checks**, shown under the letter:
  - "Exactly the pages the sender signed": each decrypted page hashes to the signed ink hash, in
    order, and there are as many pages as hashes.
  - "Written live by a hand, shortly before sending (checked by your browser)": each page was
    started no more than 12 hours before the letter's time (and not after it), and passes the
    liveness heuristics.
- **One set of heuristics.** `pages/liveness.ts` ports the server's `LivenessChecker` (with the
  feature extraction it needs) line for line. Both are pinned to the same two fixture pages and
  the same numbers (`LivenessFixturesTest`, `liveness.spec.ts`), so the browser judges a page
  exactly as the server judges an open letter's.
- **Writing** uses the same page editor as open letters (`PageComposer`), behind the same
  "Type it / Write it by hand" switch.

## Test plan (14a)

- **Shared test vectors:** Java and TypeScript compute the same `inkHash`, `timingHash`,
  `pageHash` and v2 `letterHash`.
- **Server:**
  - rejects pages over the limits, bad formats, stale pages and robotic strokes;
  - accepts real samples;
  - never stores timing when replay is off.
- **Browser verification fails if:**
  - a single ink point is changed;
  - a page is swapped or reordered;
  - timing is added when replay is off, or the replay flag is flipped.
- **Takedown** blanks the pages; the preserved copy keeps them; v1 letters still verify.
- **End to end in a real browser:** write two pages with a fountain nib, publish with replay on,
  read and replay them as a signed-out visitor, then report and hold-and-restore the letter.

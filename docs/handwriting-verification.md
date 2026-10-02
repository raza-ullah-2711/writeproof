# Handwriting verification v1 (Task 4)

Handwriting is a **biometric unlock and liveness check on top of the wallet key**.
It is never a key (architecture rule 1), and matching is fuzzy: a similarity
score against a threshold, never equality (rule 4). Verification runs **on the
server**, because a check done only in the browser could simply be skipped by a
modified client.

Code: `backend/src/main/java/com/writeproof/handwriting/`. UI: `/handwriting`.

## API (requires a wallet login token)

| Endpoint                          | Body                | Result                                                                                          |
| --------------------------------- | ------------------- | ----------------------------------------------------------------------------------------------- |
| `GET /api/handwriting/enrolment`  | —                   | `{enrolled, sampleCount, enrolledAt}`                                                           |
| `POST /api/handwriting/enrolment` | `{samples: [3..5]}` | `201`. `409` if already enrolled. `422` + `sampleIndex`, `livenessFlags` if a sample isn't live |
| `POST /api/handwriting/verify`    | `{sample}`          | `{verified, score, threshold, match, live, livenessFlags, shapeScore, durationScore}`           |

Samples use the `writeproof.handwriting` v1 format
([handwriting-capture.md](handwriting-capture.md)) and are validated on the server
(max 5,000 points). `verified = match && live`.

## Pipeline

1. **Feature extraction** (`FeatureExtractor`). Each stroke's pen-down path is
   resampled every 10 ms. This removes device sampling-rate differences but keeps
   timing: a slower hand gives more frames and smaller velocities. Position and size are
   normalized (centroid → origin, RMS radius → 1). Rotation is kept, since people sign at
   a consistent angle. Per frame: `x, y, 8·vx, 8·vy` (+ `pressure` for pens).
   Also kept: total duration, stroke durations, pen-up gaps, raw speeds.
   Capped at 600 frames.
2. **DTW** (`Dtw`). Euclidean frame distance, Sakoe-Chiba band of 15% around the
   length-scaled diagonal, normalized by `n + m`. Pressure is compared only when both
   samples are from a pen.
3. **Enrolment** (`SignatureMatcher.enrol`). 3–5 samples, each must pass liveness.
   The template's **spread** is the mean pairwise DTW distance between references
   (floor 0.05). Near-identical references (distance < 0.01) are rejected as copies.
4. **Scoring** (`SignatureMatcher.match`).
   - `ratio = DTW(query, nearest reference) / spread`. Each person is judged against
     how much their own handwriting varies.
   - `shapeScore = exp(-(max(0, ratio − 1) / 0.35)²)`
   - `durationScore` is the same Gaussian fall-off on `|ln(duration / enrolled mean)|`
     beyond `max(ln 1.35, 3σ)`, tolerance 0.2. A careful forger who gets the shape
     right is usually far too slow.
   - `score = shapeScore × durationScore`. Threshold `0.5` (`HANDWRITING_THRESHOLD`).

## Liveness heuristics (`LivenessChecker`)

| Flag                 | Rule                                                                                                        |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| `INSUFFICIENT_INPUT` | < 300 ms or < 20 points                                                                                     |
| `CONSTANT_VELOCITY`  | within-stroke speed CV < 0.30 (pooled per stroke, so a script can't hide by changing speed between strokes) |
| `NO_TIMING_VARIANCE` | raw sampling-interval CV < 0.005 over ≥ 30 intervals (a fixed-interval timer)                               |
| `CONSTANT_PRESSURE`  | pen only: pressure std < 0.01. Mice and touch report a constant 0.5, which proves nothing                   |
| `REPLAY`             | verify only: nearest-reference ratio < 0.2. Nobody writes the same thing twice exactly                      |

## Calibration (synthetic only)

`SyntheticSignatures` (tests) models writers as cursive multi-stroke curves with
their own rhythm and pressure profile. Genuine samples vary scale (±6%), rotation (±2°),
position, speed (±8%), smooth distortion, tremor, sampling jitter and pen-up gaps.
Across 200 writers at threshold 0.5:

| Sample                             | Score range | Accepted |
| ---------------------------------- | ----------- | -------- |
| Genuine, pen                       | 0.73–1.00   | 200/200  |
| Genuine, mouse                     | 0.64–1.00   | 200/200  |
| Someone else's signature           | 0.00        | 0/200    |
| Skilled tracing (2–2.5× slower)    | ≤ 0.26      | 0/200    |
| Constant-speed script, right shape | ≤ 0.02      | 0/200    |

Within-stroke speed CV: synthetic humans ≥ 0.52, constant-speed scripts ≤ 0.22.

## Known limitations / follow-ups

- **All constants are calibrated on synthetic data** (measuring them on real hands: [calibration.md](calibration.md)). Before relying on this, collect
  real enrolment/verification samples (several devices, several sessions per person,
  real forgery attempts) and measure false-accept and false-reject rates.
- **Liveness heuristics raise the bar but don't prove humanity.** A determined attacker
  can add speed variation and jitter to a script. They stop naive bots and replays.
- **Biometric data at rest** is encrypted (AES-256-GCM, `HANDWRITING_DATA_KEY`), and enrolments
  can be deleted with a fresh verified signature (Task 8, [security.md](security.md)).
- **`/verify` is rate-limited** (20/hour per account) and omits scores unless
  `HANDWRITING_EXPOSE_SCORES=true` (Task 8).
- **The result isn't bound to anything yet.** Task 5 should require a fresh, short-lived,
  server-signed verification (bound to the letter hash) before accepting a letter.
- **Re-enrolment** means deleting the enrolment first, which needs a fresh verified signature
  (Task 8). There's still no device-specific template: enrolling with a pen and verifying with a
  mouse will score lower, since dynamics differ by device.

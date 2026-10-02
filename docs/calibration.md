# Calibration on real handwriting (Task 9)

Every handwriting threshold (Task 4) was tuned on **synthetic** signatures. This is the tooling
to measure, and then tune, them on real hands. The tooling is complete. **The data still has
to come from real contributors.**

## Privacy design: practice names, never real signatures

Measuring forgeries means showing one person's handwriting, timing included, to other people so
they can imitate it. Doing that with real signatures would give away exactly what's needed to
forge someone's Writeproof account. So contributors **never give their real signature**:

- On opting in, each contributor gets a made-up **practice name** (e.g. "Nell Crane") to write
  in their own natural style, the same way each time, across visits.
- They imitate _other contributors'_ practice names (shown with replay). Those are the skilled
  forgeries.
- Samples are filed under a random **contributor id**, not the account. They are encrypted with
  `HANDWRITING_DATA_KEY` (same as enrolments) and rate-limited (60/hour each for samples and
  targets).
- **Withdrawing** (`DELETE /api/calibration`) deletes the contributor, their samples, and every
  imitation _of_ them (`ON DELETE CASCADE`).
- Nothing touches the contributor's account, enrolment or letters.

Trade-off: a practised real signature is more automatic than a newly learned name, so real-world
genuine variability may be a little lower than measured here (the measured FRR leans
pessimistic). Imitators are amateurs, not professional forgers (the measured skilled FAR leans
optimistic).

## Contributor flow (`/calibration`, linked from `/handwriting`)

| Endpoint                              | Purpose                                                           |
| ------------------------------------- | ----------------------------------------------------------------- |
| `GET /api/calibration`                | `{contributing, practiceName, genuineSamples, forgerySamples}`    |
| `POST /api/calibration/consent`       | Opt in (idempotent); assigns contributor id + practice name       |
| `POST /api/calibration/samples`       | `{kind: genuine/forgery, targetId?, sample}`                      |
| `GET /api/calibration/forgery-target` | A random other contributor with ≥ 3 samples: id, name, one sample |
| `DELETE /api/calibration`             | Withdraw and delete everything                                    |

Five practice samples per visit is plenty. **Coming back on other days matters most**, because
real verification happens days or months after enrolment.

## Operator runbook

1. **Export** (decrypts; run where the database and key live; the file is biometric data, so keep
   it on encrypted storage and delete it afterwards):

   ```bash
   java -jar writeproof-backend.jar --spring.main.web-application-type=none \
        --writeproof.calibration.export-to=/secure/dataset.json
   ```

   It writes `writeproof.calibration-dataset` v1, with pseudonymous writer ids only (no account
   ids or practice names), and exits.

2. **Evaluate** (offline, no server, no key):

   ```bash
   java -Dloader.main=com.writeproof.calibration.EvaluateCalibration \
        -cp writeproof-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher \
        /secure/dataset.json [threshold=0.5] [targetFar=0.01] [enrolSize=3]
   ```

   It prints a Markdown report and writes `dataset.json.report.json` (the full curve).

3. **Act**: set `HANDWRITING_THRESHOLD` to the recommended value once one is recommended. If
   liveness false rejects are high, revisit the `LivenessChecker` constants. If cross-device
   FRR is high, that's the case for per-device templates.

## How the evaluation works (`CalibrationEvaluator`)

The evaluator runs the production `FeatureExtractor`, `SignatureMatcher` and
`LivenessChecker`. For each writer:

- **Enrol** from the first `enrolSize` genuine samples of their earliest session (UTC day).
- **Genuine probes:** every other genuine sample. Later sessions and other devices are tracked
  separately.
- **Skilled forgeries:** every imitation targeting them.
- **Random forgeries:** one genuine sample from each other writer.

The report gives:

- FRR and FAR by threshold, both match-only and end to end (match **and** liveness).
- The equal error rate (middle of the tie band).
- Liveness false rejects by flag, FRR by device, cross-session and cross-device.
- Score deciles.

**Recommendations are confidence-bounded.** A threshold is recommended only when the 95% Wilson
**upper bound** on its end-to-end false-accept rate meets the target. Zero accepted forgeries out
of 36 still allows about 10% at 95% confidence. Showing FAR ≤ 1% with a clean run takes about
**380 forgery probes**. Until then the report says how many more are needed, rather than
offering a threshold the data can't support.

#### Synthetic baseline (12 writers, 2 sessions, pen)

|             | Threshold                  | FRR (match) | FRR (end to end) | Skilled FAR (e2e) | Random FAR (e2e) |
| ----------- | -------------------------- | ----------- | ---------------- | ----------------- | ---------------- |
| Configured  | 0.50                       | 0.0%        | 0.0%             | 0.0%              | 0.0%             |
| Recommended | none proven at FAR <= 1.0% |             |                  |                   |                  |

A threshold is recommended only when the 95% upper bound on its false-accept rate (skilled + random forgeries, end to end) meets the target.
**Collect at least 213 more forgery probes** to be able to show FAR <= 1.0%.

Equal error rate (match only): **0.0%** at threshold 0.36.

On synthetic data, separation is perfect, which is exactly why this baseline proves nothing about
real hands. The browser end-to-end run (two contributors with different styles, practice samples
and imitations, export via the jar, evaluation via the CLI) gave the same shape: 0% errors on 14
probes, and "collect at least 373 more forgery probes".

## Next steps

- Recruit contributors across pens, touch screens and mice, and over several weeks.
- Once real FRR/FAR are known: tune `HANDWRITING_THRESHOLD`, then the matcher and liveness
  constants. Consider per-device templates if cross-device FRR is high.

import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import {
  Enrolment,
  HandwritingApi,
  HandwritingRejection,
  LIVENESS_LABELS,
  describeRejection,
  LivenessFlag,
  MAX_ENROLMENT_SAMPLES,
  MIN_ENROLMENT_SAMPLES,
  NotLiveProblem,
  Verification,
} from '../handwriting/handwriting-api';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { HandwritingSample } from '../handwriting/handwriting-sample';

/** Enrol 3–5 handwriting samples, then verify new ones against them. */
@Component({
  selector: 'app-verify',
  imports: [HandwritingPad, RouterLink],
  templateUrl: './verify.html',
  styleUrl: './verify.scss',
})
export class Verify {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(HandwritingApi);

  protected readonly pad = viewChild(HandwritingPad);
  protected readonly min = MIN_ENROLMENT_SAMPLES;
  protected readonly max = MAX_ENROLMENT_SAMPLES;

  protected readonly enrolment = signal<Enrolment | null>(null);
  protected readonly collected = signal<HandwritingSample[]>([]);
  protected readonly result = signal<Verification | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        void this.run(async () => this.enrolment.set(await this.api.enrolment()));
      }
    });
  }

  protected label(flag: LivenessFlag): string {
    return LIVENESS_LABELS[flag];
  }

  protected addSample(): void {
    const sample = this.pad()?.sample();
    if (!sample || this.collected().length >= this.max) {
      return;
    }
    this.collected.update((samples) => [...samples, sample]);
    this.pad()?.clear();
  }

  protected startOver(): void {
    this.collected.set([]);
    this.error.set(null);
    this.pad()?.clear();
  }

  protected enrol(): Promise<void> {
    return this.run(async () => {
      try {
        this.enrolment.set(await this.api.enrol(this.collected()));
        this.collected.set([]);
      } catch (e) {
        if (e instanceof HttpErrorResponse && e.status === 422) {
          const problem = e.error as NotLiveProblem;
          // Drop the sample that failed so it can be rewritten; keep the rest.
          this.collected.update((s) => s.filter((_, i) => i !== problem.sampleIndex));
          throw new Error(
            `Sample ${problem.sampleIndex + 1} was rejected: ` +
              problem.livenessFlags.map((f) => LIVENESS_LABELS[f]).join(' '),
          );
        }
        throw e;
      }
    });
  }

  protected deleteEnrolment(): Promise<void> {
    const sample = this.pad()?.sample();
    if (!sample) {
      return Promise.resolve();
    }
    return this.run(async () => {
      try {
        await this.api.deleteEnrolment(sample);
        this.result.set(null);
        this.enrolment.set(await this.api.enrolment());
      } finally {
        this.pad()?.clear();
      }
    });
  }

  protected verify(): Promise<void> {
    const sample = this.pad()?.sample();
    if (!sample) {
      return Promise.resolve();
    }
    return this.run(async () => {
      this.result.set(await this.api.verify(sample));
      this.pad()?.clear();
    });
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.busy.set(false);
    }
  }
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'The server is unreachable.';
    }
    const problem = e.error as HandwritingRejection | null;
    if (e.status === 422 && Array.isArray(problem?.livenessFlags)) {
      return describeRejection(problem);
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}

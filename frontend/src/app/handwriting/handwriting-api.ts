import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { HandwritingSample } from './handwriting-sample';

export type LivenessFlag =
  | 'INSUFFICIENT_INPUT'
  | 'CONSTANT_VELOCITY'
  | 'NO_TIMING_VARIANCE'
  | 'CONSTANT_PRESSURE'
  | 'REPLAY'
  | 'STALE';

export interface Enrolment {
  enrolled: boolean;
  sampleCount: number | null;
  enrolledAt: string | null;
}

/** Scores are null unless the server is configured to expose them (off in production). */
export interface Verification {
  verified: boolean;
  score: number | null;
  threshold: number | null;
  match: boolean;
  live: boolean;
  livenessFlags: LivenessFlag[];
  shapeScore: number | null;
  durationScore: number | null;
}

/** Body of a 422 when a signature that had to verify (sending, deleting) didn't. */
export interface HandwritingRejection {
  detail: string;
  match: boolean;
  livenessFlags: LivenessFlag[];
  score?: number;
}

/** Body of a 422 when an enrolment sample fails liveness checks. */
export interface NotLiveProblem {
  detail: string;
  sampleIndex: number;
  livenessFlags: LivenessFlag[];
}

/** Turns a 422 rejection into one readable sentence (with the score only if the server sent it). */
export function describeRejection(problem: HandwritingRejection): string {
  const score = problem.score === undefined ? '' : ` (similarity ${problem.score.toFixed(2)})`;
  const reasons = (problem.livenessFlags ?? []).map((f) => LIVENESS_LABELS[f]);
  return [`${problem.detail}${score}.`, ...reasons].join(' ');
}

export const MIN_ENROLMENT_SAMPLES = 3;
export const MAX_ENROLMENT_SAMPLES = 5;

export const LIVENESS_LABELS: Record<LivenessFlag, string> = {
  INSUFFICIENT_INPUT: 'Too short to judge. Write your full signature.',
  CONSTANT_VELOCITY: 'Speed was unnaturally constant.',
  NO_TIMING_VARIANCE: 'Timing was perfectly regular, like a machine.',
  CONSTANT_PRESSURE: 'Pen pressure never changed.',
  REPLAY: 'Identical to a signature you already used. Write it fresh.',
  STALE: 'Written too long ago. Sign again.',
};

@Injectable({ providedIn: 'root' })
export class HandwritingApi {
  private readonly http = inject(HttpClient);

  enrolment(): Promise<Enrolment> {
    return firstValueFrom(this.http.get<Enrolment>('/api/handwriting/enrolment'));
  }

  enrol(samples: HandwritingSample[]): Promise<Enrolment> {
    return firstValueFrom(this.http.post<Enrolment>('/api/handwriting/enrolment', { samples }));
  }

  verify(sample: HandwritingSample): Promise<Verification> {
    return firstValueFrom(this.http.post<Verification>('/api/handwriting/verify', { sample }));
  }

  /** Deletes the enrolment; the server requires a fresh signature that verifies. */
  deleteEnrolment(sample: HandwritingSample): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/handwriting/enrolment/deletion', { sample }));
  }
}

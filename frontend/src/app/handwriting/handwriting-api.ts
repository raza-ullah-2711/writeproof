import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { HandwritingSample } from './handwriting-sample';

export type LivenessFlag =
  | 'INSUFFICIENT_INPUT'
  | 'CONSTANT_VELOCITY'
  | 'NO_TIMING_VARIANCE'
  | 'CONSTANT_PRESSURE'
  | 'REPLAY';

export interface Enrolment {
  enrolled: boolean;
  sampleCount: number | null;
  enrolledAt: string | null;
}

export interface Verification {
  verified: boolean;
  score: number;
  threshold: number;
  match: boolean;
  live: boolean;
  livenessFlags: LivenessFlag[];
  shapeScore: number;
  durationScore: number;
}

/** Body of a 422 when an enrolment sample fails liveness checks. */
export interface NotLiveProblem {
  detail: string;
  sampleIndex: number;
  livenessFlags: LivenessFlag[];
}

export const MIN_ENROLMENT_SAMPLES = 3;
export const MAX_ENROLMENT_SAMPLES = 5;

export const LIVENESS_LABELS: Record<LivenessFlag, string> = {
  INSUFFICIENT_INPUT: 'Too short to judge. Write your full signature.',
  CONSTANT_VELOCITY: 'Speed was unnaturally constant.',
  NO_TIMING_VARIANCE: 'Timing was perfectly regular, like a machine.',
  CONSTANT_PRESSURE: 'Pen pressure never changed.',
  REPLAY: 'Identical to an enrolled sample. Write it fresh.',
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
}

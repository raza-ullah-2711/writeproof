import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { HandwritingSample } from '../handwriting/handwriting-sample';

export interface CalibrationStatus {
  contributing: boolean;
  practiceName: string | null;
  genuineSamples: number;
  forgerySamples: number;
}

export interface ForgeryTarget {
  targetId: string;
  practiceName: string;
  sample: HandwritingSample;
}

@Injectable({ providedIn: 'root' })
export class CalibrationApi {
  private readonly http = inject(HttpClient);

  status(): Promise<CalibrationStatus> {
    return firstValueFrom(this.http.get<CalibrationStatus>('/api/calibration'));
  }

  consent(): Promise<CalibrationStatus> {
    return firstValueFrom(this.http.post<CalibrationStatus>('/api/calibration/consent', null));
  }

  withdraw(): Promise<void> {
    return firstValueFrom(this.http.delete<void>('/api/calibration'));
  }

  contribute(
    kind: 'genuine' | 'forgery',
    sample: HandwritingSample,
    targetId?: string,
  ): Promise<CalibrationStatus> {
    return firstValueFrom(
      this.http.post<CalibrationStatus>('/api/calibration/samples', {
        kind,
        targetId: targetId ?? null,
        sample,
      }),
    );
  }

  forgeryTarget(): Promise<ForgeryTarget> {
    return firstValueFrom(this.http.get<ForgeryTarget>('/api/calibration/forgery-target'));
  }
}

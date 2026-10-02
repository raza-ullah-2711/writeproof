import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { SignatureView } from '../handwriting/signature-view';
import { CalibrationApi, CalibrationStatus, ForgeryTarget } from './calibration-api';

/**
 * Opt-in contributions that let Writeproof measure how well it tells hands apart. Contributors
 * never give their real signature: they write an assigned practice name, and imitate others'.
 */
@Component({
  selector: 'app-calibration',
  imports: [HandwritingPad, SignatureView, RouterLink],
  templateUrl: './calibration.html',
  styleUrl: './calibration.scss',
})
export class Calibration {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(CalibrationApi);

  protected readonly ownPad = viewChild<HandwritingPad>('ownPad');
  protected readonly imitationPad = viewChild<HandwritingPad>('imitationPad');

  protected readonly status = signal<CalibrationStatus | null>(null);
  protected readonly target = signal<ForgeryTarget | null>(null);
  protected readonly noTargets = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        void this.run(async () => this.status.set(await this.api.status()));
      }
    });
  }

  protected consent(): Promise<void> {
    return this.run(async () => this.status.set(await this.api.consent()));
  }

  protected withdraw(): Promise<void> {
    return this.run(async () => {
      await this.api.withdraw();
      this.target.set(null);
      this.status.set(await this.api.status());
    });
  }

  protected saveOwn(): Promise<void> {
    const sample = this.ownPad()?.sample();
    if (!sample) {
      return Promise.resolve();
    }
    return this.run(async () => {
      this.status.set(await this.api.contribute('genuine', sample));
      this.ownPad()?.clear();
    });
  }

  protected loadTarget(): Promise<void> {
    return this.run(async () => {
      try {
        this.target.set(await this.api.forgeryTarget());
        this.noTargets.set(false);
      } catch (e) {
        if (e instanceof HttpErrorResponse && e.status === 404) {
          this.target.set(null);
          this.noTargets.set(true);
          return;
        }
        throw e;
      }
    });
  }

  protected saveImitation(): Promise<void> {
    const sample = this.imitationPad()?.sample();
    const target = this.target();
    if (!sample || !target) {
      return Promise.resolve();
    }
    return this.run(async () => {
      this.status.set(await this.api.contribute('forgery', sample, target.targetId));
      this.imitationPad()?.clear();
      await this.loadTargetQuietly();
    });
  }

  private async loadTargetQuietly(): Promise<void> {
    try {
      this.target.set(await this.api.forgeryTarget());
    } catch {
      this.target.set(null);
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse
          ? ((e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`)
          : e instanceof Error
            ? e.message
            : 'Something went wrong.',
      );
    } finally {
      this.busy.set(false);
    }
  }
}

import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import {
  OpenLetter,
  OpenLettersService,
  VerifiedOpenLetter,
} from '../open-letters/open-letters.service';
import {
  REPORT_CATEGORIES,
  ReportCategory,
  categoryLabel,
} from '../open-letters/report-categories';

/** The public page for one open letter. Works without an account; verifies in this browser. */
@Component({
  selector: 'app-open-letter-view',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './open-letter-view.html',
  styleUrl: './open-letter-view.scss',
})
export class OpenLetterView implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly contacts = inject(ContactsService);
  private readonly openLetters = inject(OpenLettersService);
  private readonly route = inject(ActivatedRoute);

  protected readonly letter = signal<OpenLetter | null>(null);
  protected readonly checks = signal<VerifiedOpenLetter | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly categories = REPORT_CATEGORIES;
  protected readonly label = categoryLabel;
  protected readonly reporting = signal(false);
  protected readonly reported = signal<string | null>(null);
  protected readonly reportError = signal<string | null>(null);
  protected category: ReportCategory | null = null;
  protected note = '';
  private hash = '';

  async ngOnInit(): Promise<void> {
    this.hash = this.route.snapshot.paramMap.get('hash') ?? '';
    if (this.auth.authenticated()) {
      // Show your name for the author, if they're a contact.
      this.contacts.ensureLoaded().catch(() => undefined);
    }
    try {
      const letter = await this.openLetters.get(this.hash);
      this.letter.set(letter);
      this.checks.set(await this.openLetters.verify(letter, this.hash));
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400)
          ? 'There is no open letter at this link.'
          : 'This letter could not be loaded.',
      );
    }
  }

  protected async report(): Promise<void> {
    if (!this.category) {
      return;
    }
    this.reportError.set(null);
    try {
      await this.openLetters.report(this.hash, this.category, this.note);
      this.reported.set('Thank you. A moderator will review this letter.');
      this.reporting.set(false);
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.reported.set('You have already reported this letter.');
        this.reporting.set(false);
      } else if (e instanceof HttpErrorResponse && e.status === 429) {
        this.reportError.set('Too many reports from here. Try again later.');
      } else {
        this.reportError.set('The report could not be sent.');
      }
    }
  }

  protected petname(address: string): string | null {
    return this.auth.authenticated() ? this.contacts.petname(address) : null;
  }

  protected short(address: string): string {
    return `${address.slice(0, 8)}…${address.slice(-4)}`;
  }
}

import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import {
  OpenLetter,
  OpenLettersService,
  VerifiedOpenLetter,
} from '../open-letters/open-letters.service';

/** The public page for one open letter. Works without an account; verifies in this browser. */
@Component({
  selector: 'app-open-letter-view',
  imports: [RouterLink],
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

  async ngOnInit(): Promise<void> {
    const hash = this.route.snapshot.paramMap.get('hash') ?? '';
    if (this.auth.authenticated()) {
      // Show your name for the author, if they're a contact.
      this.contacts.ensureLoaded().catch(() => undefined);
    }
    try {
      const letter = await this.openLetters.get(hash);
      this.letter.set(letter);
      this.checks.set(await this.openLetters.verify(letter, hash));
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400)
          ? 'There is no open letter at this link.'
          : 'This letter could not be loaded.',
      );
    }
  }

  protected petname(address: string): string | null {
    return this.auth.authenticated() ? this.contacts.petname(address) : null;
  }

  protected short(address: string): string {
    return `${address.slice(0, 8)}…${address.slice(-4)}`;
  }
}

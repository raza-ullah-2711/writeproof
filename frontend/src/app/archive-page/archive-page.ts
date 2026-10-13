import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ArchiveEntry,
  ArchiveService,
  MAX_ARCHIVE_PHOTOS,
  MAX_NOTE,
  MAX_TITLE,
  MAX_WRITTEN_ON,
  OpenedEntry,
} from '../archive/archive.service';
import { PreparedPhoto, jpegDataUrl, preparePhoto } from '../archive/photo-prep';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import { WalletService } from '../wallet/wallet.service';

/** A photo chosen for a new entry, with its preview. */
interface Chosen {
  photo: PreparedPhoto;
  preview: string;
}

/** An opened entry and its decrypted photos (as data: URLs), filled in as they arrive. */
interface Viewed {
  opened: OpenedEntry;
  photos: (string | null)[];
  current: number;
}

/**
 * The family archive (docs/family-archive.md): photos of paper letters that should never be
 * lost, sealed to the people you choose. Writeproof proves who added a copy and that it hasn't
 * changed since, not who wrote the original.
 */
@Component({
  selector: 'app-archive-page',
  imports: [FormsModule, RouterLink],
  templateUrl: './archive-page.html',
  styleUrl: './archive-page.scss',
})
export class ArchivePage {
  protected readonly auth = inject(AuthService);
  protected readonly wallet = inject(WalletService);
  protected readonly contacts = inject(ContactsService);
  private readonly archive = inject(ArchiveService);

  protected readonly entries = signal<ArchiveEntry[] | null>(null);
  protected readonly viewed = signal<Record<string, Viewed | 'opening'>>({});
  protected readonly chosen = signal<Chosen[]>([]);
  protected readonly preparing = signal(false);
  protected readonly progress = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly shareWith = signal<ReadonlySet<string>>(new Set());
  protected title = '';
  protected writtenOn = '';
  protected note = '';
  protected readonly maxPhotos = MAX_ARCHIVE_PHOTOS;
  protected readonly maxTitle = MAX_TITLE;
  protected readonly maxWrittenOn = MAX_WRITTEN_ON;
  protected readonly maxNote = MAX_NOTE;

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        void this.contacts.ensureLoaded().catch(() => undefined);
        void this.load();
      }
    });
  }

  protected async choose(input: HTMLInputElement): Promise<void> {
    const files = [...(input.files ?? [])];
    input.value = '';
    const room = MAX_ARCHIVE_PHOTOS - this.chosen().length;
    if (files.length > room) {
      this.error.set(`An entry can have at most ${MAX_ARCHIVE_PHOTOS} photos.`);
    }
    this.preparing.set(true);
    try {
      for (const file of files.slice(0, Math.max(0, room))) {
        const photo = await preparePhoto(file);
        this.chosen.update((list) => [...list, { photo, preview: jpegDataUrl(photo.bytes) }]);
      }
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : 'Could not prepare that photo.');
    } finally {
      this.preparing.set(false);
    }
  }

  protected removePhoto(index: number): void {
    this.chosen.update((list) => list.filter((_, i) => i !== index));
  }

  protected toggleShare(address: string, on: boolean): void {
    this.shareWith.update((set) => {
      const next = new Set(set);
      if (on) {
        next.add(address);
      } else {
        next.delete(address);
      }
      return next;
    });
  }

  protected async add(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const entry = await this.archive.add(
        this.chosen().map((c) => c.photo),
        { title: this.title, writtenOn: this.writtenOn, note: this.note },
        [...this.shareWith()],
        (done, total) =>
          this.progress.set(done < total ? `Sealing photo ${done + 1} of ${total}…` : 'Signing…'),
      );
      this.notice.set(`Added to the archive as ledger entry #${entry.ledger.seq}.`);
      this.chosen.set([]);
      this.shareWith.set(new Set());
      this.title = '';
      this.writtenOn = '';
      this.note = '';
      this.adding.set(false);
      await this.load();
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.progress.set(null);
      this.busy.set(false);
    }
  }

  protected async open(entry: ArchiveEntry): Promise<void> {
    this.viewed.update((v) => ({ ...v, [entry.entryHash]: 'opening' }));
    try {
      const opened = await this.archive.open(entry);
      const view: Viewed = { opened, photos: entry.photos.map(() => null), current: 0 };
      this.viewed.update((v) => ({ ...v, [entry.entryHash]: view }));
      if (opened.key) {
        for (let i = 0; i < entry.photos.length; i++) {
          const bytes = await this.archive.photo(entry, opened.key, i);
          this.setPhoto(entry.entryHash, i, jpegDataUrl(bytes));
        }
      }
    } catch (e) {
      this.viewed.update(({ [entry.entryHash]: _, ...rest }) => rest);
      this.error.set(describe(e));
    }
  }

  protected show(entry: ArchiveEntry, index: number): void {
    this.viewed.update((v) => {
      const view = v[entry.entryHash];
      return view && view !== 'opening'
        ? { ...v, [entry.entryHash]: { ...view, current: index } }
        : v;
    });
  }

  protected viewOf(entry: ArchiveEntry): Viewed | 'opening' | undefined {
    return this.viewed()[entry.entryHash];
  }

  protected asViewed(value: Viewed | 'opening' | undefined): Viewed | null {
    return value && value !== 'opening' ? value : null;
  }

  protected isMine(entry: ArchiveEntry): boolean {
    return entry.owner === this.wallet.publicKey();
  }

  protected name(address: string): string {
    if (address === this.wallet.publicKey()) {
      return 'you';
    }
    return this.contacts.petname(address) ?? `${address.slice(0, 8)}…`;
  }

  /** Everyone but the owner, by name. */
  protected sharedWith(entry: ArchiveEntry): string {
    const names = entry.readers.slice(1).map((r) => this.name(r.publicKey));
    return names.length === 0 ? 'only you' : names.join(', ');
  }

  private setPhoto(hash: string, index: number, url: string): void {
    this.viewed.update((v) => {
      const view = v[hash];
      if (!view || view === 'opening') {
        return v;
      }
      const photos = [...view.photos];
      photos[index] = url;
      return { ...v, [hash]: { ...view, photos } };
    });
  }

  private async load(): Promise<void> {
    try {
      this.entries.set(await this.archive.list());
    } catch (e) {
      this.error.set(describe(e));
    }
  }
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'The server is unreachable.';
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ArchiveEntry, ArchiveService, OpenedEntry } from '../archive/archive.service';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import { WalletService } from '../wallet/wallet.service';
import { ArchivePage } from './archive-page';

const ME = 'A'.repeat(43);
const GRAN = 'G'.repeat(43);

const ENTRY: ArchiveEntry = {
  entryHash: 'entry-1',
  owner: GRAN,
  addedAt: '2026-10-14T10:00:00.000Z',
  iv: '',
  ciphertext: '',
  signature: '',
  photos: ['p1', 'p2'],
  readers: [
    { publicKey: GRAN, ephemeralPublicKey: '', iv: '', wrappedKey: '' },
    { publicKey: ME, ephemeralPublicKey: '', iv: '', wrappedKey: '' },
  ],
  ledger: { seq: 12, prevHash: '', payloadHash: '', recordedAtMillis: 0, entryHash: '' },
};

const OPENED: OpenedEntry = {
  description: {
    title: 'Letters from the war',
    writtenOn: '1944',
    note: 'Kept in a biscuit tin.',
    photos: [
      { width: 10, height: 10 },
      { width: 10, height: 10 },
    ],
  },
  signatureValid: true,
  decrypted: true,
  ledgerValid: true,
  ledgerProblem: null,
  ledgerCheckpointSize: 20,
  key: {} as CryptoKey,
};

describe('ArchivePage', () => {
  let service: {
    list: ReturnType<typeof vi.fn>;
    open: ReturnType<typeof vi.fn>;
    photo: ReturnType<typeof vi.fn>;
    add: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    service = {
      list: vi.fn().mockResolvedValue([ENTRY]),
      open: vi.fn().mockResolvedValue(OPENED),
      photo: vi.fn().mockResolvedValue(new Uint8Array([0xff, 0xd8, 0xff])),
      add: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [ArchivePage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated: signal(true) } },
        { provide: ArchiveService, useValue: service },
        { provide: WalletService, useValue: { publicKey: signal(ME) } },
        {
          provide: ContactsService,
          useValue: {
            contacts: signal([{ address: GRAN, petname: 'Gran' }]),
            petname: (a: string) => (a === GRAN ? 'Gran' : null),
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
          },
        },
      ],
    }).compileComponents();
  });

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  it('lists what is shared with you, and opens it with its checks and an honest label', async () => {
    const fixture = TestBed.createComponent(ArchivePage);
    const el: HTMLElement = fixture.nativeElement;
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!el.querySelector('.entry')) throw new Error('not yet');
    });
    expect(el.querySelector('.entry .meta')?.textContent).toContain('Shared by Gran');

    button(el, 'Open and verify').click();
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!el.querySelector('.viewer img')) throw new Error('not yet');
    });

    expect(el.querySelector('h4')?.textContent).toBe('Letters from the war');
    expect(el.querySelector('.viewer img')?.getAttribute('src')).toMatch(
      /^data:image\/jpeg;base64,/,
    );
    expect(el.querySelector('.label')?.textContent).toContain('Archived copy, added by Gran');
    const checks = [...el.querySelectorAll('.checks li')].map((li) => li.textContent?.trim());
    expect(checks[0]).toMatch(/^✓ Added by\s+Gran/);
    expect(checks[2]).toMatch(/✓ Unchanged since\s+2026-10-14/);
    expect(checks[3]).toContain("Who wrote the original can't be proven");
    expect(service.photo).toHaveBeenCalledTimes(2);
  });

  it('offers the people in your contacts to share with', async () => {
    const fixture = TestBed.createComponent(ArchivePage);
    const el: HTMLElement = fixture.nativeElement;
    await fixture.whenStable();
    button(el, 'Add a letter').click();
    await fixture.whenStable();

    expect(el.querySelector('.share')?.textContent).toContain('Gran');
    expect(button(el, 'Seal and add to the archive').disabled).toBe(true); // no photos yet
  });
});

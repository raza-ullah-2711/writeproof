import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { fakeContext } from '../../testing/pointer';
import { AuthService } from '../auth/auth.service';
import { Contact } from '../contacts/contact-book';
import { ContactsService } from '../contacts/contacts.service';
import { WalletService } from '../wallet/wallet.service';
import { ContactsPage } from './contacts-page';

const ME = 'M'.repeat(43);
const BOB = 'B'.repeat(43);

describe('ContactsPage', () => {
  const authenticated = signal(true);
  const list = signal<Contact[] | null>(null);
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let queryParams: Record<string, string>;
  const walletState = signal<'unknown' | 'none' | 'ready'>('ready');
  const wallet = {
    publicKey: () => ME,
    state: walletState,
    load: vi.fn(async () => walletState.set('ready')),
  };
  let login: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    authenticated.set(true);
    list.set([]);
    queryParams = {};
    walletState.set('ready');
    wallet.load.mockClear();
    login = vi.fn(async () => authenticated.set(true));
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    service = {
      ensureLoaded: vi.fn().mockResolvedValue(undefined),
      add: vi.fn(async (address: string, petname: string) => {
        const c = { address, petname: petname.trim(), addedAt: '' };
        list.update((l) => [...(l ?? []), c]);
        return c;
      }),
      rename: vi.fn(async (address: string, petname: string) =>
        list.update((l) => l!.map((c) => (c.address === address ? { ...c, petname } : c))),
      ),
      remove: vi.fn(async (address: string) =>
        list.update((l) => l!.filter((c) => c.address !== address)),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [ContactsPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated, login } },
        { provide: WalletService, useValue: wallet },
        { provide: ContactsService, useValue: { ...service, contacts: list } },
        {
          provide: ActivatedRoute,
          useValue: {
            get snapshot() {
              return { queryParamMap: convertToParamMap(queryParams) };
            },
          },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const fixture = TestBed.createComponent(ContactsPage);
    await fixture.whenStable();
    return fixture;
  }

  async function type(fixture: ComponentFixture<ContactsPage>, name: string, value: string) {
    const input = fixture.nativeElement.querySelector(`input[name=${name}]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  it('opened from a shared link before signing in: loads the wallet and signs in on the spot', async () => {
    authenticated.set(false);
    walletState.set('unknown');
    queryParams = { add: BOB };
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(wallet.load).toHaveBeenCalled();
    expect(el.textContent).toContain('Sign in to add the address you were sent');
    expect(service['ensureLoaded']).not.toHaveBeenCalled();
    button(el, 'Sign in').click();
    await vi.waitFor(() => expect(el.querySelector('input[name=address]')).not.toBeNull());

    expect(login).toHaveBeenCalled();
    expect(el.querySelector<HTMLInputElement>('input[name=address]')!.value).toBe(BOB);
    expect(service['ensureLoaded']).toHaveBeenCalled();
  });

  it('without a wallet, says to create or restore one first', async () => {
    authenticated.set(false);
    walletState.set('none');
    const fixture = await render();

    expect(fixture.nativeElement.textContent).toContain('Create or restore a wallet');
  });

  it('shares your address as a QR code and a link', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('app-qr-code canvas')?.getAttribute('aria-label')).toBe(
      'QR code with your address',
    );
    expect(el.querySelector('.share .address')?.textContent).toBe(ME);
    expect(fixture.componentInstance['shareLink']()).toBe(`${location.origin}/contacts?add=${ME}`);
  });

  it('adds a contact', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.empty')?.textContent).toContain('No contacts yet');

    await type(fixture, 'address', BOB);
    await type(fixture, 'petname', 'Bob from choir');
    button(el, 'Add contact').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());
    await fixture.whenStable();

    expect(service['add']).toHaveBeenCalledWith(BOB, 'Bob from choir');
    expect(el.querySelector('.notice')?.textContent).toContain('Added “Bob from choir”');
    expect(el.querySelector('.contact .petname')?.textContent).toBe('Bob from choir');
    expect(el.querySelector('.contact a')?.getAttribute('href')).toBe(`/letters?to=${BOB}`);
  });

  it('prefills an address from a shared link, with a warning, and clears the link after', async () => {
    queryParams = { add: BOB };
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector<HTMLInputElement>('input[name=address]')!.value).toBe(BOB);
    expect(el.querySelector('.add .hint')?.textContent).toContain('Only add it if you know whose');
    await type(fixture, 'petname', 'Bob');
    button(el, 'Add contact').click();
    await vi.waitFor(() => expect(navigate).toHaveBeenCalled());
  });

  it('shows why adding failed', async () => {
    service['add'].mockRejectedValue(new Error('You already have a contact called “Bob”'));
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await type(fixture, 'address', BOB);
    await type(fixture, 'petname', 'bob');
    button(el, 'Add contact').click();
    await vi.waitFor(() => expect(el.querySelector('[role=alert]')).not.toBeNull());

    expect(el.querySelector('[role=alert]')?.textContent).toContain('already have a contact');
  });

  it('renames and removes, asking before removing', async () => {
    list.set([{ address: BOB, petname: 'Bob', addedAt: '' }]);
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Rename').click();
    await fixture.whenStable();
    await type(fixture, 'newName', 'Robert');
    button(el, 'Save').click();
    await vi.waitFor(() => expect(service['rename']).toHaveBeenCalledWith(BOB, 'Robert'));
    await fixture.whenStable();
    expect(el.querySelector('.contact .petname')?.textContent).toBe('Robert');

    button(el, 'Remove').click();
    await fixture.whenStable();
    expect(el.querySelector('.contact .actions')?.textContent).toContain('Remove “Robert”?');
    expect(service['remove']).not.toHaveBeenCalled();
    button(el, 'Remove').click();
    await vi.waitFor(() => expect(service['remove']).toHaveBeenCalledWith(BOB));
  });

  it('reports a contact book that cannot be loaded', async () => {
    service['ensureLoaded'].mockRejectedValue(
      new Error('The server returned an older contact book'),
    );
    list.set(null);
    const fixture = await render();
    await vi.waitFor(() =>
      expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
        'older contact book',
      ),
    );
  });
});

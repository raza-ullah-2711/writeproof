import { LedgerTrustStore } from './ledger-trust';

describe('LedgerTrustStore', () => {
  let store: LedgerTrustStore;

  beforeEach(() => {
    localStorage.clear();
    store = new LedgerTrustStore();
  });

  it('remembers nothing at first, then the key and checkpoint it is given', () => {
    expect(store.load()).toBeNull();

    store.remember({ publicKey: 'k', size: 3, root: 'r3' });

    expect(store.load()).toEqual({ publicKey: 'k', size: 3, root: 'r3' });
  });

  it('only moves forward', () => {
    store.remember({ publicKey: 'k', size: 5, root: 'r5' });
    store.remember({ publicKey: 'k', size: 4, root: 'r4' });
    expect(store.load()?.size).toBe(5);

    store.remember({ publicKey: 'k', size: 9, root: 'r9' });
    expect(store.load()).toEqual({ publicKey: 'k', size: 9, root: 'r9' });
  });

  it('ignores corrupt storage', () => {
    localStorage.setItem('writeproof.ledger-trust.v1', '{"publicKey":1}');
    expect(store.load()).toBeNull();
    localStorage.setItem('writeproof.ledger-trust.v1', 'not json');
    expect(store.load()).toBeNull();
  });
});

import { loginMessage } from './login-message';

describe('loginMessage', () => {
  const challengeId = '00000000-0000-4000-8000-000000000001';
  const nonce = Uint8Array.from({ length: 32 }, (_, i) => i);

  it('matches the vector shared with the backend LoginMessageTest', () => {
    expect(new TextDecoder().decode(loginMessage(challengeId, nonce))).toBe(
      'writeproof/login/v1\n00000000-0000-4000-8000-000000000001\nAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8',
    );
  });

  it('refuses to build a message from a malformed challenge', () => {
    expect(() => loginMessage('../../etc', nonce)).toThrow();
    expect(() => loginMessage(challengeId, new Uint8Array(16))).toThrow();
  });
});

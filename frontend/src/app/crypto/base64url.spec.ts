import { fromBase64Url, toBase64Url } from './base64url';

describe('base64url', () => {
  it('encodes without padding using the URL-safe alphabet', () => {
    expect(toBase64Url(new Uint8Array([0xfb, 0xff]))).toBe('-_8');
    expect(toBase64Url(new Uint8Array([]))).toBe('');
  });

  it('round-trips arbitrary bytes', () => {
    const bytes = crypto.getRandomValues(new Uint8Array(67));
    expect(fromBase64Url(toBase64Url(bytes))).toEqual(bytes);
  });

  it('rejects characters outside the base64url alphabet', () => {
    expect(() => fromBase64Url('a+b/')).toThrow();
  });
});

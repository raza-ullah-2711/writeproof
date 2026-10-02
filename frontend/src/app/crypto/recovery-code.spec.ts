import { formatRecoveryCode, generateRecoveryCode, parseRecoveryCode } from './recovery-code';

describe('recovery codes', () => {
  it('formats 16 bytes as 7 groups of 4 and parses back', async () => {
    const { code, bytes } = await generateRecoveryCode();

    expect(code).toMatch(/^([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{4}$/);
    expect(await parseRecoveryCode(code)).toEqual(bytes);
  });

  it('round-trips the extremes', async () => {
    for (const bytes of [new Uint8Array(16), new Uint8Array(16).fill(0xff)]) {
      expect(await parseRecoveryCode(await formatRecoveryCode(bytes))).toEqual(bytes);
    }
  });

  it('forgives case, spacing and look-alike characters', async () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, i) => i * 17);
    const code = await formatRecoveryCode(bytes);
    const sloppy = code.toLowerCase().replace(/-/g, ' ').replace(/0/g, 'o').replace(/1/g, 'l');

    expect(await parseRecoveryCode(`  ${sloppy} `)).toEqual(bytes);
  });

  it('catches a mistyped character with the checksum', async () => {
    const { code } = await generateRecoveryCode();
    const i = code.indexOf('-') - 1;
    const wrong = code[i] === 'A' ? 'B' : 'A';
    const typo = code.slice(0, i) + wrong + code.slice(i + 1);

    await expect(parseRecoveryCode(typo)).rejects.toThrow(/typos/);
  });

  it('rejects the wrong length and impossible characters', async () => {
    await expect(parseRecoveryCode('ABCD-EFGH')).rejects.toThrow(/28 characters/);
    await expect(parseRecoveryCode('U'.repeat(28))).rejects.toThrow(/can’t have/);
  });

  it('only accepts 16-byte codes', async () => {
    await expect(formatRecoveryCode(new Uint8Array(8))).rejects.toThrow(/16 bytes/);
  });
});

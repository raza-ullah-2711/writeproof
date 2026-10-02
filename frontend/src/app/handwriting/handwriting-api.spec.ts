import { describeRejection } from './handwriting-api';

describe('describeRejection', () => {
  it('lists the reasons without a score when the server hides it', () => {
    expect(
      describeRejection({
        detail: "Your signature didn't pass the liveness checks",
        match: true,
        livenessFlags: ['REPLAY', 'STALE'],
      }),
    ).toBe(
      "Your signature didn't pass the liveness checks. " +
        'Identical to a signature you already used. Write it fresh. Written too long ago. Sign again.',
    );
  });

  it('includes the score when the server sends one', () => {
    expect(
      describeRejection({ detail: 'No match', match: false, livenessFlags: [], score: 0.123 }),
    ).toBe('No match (similarity 0.12).');
  });
});

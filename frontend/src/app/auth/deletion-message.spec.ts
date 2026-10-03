import { deletionMessage } from './deletion-message';

describe('deletionMessage', () => {
  it('is exactly what the backend verifies (DeletionMessage)', () => {
    expect(
      new TextDecoder().decode(
        deletionMessage('00000000-0000-0000-0000-000000000001', '2026-10-03T12:00:00.000Z'),
      ),
    ).toBe(
      'writeproof/delete-account/v1\n00000000-0000-0000-0000-000000000001\n2026-10-03T12:00:00.000Z',
    );
  });
});

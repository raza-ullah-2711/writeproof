# Threads (Task 11b)

The second slice of the social layer. You can reply to a letter, and letters are grouped into
conversations between two people.

## The reply link is signed

A reply uses header v3, which is a v2 header plus the hash of the letter it answers:

```
writeproof/letter/v3\n sender \n recipient \n sentAt \n handwritingHash \n inReplyTo
```

The header is both signed (through the letter hash) and the AES-GCM associated data. So the
server can't attach a reply to a different letter, or strip the link and pass the reply off as a
fresh letter: either change breaks the signature and the decryption. A new letter that starts a
conversation is still v2.

## What the server enforces

- A reply must answer a letter between **the same two people**. An unknown hash and someone
  else's letter get the same 422, so replying can't be used to probe for letters.
- `in_reply_to` references `letters(letter_hash)`. `thread_id`, the hash of the letter that
  started the conversation, is set only on replies (migration V11), and a letter that starts a
  conversation is its own thread. Existing letters therefore needed no backfill, which the
  append-only trigger would forbid anyway.
- The server sees the conversation structure (which letter answers which), much as it already
  sees who writes to whom. Bodies stay sealed.

## API

| Endpoint                              | Returns                                                                        |
| ------------------------------------- | ------------------------------------------------------------------------------ |
| `POST /api/letters` with `inReplyTo`  | the reply; 422 unless it answers a letter between the same two people          |
| `GET /api/letters/threads`            | `{threadId, counterpart, letters, latestSeq, latestSentAt}`, most recent first |
| `GET /api/letters/threads/{threadId}` | the conversation's letters, oldest first; 404 unless you're in it              |

Every letter response now carries `inReplyTo` (null unless it's a reply) and `threadId`.

## In the browser

- An opened letter, once verified, offers **Reply**. The reply is addressed to the other person,
  the recipient field is locked, and the compose form says which letter it answers.
  `LettersService.send` refuses a reply addressed to anyone else.
- The **Conversations** tab lists conversations, showing the other person's petname. Opening one
  runs `checkThread`, which checks that:
  - the conversation starts with the letter it's named after;
  - every later letter answers an earlier one in the conversation;
  - all letters are between the same two people.

  Opening each letter then verifies its signature, which is what makes its link trustworthy.

- Replies show "↳ reply". A verified reply says its wallet signature covers which letter it
  answers.

## Next

Open letters: signed by hand and wallet but not sealed, and readable by anyone with the link.

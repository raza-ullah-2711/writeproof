# Brand

Writeproof's mark is about one idea: a letter that is handwritten, then sealed. The domain will
be getwriteproof.com; the brand stays "Writeproof".

## The marks

| Mark                                                                                                              | Use                                                      | Files                                                                                                                             |
| ----------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| **Seal**: a wax seal holding a one-stroke W whose last stroke rises like a check                                  | App and browser icons, avatars, anywhere square or small | `frontend/public/brand/logo-mark.svg`, `logo-mark-512.png`; `favicon.svg`/`.ico`, `apple-touch-icon.png`                          |
| **Signed wordmark**: a handwritten W, "riteproof" in serif italic, a signature underline ending in a seal-red dot | Headers, documents, anywhere wide                        | `src/app/brand/logo.ts` (the apps' header, follows the theme); `frontend/public/brand/logo-wordmark.svg`, `logo-wordmark-800.png` |
| Social preview (1200×630)                                                                                         | `og:image` for shared links                              | `frontend/public/brand/og-image.png`                                                                                              |

The admin app's browser icon is the same seal in ink instead of red, so the two tabs are easy to
tell apart.

## Colours

| Role                 | Light     | Dark      | Token     |
| -------------------- | --------- | --------- | --------- |
| Ink (wordmark, text) | `#1b1f3a` | `#e6e8f5` | `--ink`   |
| Seal (accent)        | `#a8322d` | `#ef6b62` | `--seal`  |
| Paper (sheets)       | `#fffdf7` | `#171824` | `--paper` |
| Page (desk)          | `#f5f0e6` | `#0b0c14` | `--page`  |

Seal red is an accent: the seal, the dot, the active-page dot, small uppercase eyebrows and the
opening quotation mark of an open letter. Don't use it for body text or status (critical red is a
different colour on purpose).

## Type

- **Fraunces** (variable, OFL): headings, the wordmark (italic, soft and "wonky" axes on), letter
  bodies and the editorial lede. Self-hosted from `@fontsource-variable/fraunces`.
- **Inter** (variable, OFL): everything else in the interface.

Tokens: `--font-display`, `--font-ui`, `--font-mono` (theme.scss). The static wordmark files and
the social image still set "riteproof" in Georgia; redraw them in Fraunces italic (outlined) when
they are next touched.

## Rules

- Keep clear space around the seal of at least a quarter of its width.
- Don't recolour the seal outside the palette, add effects (shadows, gradients), or stretch it.
- On photos or busy backgrounds, use the seal on a paper-coloured disc or the paper rectangle
  from `apple-touch-icon.png`.

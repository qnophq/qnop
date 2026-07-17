# qnop branding

The qnop brand assets (issue #153) and the script that regenerates them.

## The mark

The logomark is a **speech-bubble "Q"**: the bowl of the Q is a comment
balloon, its pointed tail sits at the bottom right like the swash of a
capital Q, and the blue dot inside is an annotation anchor — a direct nod
to what qnop does (marking up and commenting on documents).

In the wordmark lockup the mark itself is the capital "Q" of "Qnop":
it stands at cap height on the shared baseline, followed by "nop" in
lowercase at two thirds of the mark's height.

| Token | Value | Used for |
|---|---|---|
| Navy | `#012142` | Bubble on light backgrounds, logomark tile |
| Accent blue | `#1292EE` | Annotation dot (the only color accent) |
| White | `#FFFFFF` | Bubble/wordmark on dark backgrounds |

The dark-mode base color of the product UI is **black**, so all dark
variants are designed and verified against black, not navy. The wordmark
rest ("nop") is set in **Manrope Bold** (SIL OFL 1.1) and embedded as
outlines — the SVGs have no font dependency at render time.

## Generated files

| File | Purpose |
|---|---|
| `qnop-core/.../branding/defaults/logo-light.svg` | Wordmark lockup for light backgrounds (default branding slot) |
| `qnop-core/.../branding/defaults/logo-dark.svg` | Wordmark lockup for dark backgrounds (default branding slot) |
| `qnop-core/.../branding/defaults/logomark.svg` | Compact square mark on a navy tile (default branding slot, favicon source) |
| `qnop-ui/public/favicon.svg` | SPA favicon (same tile as the logomark) |
| `qnop-ui/public/favicon-32.png` | PNG favicon fallback |
| `qnop-ui/public/apple-touch-icon.png` | Opaque 180×180 touch icon |
| `qnop-ui/public/og-image.png` | 1200×630 OG/social share image |

All slot assets stay far below the 512 KiB branding cap (ADR-0024).

## Regenerating

```bash
cd docs/branding
curl -sfL -o "Manrope[wght].ttf" \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf"
npm install
npm run generate
```

The script writes the files listed above in place. The font file and
`node_modules/` are git-ignored; only the generated assets are committed.

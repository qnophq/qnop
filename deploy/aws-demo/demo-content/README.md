<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Demo content generator

Produces the demo reviews for demo.qnop.io (issue #710): 13 excerpts of
public-domain literature rendered to PDF, staged as reviews with a lively,
fully **anchored** discussion — annotations with region + text-quote
anchors (ADR-0009), replies, resolutions and mixed workflow states. No
document-scoped ("global") annotations are used.

| File | Purpose |
|---|---|
| `screenplay.mjs` | The cast and script: stories, owners, participants, states, every annotation and reply |
| `generate-pdfs.mjs` | Fetches the texts from Project Gutenberg (cached in `cache/`), renders `pdfs/*.pdf` and records per-paragraph anchor targets (page + normalized box + quote) in `targets/*.json` |
| `create-demo-content.mjs` | Plays the screenplay against a running instance through the public API |

## Usage

```bash
npm install
node generate-pdfs.mjs
QNOP_BASE_URL=https://demo.qnop.io node create-demo-content.mjs
```

Requirements and behaviour:

- Node ≥ 18 (global `fetch`/`FormData`), network access to gutenberg.org
  on the first `generate-pdfs.mjs` run.
- The target instance must be seeded with `testdata/db/seed.sql` — the
  screenplay references the seeded users and teams by their fixed ids.
- Sign-ins are paced to stay under the per-IP auth rate limit
  (ADR-0027), so a full run takes a few minutes.
- Idempotent per story: a taken slug means the story is already staged
  and is skipped. To restage one story, delete the review first
  (`DELETE /api/v1/documents/{id}`, ADMIN only) and re-run.
- After staging, capture the state with `../build-golden-state.sh` so
  the 12-hour reset restores it.

The texts are public domain (all authors died more than 70 years ago);
only short excerpts are rendered, marked as demo content on the title
block.

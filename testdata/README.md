<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# testdata

Shared, repo-level test fixtures for populating the qnop database and driving
integration tests. Keep fixtures small, deterministic, and self-describing so a
test that loads one reads clearly.

## How tests locate this directory

Integration tests resolve the path from the `qnop.testdata.dir` system property,
which the Gradle convention plugin sets to this directory for every `Test` task
(`build-logic/.../qnop.java-conventions.gradle.kts`). The `io.qnop.testsupport.TestData`
helper reads that property and falls back to walking up from the working
directory, so tests also run from an IDE without extra configuration.

```java
byte[] png = TestData.bytes("branding/logo-light.png");
Path svg = TestData.path("branding/logomark.svg");
```

## Layout

```
testdata/
├── branding/                 # branding upload fixtures (issue #106)
│   ├── logo-light.png        # valid 320×96 RGBA PNG — "TEST LOGO" wordmark (navy text)
│   ├── logo-dark.png         # valid 320×96 RGBA PNG — "TEST LOGO" wordmark (white text), for "replace"
│   ├── logomark.svg          # valid, clean SVG — "T" mark
│   ├── unsafe.svg            # hostile SVG (script + onload + javascript: link) for sanitization
│   └── not-an-image.txt      # plain text — must be rejected as an unsupported type (415)
├── db/                       # deterministic SQL fixtures (issue #163)
│   ├── clean.sql             # wipes the seeded tables to a known-empty slate
│   └── seed.sql              # the shared seeded dataset (users, teams, OIDC provider,
│                             # Mailpit SMTP settings; issue #401 adds a 20-user crowd
│                             # and 5 department teams for manual testing)
└── documents/                # review document payloads (issues #308, #370)
    ├── sample.pdf            # minimal valid single-page PDF, text "QNOP SMOKE TEST"
    ├── doc1/                 # multi-version dummy: test-dummy-v1..v5.pdf (1 page each)
    ├── doc2/                 # multi-version story: scifi-story-v1..v5.pdf (3 pages each)
    └── doc3/                 # placement lab: placement-lab-v1..v3.pdf (1 page each, issue #480)
```

The `documents/sample.pdf` fixture backs the ingest smoke test: it is uploaded,
the extraction job renders it, and the smoke asserts the extracted text — so it
must stay a real, PDFBox-parsable PDF with extractable text.

The multi-version families (issue #370) drive `DocumentFixtureLifecycleIT` and
the smoke's multi-version/diff steps:

- **doc1** (`test-dummy-v1..v5.pdf`) — every version names itself in its text
  (`Testdokument – Version N`, trailer `Dokument-ID: TEST-DUMMY-VN`), so a test
  can assert that each stored version keeps serving exactly its own rendering.
- **doc2** (`scifi-story-v1..v5.pdf`) — a three-page story ("Das letzte
  Signal") with real word-level edits between versions; v1→v2 replaces
  *letzten* → *einsamen* in the Kalinda sentence, which the diff assertions
  pin down. Keep those anchor words stable when regenerating the fixtures.
- **doc3** (`placement-lab-v1..v3.pdf`) — the placement lab for manually
  exercising re-anchoring outcomes (issues #457/#480). Annotate the three
  target sentences in v1 (sections 3–5: ALPHA, BRAVO, CHARLIE), then upload
  v2: ALPHA keeps its wording but a section inserted before it shifts its
  position → **MOVED**; the BRAVO and CHARLIE sentences vanish without a
  near-duplicate → **ORPHANED**. v3 prepends a preamble that shifts the whole
  document once more, so placed (and freshly re-attached) annotations go
  MOVED again for a second round.

The seed's role/state users (`admin`, `member`, `auditor`, …) are pinned by
`SeededAdminUsersIT`/`SeededTeamIT` — keep their rows and the Alpha/Beta teams
byte-stable. The issue #401 crowd (20 MEMBER users `nora`…`david`, teams Legal/
Compliance/Finance/Procurement/Engineering, 5 users teamless) exists for manual
testing and carries no pinned counts; all passwords are `Test-Pass-1234!`. The
SMTP settings point at the docker-compose Mailpit (`localhost:1025`, inbox on
`localhost:8025`).

Add new fixture families as sibling directories as the suites that need them land.

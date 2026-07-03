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
│   └── seed.sql              # the shared seeded dataset (users, team, OIDC provider)
└── documents/                # review document payloads (issue #308)
    └── sample.pdf            # minimal valid single-page PDF, text "QNOP SMOKE TEST"
```

The `documents/sample.pdf` fixture backs the ingest smoke test: it is uploaded,
the extraction job renders it, and the smoke asserts the extracted text — so it
must stay a real, PDFBox-parsable PDF with extractable text.

Add new fixture families as sibling directories as the suites that need them land.

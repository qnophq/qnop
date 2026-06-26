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
└── branding/                 # branding upload fixtures (issue #106)
    ├── logo-light.png        # valid 96×96 RGBA PNG (navy)
    ├── logo-dark.png         # valid 120×64 RGBA PNG (blue) — used to test "replace"
    ├── logomark.svg          # valid, clean SVG
    ├── unsafe.svg            # hostile SVG (script + onload + javascript: link) for sanitization
    └── not-an-image.txt      # plain text — must be rejected as an unsupported type (415)
```

Add new fixture families as sibling directories (e.g. `db/` seed scripts,
`documents/` review payloads) as the suites that need them land.

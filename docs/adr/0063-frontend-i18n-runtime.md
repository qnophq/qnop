# ADR-0063: Frontend i18n — i18next/react-i18next, a layered language resolution, English bundled

- **Status:** Accepted
- **Date:** 2026-10-12
- **Deciders:** devtank42 (with Claude)

## Context

The `qnop-ui` SPA was English-only: every user-facing string was a literal, and formatters hardcoded
`en`/`en-GB`/`en-US`. The backend already stored the inputs for a language choice — the per-user
`preferred_language` and the application default `general.default_language` (issue #22) — and mail
templates already resolve per locale (requested → configured default → `en`). Issue #464 adds the
frontend runtime and a user-selectable UI language. Language is a separate concern from the display
time zone (ADR-0041); the two share only the `useFormatters()` seam.

Two facts shaped the decision. `preferred_language` defaulted to `"en"`, so "no preference" was
indistinguishable from an explicit English choice and no fallback could ever reach the application
default. And `/config` did not expose the default language, which the pre-login pages need.

## Decision

- **Library: `i18next` + `react-i18next`.** The de-facto standard for React: namespaces, plurals and
  interpolation built in, lazy-loadable catalogs, no compile step. FormatJS/`react-intl` was
  rejected for heavier ICU authoring; Lingui for its build-time extraction step in a Vite + pnpm
  pipeline that otherwise has none.
- **Typed keys.** `CustomTypeOptions` types every `t()` key against the English catalog, so a
  misspelt or removed key fails `tsc`. English is the source of truth; other catalogs mirror it.
- **Catalogs** live in `qnop-ui/src/i18n/<locale>/<namespace>.json`. English is bundled (it is the
  fallback for every missing key); every other language is a lazily loaded chunk
  (`import.meta.glob`), so the initial bundle does not grow per language. `SUPPORTED_LOCALES` is
  the single list switchers, resolution and MUI locales derive from.
- **Resolution order:** stored `preferred_language` → device choice (localStorage, set by the
  pre-login switcher) → `general.defaultLanguage` → `navigator.languages` → `en`. A step counts
  only if it names a shipped language, so a valid-but-unsupported code (e.g. `fr` for mail
  templates) falls through instead of leaving the UI untranslated. The device choice is never
  written to the profile automatically.
- **No-preference semantics in the backend.** `PREFERRED_LANGUAGE` defaults to `""` (as `TIMEZONE`
  does since #465). Defaults are computed on read, so no migration is needed; stored `"en"` rows
  stay explicit choices. Both language keys are validated by format (ISO 639-1, `^[a-z]{2}$`, or
  empty), not against the UI's list — mail templates may support more locales than the UI ships.
  `/config` exposes `general.defaultLanguage`.
- **One application point.** `LanguageSync` (mounted beside `TimezoneSync`) loads the catalogs,
  calls `i18next.changeLanguage`, sets `<html lang>`, and mirrors the locale into the UI store.
  `useFormatters()` threads that locale into every `Intl` formatter; MUI core and date-picker
  locale bundles and the `date-fns` adapter locale follow it.
- **German catalogs address the user informally ("du").**

## Consequences

- A user's language follows them across devices; anonymous visitors can still choose one on the
  sign-in pages, and the choice survives reloads on that device.
- English output is unchanged byte-for-byte: the `en` formatters keep their `en-GB` style.
- Adding a language is a catalog folder plus one entry in `SUPPORTED_LOCALES` (and the MUI/date-fns
  locale maps and the emoji picker's string map, all typed so a missing entry fails the build).
- Externalizing strings is incremental: the pipeline lands first, the pilot surfaces (sign-in,
  shell, profile) next, the rest in a follow-up sweep. Until then, untranslated surfaces stay
  English in every language.
- **Out of scope:** right-to-left layouts, translating user-generated content, and server error
  messages (to be mapped by their `code` in the sweep).

## Amendment — Spanish (2026-10-13, issue #849)

Spanish (`es`) is the third UI language. Its catalogs address the user informally ("tú"), use
neutral, international wording rather than Spain- or Latin-America-specific vocabulary, and format
dates and numbers as `es-ES`. No other part of this decision changes; the catalog parity test and
the application-setting catalog test cover the new language automatically.

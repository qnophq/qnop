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

- **Plurals:** every `_many` value mirrors its `_other` value by design. i18next resolves a missing
  plural category from the English fallback catalog, not from `_other`, so the parity test requires
  every CLDR plural category of each language.
- **E-mails** stay English for Spanish users until mail templates exist for `es`; the mail-template
  lookup falls back to `en`.
- **Glossary:** review → "revisión", annotation → "anotación"; resolved / dismissed / reopened →
  "resuelta" / "descartada" / "reabierta"; the review owner is "Responsable", the team lead role is
  "Responsable del equipo"; upload → "subir" / "subida"; an annotation's placement → "ubicación";
  user seats and team or quota slots → "plaza".

## Amendment — French (2026-10-13, issue #851)

French (`fr`) is the fourth UI language. Unlike the German and Spanish catalogs, it addresses the
user formally ("vous"): French business software does, and "tu" would read as unprofessional in an
enterprise tool. Wording is neutral (no Québec-specific vocabulary), formatting is `fr-FR`, and the
catalogs follow French typography — guillemets with no-break spaces, a narrow no-break space before
`; ? !` and a no-break space before `:`. French plurals have the CLDR categories one (which includes
0), many and other; the parity test requires all three, with `_many` mirroring `_other` by
design, and a typography test pins the French spacing rules.

- E-mail notifications and the digest stay English for French users until mail templates exist
  for `fr` (the template lookup falls back to `en`).
- Glossary: révision (f.), annotation, résolue / écartée / rouverte, Responsable (review owner) vs
  responsable d’équipe (team lead), téléverser / téléversement (upload), emplacement (placement),
  repositionner (moving an annotation) vs réancrer (automatic re-anchoring across versions),
  places (seats), Configuration du serveur.

## Amendment — Portuguese (2026-10-13, issue #854)

Portuguese is the fifth UI language, shipped in its **Brazilian** variant under the language code
`pt` (so `pt-BR`, `pt-PT` and plain `pt` browsers all resolve to it) with `pt-BR` formatting. The
catalogs address the user with "você", the usual friendly-but-neutral form in Brazilian software,
and prefer phrasings that do not gender the reader. Portuguese plurals, like French, put 0 in the
"one" category; the parity test requires one, many and other, with `_many` mirroring `_other`.
A count of 0 therefore reads in the singular ("0 dia"), which is correct Portuguese; the
Portuguese test pins it. E-mails stay English until mail templates are localized (#853).

- Glossary: revisão (f.), anotação, resolvida / descartada / reaberta, Responsável (review owner)
  vs Líder da equipe (team lead), enviar / envio (upload), posição (placement), reposicionar (free
  move) vs reassociar (choosing the passage again) vs reancorar (automatic re-anchoring across
  versions), ponto (a concern raised), vagas (seats), Configuração do servidor.

## Amendment — Localized mail (2026-10-13, issue #853)

Outgoing mail now follows the recipient's language too. There is no separate mail ADR, so the
decision is recorded here, beside the UI's.

- **Recipient language.** One resolver, `MailLocaleResolver`, used by every sender (review
  notifications, the daily digest, registration, password reset and the admin reset/setup link):
  the recipient's `preferred_language` → `general.default_language` → `en`, blank values skipped.
  It is the UI order without the device step, which a mail has no device for. As in the UI, a
  preference counts only if mail is shipped in that language, so one mail is never a mix of a
  fallback template and fallback phrases; the application default always counts, so an instance
  whose admins customised the templates in an unshipped default language keeps sending those.
  A brand-new account has no preference yet, so its verification mail uses the application
  default; the registration API carries no language (a later change may send the sign-up page's
  language).
- **One normalisation.** Every language value — a stored preference, the default-language
  setting, and the locale handed to any template-service entry point (render, preview, editor
  view, update, reset) — is reduced to its primary subtag, trimmed and lower-cased, as the UI's
  `toSupportedLocale` does: `de-AT`, `de_DE` and `DE` are all `de`. Rows are therefore stored and
  found under the same key.
- **One language per mail.** The sender resolves the language *before* it builds the template
  variables, and passes that language to the renderer, so the words around a variable and the
  variable agree. Review notifications carry it on the intent (`mailLocale`); the persisted inbox
  row keeps the raw decision and states, never the translated words.
- **Shipped defaults.** English stays authoritative in the `MailTemplateKey` enum, unchanged.
  Every other language is one classpath resource, `qnop-core/src/main/resources/mail/templates/
  <language>.yaml`, with `subject`, `bodyPlain`, `bodyHtmlContent`, `ctaLabel` and `preheader` per
  template key. YAML rather than JSON: block scalars keep the multi-line bodies and the HTML
  fragments readable for a translator, without escaped quotes. `MailTemplateDefaults` loads them
  at startup and fails the start on a bad file: an unknown or missing key or field, a placeholder
  outside the key's closed set, a call to action where English has none (or none where English
  has one), a field that does not compile and render as Mustache with the key's demo variables,
  or the same language shipped twice (the error names both resources; files are never merged).
  The resources are loaded from `classpath*:`, so an add-on may ship a language — a resource seam,
  catalogued in `docs/EXTENSION-POINTS.md`. No Liquibase seeding — released changesets stay
  untouched.
- **Resolution order** (render, preview, editor view): for the requested language, then the
  default language, then `en`, try the admin-edited `mail_template` row first and the shipped
  default second. So an admin row in the recipient's language wins, but an admin row in another
  language never beats the shipped default in the recipient's own language. English output for an
  English recipient is byte-identical to before (pinned by golden files). Where a row carries no
  HTML of its own, the branded HTML is built from the shipped default of the row's language (or
  English where that language ships none). The layout's chrome and its `lang` attribute follow the
  language the mail is rendered in; a language with rows but no phrase bundle gets its own `lang`
  with the English chrome wording.
- **Reachable languages.** Admin rows count only in a language the resolver can choose: a shipped
  language, or the application default. A row saved for a language that is neither shipped nor
  the default is never used until that language ships (Spanish, French and Portuguese follow in
  the second change) — sending it would mix admin text with English phrases.
- **Upgrade note — customised templates.** Before this change every recipient got the rows of the
  default language. Now an admin row in the default language no longer reaches a recipient whose
  own language is shipped: an installation that customised its English templates (wording, a
  legal footer, branding) sends German recipients the stock German defaults instead. This is
  intended — the recipient's language wins — but operators who customised templates should review
  them per language. The second change adds per-language editing to the admin template editor so
  each language can be customised; until then a German row can be stored through the admin API
  (`locale: de`).
- **Server-rendered phrases.** A small `java.util.ResourceBundle` per language,
  `mail/messages[_<language>].properties` (UTF-8, English root, no fallback to the JVM's default
  locale), holds the layout's footer, CTA fallback and link hint, the relative expiry time, the
  decision words, workflow state names, actor fallbacks, and the digest's counts, sentences,
  headings and date format. Plurals use `MessageFormat` choice formats; counted phrases print the
  count as plain digits so no language groups them. English values are the former literals.
- **Adding a mail language** is adding `mail/templates/<language>.yaml` and
  `mail/messages_<language>.properties` — no code. Parity tests require every shipped language to
  define every template, field and phrase, with the same placeholders as English part by part.
- **German** is the first mail language (informal "du", the UI's terminology, „…“ quotes). Spanish,
  French and Portuguese defaults and a per-language selector in the admin template editor follow
  in a second change; until then those recipients get the application default's mail (English
  unless configured otherwise), and the admin editor still edits the default language only.
- Out of scope, as in the issue: the server-generated pseudonyms of anonymous reviews
  ("Participant N"), and the in-app inbox's server-side fallbacks, which the UI renders itself.

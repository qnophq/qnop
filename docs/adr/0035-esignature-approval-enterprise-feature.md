# ADR-0035: E-signature approval as a post-finalization enterprise feature

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

qnop reviews legal/compliance documents; a natural commercial capability is a **legally-binding e-signature** for the *approval* of a released document — designated signers sign the finalized version. The question is how to integrate this (e.g. DocuSign) without compromising:

- the **AGPL core** (open-core boundary, [ADR-0002](0002-open-core-via-polyrepo-and-published-spi.md)/[ADR-0003](0003-agpl-boundary-is-the-spi.md)),
- qnop's **sovereignty / EU-data promise** ("On-Premises · data stays in the EU"), which a hard dependency on a single US SaaS would undermine,
- and the **review workflow** ([ADR-0011](0011-review-workflow-state-model.md)).

eIDAS defines escalating levels — SES → AES → QES (only QES is equivalent to a handwritten signature) — and the needed level varies by customer/use case.

## Decision

E-signature is an **enterprise-only feature, layered *after* content finalization**, with no signature-specific seam in the community core.

1. **The community workflow is unchanged.** `FINALIZED` ([ADR-0011](0011-review-workflow-state-model.md)) means *content* approval (zero open annotations, nothing pending) and knows nothing about signatures — **no `PENDING_SIGNATURE` state, no FINALIZED guard**. Content approval and legal approval are deliberately distinct.
2. **A separate, additive lifecycle on the finalized version.** The signature process lives entirely in the `qnop-enterprise` repo — its own tables, services, controllers, and FE routes via the existing enterprise seams (`@AutoConfiguration` + `@ConditionalOnMissingBean`; frontend separation [ADR-0014](0014-frontend-enterprise-separation.md)). It reads the community core **read-only**: the finalized `DocumentVersion`, its content hash, and the `ReviewParticipant`s.
3. **Bound to the version content-hash.** A signature attaches to `DocumentVersion.contentHash` ([ADR-0032](0032-document-representation-and-rendering-pipeline.md)) — an immutable SHA-256. A new version invalidates prior signatures, which is correct. PAdES (signature embedded in the PDF) fits the PDF-first model; the signed artifact is stored as a signed representation of the version.
4. **Provider-agnostic via an enterprise-internal `SignatureProvider` abstraction — *not* a community `qnop-spi` seam.** The community core has no signature concept, so the abstraction is enterprise-internal. **DocuSign is one connector**; EU-native eIDAS providers (Skribble, D-Trust/sign-me, Namirial, yousign) and a **local PAdES** path (certificate/HSM, no external service) are equal alternatives — this is what preserves the sovereignty promise and avoids single-vendor lock-in.
5. **Configurable eIDAS level.** Connectors declare `supportedLevels()` ∈ {SES, AES, QES}; an instance default plus per-request selection; the UI only offers what the active provider supports.
6. **Evidence into the audit trail.** Signer, time, signed hash, and certificate flow into the existing `AuditEvent` chain — the unbroken evidence trail legal validity requires.

## Consequences

- **The community core needs zero signature-specific work.** The feature is fully additive over the generic enterprise seams. The only core prerequisites already exist (the version content-hash, read access to the finalized version and participants) **except** the generic enterprise-Liquibase seam, which does not exist yet and is tracked in **#254** *(shipped since — see [ADR-0039](0039-enterprise-packaging-and-runtime-extensions.md) §2 / issue #254)* (needed before any enterprise schema lands; not a blocker for the PDF slice).
- DocuSign's proprietary SDK lives in `qnop-enterprise`, never in the AGPL core ([ADR-0007](0007-spdx-dco-license-scanning.md)).
- Sovereignty is preserved: customers choose an EU/on-prem-capable provider.
- "Legally binding" remains regulatorily deep — eIDAS levels, PAdES-LTV / long-term validation, Schrems-II data-transfer constraints, per-signature cost — a substantial enterprise module, deliberately out of community scope.

## Deferred (enterprise, later)

- The signature sub-workflow states and the connector set.
- **Behavior on signer decline:** content stays `FINALIZED`; only the legal approval fails. The owner likely starts a new version (→ back into review), but [ADR-0011](0011-review-workflow-state-model.md) has no re-open path from `FINALIZED` today — revisit if/when the enterprise flow needs it.
- Long-term validation / archival.

## Alternatives considered

- **Hardwire DocuSign in the core.** Rejected: a US-SaaS dependency contradicts the sovereignty/EU-data promise, and single-vendor lock-in narrows the market; a provider-agnostic abstraction is strictly better.
- **Signature as a guard on the community `FINALIZED` transition.** Rejected: couples a community-core state machine to an enterprise concern; a post-finalization separate lifecycle keeps the core clean and models "content vs. legal approval" honestly.
- **`SignatureProvider` as a published community `qnop-spi` seam** (alongside `StorageProvider`/`DocumentExtractor`/`Reviewer`). Rejected for now: community has no signature concept. Could be promoted to a published SPI later if a third-party connector ecosystem is wanted.

## Amendment (2026-07-01, issue #244 implementation)

The Decision framed signature as *strictly post-finalization* (point 1: "no `PENDING_SIGNATURE` state"). Implementing the domain schema (#244) deliberately keeps a **second placement option open**: `document.workflow_state` is persisted as an **extensible string with no closed `CHECK`** ([ADR-0011](0011-review-workflow-state-model.md) amendment), so an enterprise edition *may* model the signing step as an **in-workflow intermediate state** (e.g. `PENDING_SIGNATURE` between content approval and a legally-final state) via the extensible state machine (#246) — not only as the additive post-`FINALIZED` lifecycle described above.

This does not change the community core, which still ships no signature concept and no signature-specific seam. It records that the core no longer *forecloses* the in-workflow option: the choice between (a) a post-finalization additive lifecycle and (b) an in-workflow intermediate state is deferred to the enterprise signature module, and the `workflow_state` seam supports both. The rejected alternative "signature as a guard on the community `FINALIZED` transition" remains rejected — the extensibility lives in the (enterprise-pluggable) state machine, not as a hardcoded community-core guard.

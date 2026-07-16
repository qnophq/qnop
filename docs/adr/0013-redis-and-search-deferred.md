# ADR-0013: Redis & search index deferred

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

A collaborative review tool invites infrastructure (Redis for presence/real-time fan-out, OpenSearch for full-text search, a vector DB for AI semantic search). Adding these too early is over-engineering.

## Decision (direction)

Deliberately **defer** these until there is demonstrated need (YAGNI):

- **Redis** — not in the MVP. The first review flow uses HTTP + SSE/WebSocket without distributed state. Redis enters when real-time presence/live cursors, horizontal WebSocket fan-out, or an extraction cache are actually required. Provide a `PresenceService`/`MessageBroker` port (in-memory default) so the later switch is an adapter swap.
- **Full-text search** — start with **PostgreSQL FTS** (`tsvector` + GIN). Introduce OpenSearch only when ranking/faceting/corpus size exceed Postgres FTS.
- **Semantic/AI search** — if needed, the first step is **`pgvector`** in Postgres, not a separate vector database.

## Consequences

- Fewer moving parts in early phases; faster to operate and reason about.
- Ports are introduced where a later swap is likely, so deferral doesn't become a rewrite.

## Status note

This ADR exists to document intentional non-scope and guard against scope creep. Revisit per the triggers above.

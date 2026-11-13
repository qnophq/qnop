-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Wipes the tables seeded by seed.sql (issue #163) so every seeded integration
-- test starts from a known-empty slate and leaves the JVM-shared Testcontainers
-- database clean for non-seeded IT classes afterwards.
--
-- The migration-seeded tables (application_setting, mail_template) must survive
-- this script. That is why qnop_user is cleared with DELETE rather than being in
-- a TRUNCATE list: TRUNCATE ... CASCADE truncates every table holding an FK to
-- it — including application_setting (updated_by) — which silently wiped the
-- Liquibase-seeded defaults for any IT class running afterwards (surfaced by
-- #246's ReviewWorkflowControllerIT, which runs before SettingsSchemaIT). A
-- row-wise DELETE instead lets the FKs do their declared work: CASCADE clears
-- the per-user rows (tokens, avatars, oidc_identity), SET NULL preserves seeded
-- rows that merely reference a user.

-- Document-review aggregate first: document.owner_id is ON DELETE RESTRICT, so
-- these must be gone before the users are.
TRUNCATE TABLE annotation_placement, comment, annotation, audit_event,
               review_participant, document_version, document
               RESTART IDENTITY CASCADE;

-- The audit hash chain (issue #928) restarts with the emptied trail; TRUNCATE
-- bypasses the row triggers, so the head would otherwise still point at the
-- wiped rows and every later chain verification would report a break.
UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64) WHERE id = 1;

-- Anonymous-review pseudonyms (issue #914) carry no FK to document, so the
-- CASCADE above does not reach them.
TRUNCATE TABLE review_pseudonym;

-- Test-created data without migration seeds; CASCADE also clears anything a
-- test created that references these rows (e.g. team memberships).
TRUNCATE TABLE team_membership, team, oidc_provider, user_setting, application_asset
               RESTART IDENTITY CASCADE;

-- Scheduler-job operator state (issue #524): seeded at start-up and mutated by
-- the dashboard, so reset it per test for deterministic assertions. No FK deps.
TRUNCATE TABLE scheduler_job RESTART IDENTITY CASCADE;

DELETE FROM user_avatar;
DELETE FROM qnop_user;

-- Deleting a user writes a `user.erased` audit event (issue #952), so the
-- trail is emptied once more after the users, and the chain head with it.
-- When this script runs inside a transaction, those rows' deferred seal is
-- still pending and TRUNCATE would refuse ("pending trigger events"): fire
-- it now. Outside a transaction this is a harmless no-op.
SET CONSTRAINTS ALL IMMEDIATE;
TRUNCATE TABLE audit_event;
UPDATE audit_chain_head SET last_seq = 0, last_hash = repeat('0', 64) WHERE id = 1;

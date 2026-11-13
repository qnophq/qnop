-- Copyright (c) 2026-present devtank42 GmbH
--
-- This file is part of qnop (Qualified Notes on Papers).
--
-- qnop is free software: you can redistribute it and/or modify it under the
-- terms of the GNU Affero General Public License as published by the Free
-- Software Foundation, either version 3 of the License, or (at your option)
-- any later version.
--
-- qnop is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
-- FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
-- details.
--
-- You should have received a copy of the GNU Affero General Public License
-- along with qnop. If not, see <https://www.gnu.org/licenses/>.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Owner-only authorisation for the document cascade and its prune tombstones
-- (issue #959, ADR-0043 amendment). 0040 marked the documents being deleted,
-- and armed the tombstone insert, with transaction-local session settings
-- (qnop.audit_cascade_docs, qnop.audit_writing_tombstone) that any session can
-- set. Both now live in tables only the schema owner can write, as 0042 did for
-- the redaction context:
--
--   * audit_cascade_context (txid, document_id): written by the BEFORE DELETE
--     trigger on document (now SECURITY DEFINER), one row per document being
--     deleted. audit_event_cascade_sanctioned reads it through a read-only
--     SECURITY DEFINER helper; the delete guard and the tombstone writer both
--     go through that function. The tombstone writer — the statement trigger
--     on audit_event, which PostgreSQL fires once after all of a statement's
--     cascades, even when they removed no row — is the last reader, and spends
--     the markers of the documents whose rows it saw go. (A trigger on
--     document cannot do it: the cascade's statement trigger fires after
--     document's own.) Markers of documents that had no audit row are swept
--     once their transaction has ended.
--   * audit_prune_context (txid, event_detail): the one tombstone the insert
--     guard currently admits, written by audit_event_record_prune just before
--     its insert and removed right after. The guard admits an
--     `audit.chain.pruned` row only from inside a trigger and only as exactly
--     that announced event.
--
-- Both are keyed by txid_current(), which is the top-level transaction id even
-- inside a savepoint, and both are plain rows: a savepoint rolled back takes
-- its rows with it. The session settings are no longer read anywhere.
--
-- The SECURITY DEFINER trigger functions (here and from 0040/0042) are bound
-- to their one trigger and have EXECUTE revoked — see "binding" below.

-- --- owner-only state ------------------------------------------------------

CREATE TABLE audit_cascade_context (
  txid BIGINT NOT NULL,
  document_id UUID NOT NULL,
  PRIMARY KEY (txid, document_id)
);

CREATE TABLE audit_prune_context (
  txid BIGINT PRIMARY KEY,
  event_detail JSONB NOT NULL
);

-- Every privilege goes, including any a default-privileges rule granted to
-- the application role when the tables were created.
DO $$
DECLARE
  t TEXT;
  g TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY['audit_cascade_context', 'audit_prune_context'] LOOP
    EXECUTE format('REVOKE ALL ON %I FROM PUBLIC', t);
    FOR g IN
      SELECT DISTINCT grantee FROM information_schema.role_table_grants
       WHERE table_schema = current_schema()
         AND table_name = t
         AND grantee <> current_user
    LOOP
      EXECUTE format('REVOKE ALL ON %I FROM %I', t, g);
    END LOOP;
  END LOOP;
END;
$$;

-- Whether this transaction is deleting the document right now. Read-only, so
-- it stays executable by everyone.
CREATE FUNCTION audit_cascade_marked(p_document_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
VOLATILE
SECURITY DEFINER
AS $fn$
  SELECT EXISTS (
    SELECT 1 FROM audit_cascade_context
     WHERE txid = txid_current() AND document_id = p_document_id)
$fn$;

-- The tombstone detail this transaction's prune trigger is writing (NULL when
-- none). Read-only, so it stays executable by everyone.
CREATE FUNCTION audit_prune_context_current()
RETURNS JSONB
LANGUAGE sql
VOLATILE
SECURITY DEFINER
AS $fn$
  SELECT event_detail FROM audit_prune_context WHERE txid = txid_current()
$fn$;

-- --- the cascade marker ------------------------------------------------------

-- Same contract as in 0040, read from the owner-only marker. VOLATILE so the
-- marker written earlier in the statement is always seen.
CREATE OR REPLACE FUNCTION audit_event_cascade_sanctioned(p_scope TEXT, p_document_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
VOLATILE
AS $fn$
  SELECT p_scope = 'DOCUMENT'
     AND p_document_id IS NOT NULL
     AND audit_cascade_marked(p_document_id)
     -- Inside the referential action the parent row is already invisible.
     AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id = p_document_id)
$fn$;

-- BEFORE DELETE on document: marks the id before the referential action
-- removes the document's audit rows. Refuses to run as any other trigger (see
-- "binding" below). Also sweeps markers left by transactions that have ended:
-- a document whose cascade removed no audit row is never named by the
-- tombstone trigger, so its marker outlives the statement; it authorises
-- nothing (the id is gone or exists again, and txids never repeat), and the
-- sweep keeps the table small. SKIP LOCKED: concurrent deletes never wait on
-- each other's sweep.
CREATE OR REPLACE FUNCTION document_mark_audit_cascade()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
BEGIN
  IF TG_RELID IS DISTINCT FROM 'document'::regclass
     OR TG_WHEN <> 'BEFORE' OR TG_LEVEL <> 'ROW' OR TG_OP <> 'DELETE' THEN
    RAISE EXCEPTION 'document_mark_audit_cascade runs only as the BEFORE DELETE row trigger on document'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  DELETE FROM audit_cascade_context
   WHERE ctid IN (
     SELECT ctid FROM audit_cascade_context
      WHERE txid < txid_snapshot_xmin(txid_current_snapshot())
      FOR UPDATE SKIP LOCKED);
  INSERT INTO audit_cascade_context (txid, document_id)
  VALUES (txid_current(), OLD.id)
  ON CONFLICT DO NOTHING;
  RETURN OLD;
END;
$fn$;

-- --- the tombstone -----------------------------------------------------------

-- As in 0040, but the tombstone is announced in audit_prune_context instead of
-- a session setting, and the detail is computed once and inserted verbatim.
CREATE OR REPLACE FUNCTION audit_event_record_prune()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_detail JSONB;
BEGIN
  IF TG_RELID IS DISTINCT FROM 'audit_event'::regclass
     OR TG_WHEN <> 'AFTER' OR TG_LEVEL <> 'STATEMENT' OR TG_OP <> 'DELETE' THEN
    RAISE EXCEPTION 'audit_event_record_prune runs only as the AFTER DELETE statement trigger on audit_event'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF EXISTS (
    SELECT 1 FROM removed r
    WHERE NOT coalesce(audit_event_cascade_sanctioned(r.scope, r.document_id), false)) THEN
    RAISE EXCEPTION 'audit_event is append-only: rows are removed only with their document'
      USING ERRCODE = 'insufficient_privilege';
  END IF;

  SELECT jsonb_build_object(
           'documentIds', (SELECT jsonb_agg(DISTINCT r.document_id)
                             FROM removed r WHERE r.chain_seq IS NOT NULL),
           'ranges', jsonb_agg(
             jsonb_build_object(
               'fromSeq', g.from_seq, 'toSeq', g.to_seq,
               'prevHash', g.prev_hash, 'lastHash', g.last_hash)
             ORDER BY g.from_seq))
    INTO v_detail
  FROM (
    SELECT min(chain_seq) AS from_seq,
           max(chain_seq) AS to_seq,
           (array_agg(prev_hash ORDER BY chain_seq))[1] AS prev_hash,
           (array_agg(row_hash ORDER BY chain_seq DESC))[1] AS last_hash
    FROM (
      SELECT chain_seq, prev_hash, row_hash,
             chain_seq - row_number() OVER (ORDER BY chain_seq) AS island
      FROM removed
      WHERE chain_seq IS NOT NULL
    ) runs
    GROUP BY island
  ) g
  HAVING count(*) > 0;

  -- The cascades of the documents whose rows are in the transition table have
  -- run in full (a document's rows go in one referential action), so their
  -- markers are spent. Markers of documents with no removed row are left to
  -- the sweep in document_mark_audit_cascade: removing them here could spend
  -- the marker of a document whose cascade is still queued.
  DELETE FROM audit_cascade_context c
   WHERE c.txid = txid_current()
     AND c.document_id IN (SELECT r.document_id FROM removed r WHERE r.document_id IS NOT NULL);

  IF v_detail IS NULL THEN
    RETURN NULL;
  END IF;

  INSERT INTO audit_prune_context (txid, event_detail)
  VALUES (txid_current(), v_detail)
  ON CONFLICT (txid) DO UPDATE SET event_detail = EXCLUDED.event_detail;
  INSERT INTO audit_event (id, scope, document_id, event_type, actor_id, detail, created_at)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'audit.chain.pruned', NULL, v_detail,
          clock_timestamp());
  DELETE FROM audit_prune_context WHERE txid = txid_current();
  RETURN NULL;
END;
$fn$;

-- --- insert guard --------------------------------------------------------------

-- The 0042 guard, with the tombstone check moved from the session setting to
-- the announced event: an `audit.chain.pruned` row is admitted only from
-- inside a trigger and only as exactly the event audit_prune_context holds.
CREATE OR REPLACE FUNCTION audit_event_guard_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
DECLARE
  ctx audit_redaction_context;
  v_prune JSONB;
BEGIN
  IF NEW.chain_seq IS NOT NULL OR NEW.prev_hash IS NOT NULL OR NEW.row_hash IS NOT NULL THEN
    RAISE EXCEPTION 'audit_event chain columns are assigned by the database at commit'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.redacted_at IS NOT NULL OR NEW.redaction_id IS NOT NULL OR NEW.actor_pseudonym IS NOT NULL THEN
    RAISE EXCEPTION 'audit_event redaction columns are assigned by the database'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.event_type = 'audit.chain.pruned' THEN
    IF pg_trigger_depth() > 1 THEN
      v_prune := audit_prune_context_current();
    END IF;
    IF v_prune IS NULL
       OR NEW.scope <> 'SYSTEM'
       OR NEW.document_id IS NOT NULL
       OR NEW.actor_id IS NOT NULL
       OR NEW.detail IS DISTINCT FROM v_prune THEN
      RAISE EXCEPTION 'audit.chain.pruned events are written only by the database'
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  END IF;
  IF NEW.event_type IN ('audit.chain.redacted', 'user.erased') THEN
    IF pg_trigger_depth() > 1 THEN
      ctx := audit_redaction_context_current();
    END IF;
    IF ctx.txid IS NULL
       OR ctx.phase <> 'record'
       OR NEW.event_type IS DISTINCT FROM ctx.event_type
       OR NEW.scope <> 'SYSTEM'
       OR NEW.document_id IS NOT NULL
       OR NEW.actor_id IS DISTINCT FROM ctx.event_actor
       OR NEW.detail IS DISTINCT FROM ctx.event_detail THEN
      RAISE EXCEPTION '% events are written only by the database', NEW.event_type
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  END IF;
  RETURN NEW;
END;
$fn$;

-- --- binding the SECURITY DEFINER trigger functions ---------------------------

-- A trigger function running with the owner's rights must not be attachable
-- to a table of someone else's choosing: PostgreSQL checks EXECUTE when a
-- trigger is created, not when it fires, so revoking EXECUTE from PUBLIC (and
-- from anyone granted it) leaves the owner-created triggers working and keeps
-- everyone else from creating new ones — on a temporary table, say. As a
-- second barrier each function refuses to run as anything but its intended
-- trigger. Covers the definer trigger functions of 0040 and 0042 too; the 0042
-- redaction function itself (audit_event_redact_user) already had EXECUTE
-- revoked from PUBLIC and is swept for other grantees here.

-- The 0042 seal, bound to the deferred AFTER INSERT row trigger on audit_event.
CREATE OR REPLACE FUNCTION audit_event_seal()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  r RECORD;
  v_seq BIGINT;
  v_prev VARCHAR(64);
  v_hash VARCHAR(64);
BEGIN
  IF TG_RELID IS DISTINCT FROM 'audit_event'::regclass
     OR TG_WHEN <> 'AFTER' OR TG_LEVEL <> 'ROW' OR TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'audit_event_seal runs only as the AFTER INSERT row trigger on audit_event'
      USING ERRCODE = 'insufficient_privilege';
  END IF;

  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'audit events must be written in READ COMMITTED transactions (found %)',
      current_setting('transaction_isolation')
      USING ERRCODE = 'invalid_transaction_state';
  END IF;

  -- The row may already be gone again (inserted and cascade-deleted in the
  -- same transaction); then there is nothing to seal.
  SELECT id, scope, document_id, event_type, actor_id, detail, created_at INTO r
  FROM audit_event WHERE id = NEW.id AND chain_seq IS NULL;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  SELECT last_seq, last_hash INTO v_seq, v_prev
  FROM audit_chain_head WHERE id = 1 FOR UPDATE;

  v_hash := encode(
    sha256(convert_to(v_prev || audit_event_canonical(
      r.id, r.scope, r.document_id, r.event_type, r.actor_id, r.detail, r.created_at), 'UTF8')),
    'hex');

  UPDATE audit_event
     SET chain_seq = v_seq + 1, prev_hash = v_prev, row_hash = v_hash
   WHERE id = NEW.id;
  UPDATE audit_chain_head SET last_seq = v_seq + 1, last_hash = v_hash WHERE id = 1;
  RETURN NULL;
END;
$fn$;

-- The 0042 user-erasure trigger, bound to the BEFORE DELETE row trigger on
-- qnop_user.
CREATE OR REPLACE FUNCTION qnop_user_redact_audit()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_actor UUID;
BEGIN
  IF TG_RELID IS DISTINCT FROM 'qnop_user'::regclass
     OR TG_WHEN <> 'BEFORE' OR TG_LEVEL <> 'ROW' OR TG_OP <> 'DELETE' THEN
    RAISE EXCEPTION 'qnop_user_redact_audit runs only as the BEFORE DELETE row trigger on qnop_user'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  BEGIN
    v_actor := nullif(current_setting('qnop.erasing_actor', true), '')::uuid;
  EXCEPTION WHEN invalid_text_representation THEN
    v_actor := NULL;
  END;
  PERFORM set_config('qnop.erasing_actor', '', true);
  IF v_actor = OLD.id OR NOT EXISTS (SELECT 1 FROM qnop_user u WHERE u.id = v_actor) THEN
    v_actor := NULL;
  END IF;
  PERFORM audit_event_redact_user(OLD.id, v_actor);
  RETURN OLD;
END;
$fn$;

DO $$
DECLARE
  f TEXT;
  g TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_event_seal()',
    'document_mark_audit_cascade()',
    'audit_event_record_prune()',
    'qnop_user_redact_audit()',
    'audit_event_redact_user(uuid, uuid)']
  LOOP
    EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC', f);
    FOR g IN
      SELECT DISTINCT a.grantee::regrole::text
        FROM pg_proc p, aclexplode(p.proacl) a
       WHERE p.oid = f::regprocedure
         AND a.grantee <> 0
         AND a.grantee <> p.proowner
    LOOP
      EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM %s', f, g);
    END LOOP;
  END LOOP;
END;
$$;

-- --- search_path ---------------------------------------------------------------

-- CREATE OR REPLACE drops the per-function settings, so every function this
-- changeset (re)defines pins its search_path again.
DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_cascade_marked(uuid)',
    'audit_prune_context_current()',
    'audit_event_cascade_sanctioned(text, uuid)',
    'document_mark_audit_cascade()',
    'audit_event_record_prune()',
    'audit_event_guard_insert()',
    'audit_event_seal()',
    'qnop_user_redact_audit()']
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = %I, pg_temp', f, current_schema());
  END LOOP;
END;
$$;

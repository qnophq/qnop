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
--     the markers of every document that is gone. (A trigger on document
--     cannot do it: the cascade's statement trigger fires after document's
--     own.) A document recreated later in the transaction is therefore marked
--     afresh when it is deleted again.
--   * audit_prune_context (txid, event_detail): the one tombstone the insert
--     guard currently admits, written by audit_event_record_prune just before
--     its insert and removed right after. The guard admits an
--     `audit.chain.pruned` row only from inside a trigger and only as exactly
--     that announced event.
--
-- Both are keyed by txid_current(), which is the top-level transaction id even
-- inside a savepoint, and both are plain rows: a savepoint rolled back takes
-- its rows with it, and nothing survives the transaction in normal operation.
-- The session settings are no longer read anywhere.

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
-- removes the document's audit rows.
CREATE OR REPLACE FUNCTION document_mark_audit_cascade()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
BEGIN
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

  -- The cascade is done: this trigger is the last thing to read the markers
  -- of the documents it has seen go (statement triggers of a referential
  -- action fire after the outer statement's own, once for all its cascades,
  -- even when no row was removed). Spend every marker whose document is gone.
  DELETE FROM audit_cascade_context c
   WHERE c.txid = txid_current()
     AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id = c.document_id);

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
    'audit_event_guard_insert()']
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = %I, pg_temp', f, current_schema());
  END LOOP;
END;
$$;

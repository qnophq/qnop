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

-- Rollback of 0043-audit-prune-context (issue #959): restores the 0040 cascade
-- marker, sanction check and tombstone writer, and the 0042 insert guard —
-- i.e. the session-setting authorisation this changeset replaced — and drops
-- the owner-only state. The 0042 seal and user-erasure trigger functions
-- return to their 0042 bodies, without the trigger-binding check.
--
-- The EXECUTE revokes on the SECURITY DEFINER trigger functions stay in
-- place: they break nothing (triggers fire regardless of EXECUTE), and
-- restoring PUBLIC's EXECUTE would only reopen a hole.

CREATE OR REPLACE FUNCTION audit_event_cascade_sanctioned(p_scope TEXT, p_document_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $fn$
  SELECT p_scope = 'DOCUMENT'
     AND p_document_id IS NOT NULL
     AND p_document_id::text = ANY (string_to_array(
           coalesce(current_setting('qnop.audit_cascade_docs', true), ''), ','))
     -- Inside the referential action the parent row is already invisible.
     AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id = p_document_id)
$fn$;

CREATE OR REPLACE FUNCTION document_mark_audit_cascade()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
AS $fn$
BEGIN
  PERFORM set_config(
    'qnop.audit_cascade_docs',
    coalesce(nullif(current_setting('qnop.audit_cascade_docs', true), '') || ',', '')
      || OLD.id::text,
    true);
  RETURN OLD;
END;
$fn$;

CREATE OR REPLACE FUNCTION audit_event_record_prune()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
BEGIN
  IF EXISTS (
    SELECT 1 FROM removed r
    WHERE NOT coalesce(audit_event_cascade_sanctioned(r.scope, r.document_id), false)) THEN
    RAISE EXCEPTION 'audit_event is append-only: rows are removed only with their document'
      USING ERRCODE = 'insufficient_privilege';
  END IF;

  PERFORM set_config('qnop.audit_writing_tombstone', 'on', true);
  INSERT INTO audit_event (id, scope, document_id, event_type, actor_id, detail, created_at)
  SELECT gen_random_uuid(), 'SYSTEM', NULL, 'audit.chain.pruned', NULL,
         jsonb_build_object(
           'documentIds', (SELECT jsonb_agg(DISTINCT r.document_id)
                             FROM removed r WHERE r.chain_seq IS NOT NULL),
           'ranges', jsonb_agg(
             jsonb_build_object(
               'fromSeq', g.from_seq, 'toSeq', g.to_seq,
               'prevHash', g.prev_hash, 'lastHash', g.last_hash)
             ORDER BY g.from_seq)),
         clock_timestamp()
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
  PERFORM set_config('qnop.audit_writing_tombstone', 'off', true);
  RETURN NULL;
END;
$fn$;

CREATE OR REPLACE FUNCTION audit_event_guard_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
DECLARE
  ctx audit_redaction_context;
BEGIN
  IF NEW.chain_seq IS NOT NULL OR NEW.prev_hash IS NOT NULL OR NEW.row_hash IS NOT NULL THEN
    RAISE EXCEPTION 'audit_event chain columns are assigned by the database at commit'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.redacted_at IS NOT NULL OR NEW.redaction_id IS NOT NULL OR NEW.actor_pseudonym IS NOT NULL THEN
    RAISE EXCEPTION 'audit_event redaction columns are assigned by the database'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.event_type = 'audit.chain.pruned'
     AND (pg_trigger_depth() < 2
          OR coalesce(current_setting('qnop.audit_writing_tombstone', true), '') <> 'on') THEN
    RAISE EXCEPTION 'audit.chain.pruned events are written only by the database'
      USING ERRCODE = 'insufficient_privilege';
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

CREATE OR REPLACE FUNCTION qnop_user_redact_audit()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_actor UUID;
BEGIN
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

DROP FUNCTION IF EXISTS audit_prune_context_current();
DROP FUNCTION IF EXISTS audit_cascade_marked(uuid);
DROP TABLE IF EXISTS audit_prune_context;
DROP TABLE IF EXISTS audit_cascade_context;

DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
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

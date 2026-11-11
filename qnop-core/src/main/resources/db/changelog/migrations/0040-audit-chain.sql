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

-- Tamper evidence and resistance for the audit trail (issue #928, ADR-0043
-- amendment). Every committed audit_event row is sealed into one global hash
-- chain:
--
--   row_hash = hex(sha256(utf8(prev_hash || canonical(row))))
--
-- where prev_hash is the previous sealed row's row_hash (64 zeros for the
-- first) and canonical(row) is a length-prefixed encoding of the row's
-- content. AuditChainHasher (Java) re-implements the canonical form
-- independently; the verification job compares the two, so a redefined
-- function here cannot silently weaken the check.
--
-- Sealing happens in a DEFERRED constraint trigger, i.e. at COMMIT, under a
-- row lock on the single-row audit_chain_head table. The lock serialises chain
-- order across every application instance, and because it is taken only at
-- the very end of a transaction it is held for the commit alone: no business
-- statement runs after it, so it cannot take part in a lock-order deadlock.
--
-- Resistance where it is cheap: rows can never be UPDATEd (only the seal
-- itself may fill in the chain columns, once) and never DELETEd directly — the
-- one sanctioned delete path is the ON DELETE CASCADE from document. A
-- BEFORE DELETE trigger on document marks, transaction-locally, which
-- document ids are being deleted; only DOCUMENT rows of a marked document may
-- go. The cascade is recorded by a statement trigger as an
-- `audit.chain.pruned` SYSTEM event carrying the document ids and the removed
-- ranges' boundary hashes, so the verifier can tell a sanctioned purge from a
-- hole. The statement trigger re-checks the marker itself, so disabling the
-- row guard alone does not open a delete path, and disabling the tombstone
-- writer alone leaves a gap the verifier reports. New rows cannot arrive
-- pre-sealed, and tombstones can only be written by the tombstone trigger.

-- --- preconditions --------------------------------------------------------

-- extract(epoch) returns exact numeric only from PostgreSQL 14 on; earlier
-- versions would hash rounded timestamps.
DO $$
BEGIN
  IF current_setting('server_version_num')::int < 140000 THEN
    RAISE EXCEPTION 'qnop requires PostgreSQL 14 or newer (found %)', current_setting('server_version');
  END IF;
END;
$$;

-- --- schema ---------------------------------------------------------------

ALTER TABLE audit_event
  ADD COLUMN chain_seq BIGINT,
  ADD COLUMN prev_hash VARCHAR(64),
  ADD COLUMN row_hash VARCHAR(64);

ALTER TABLE audit_event ADD CONSTRAINT ux_audit_event_chain_seq UNIQUE (chain_seq);
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_chain_seq
  CHECK (chain_seq IS NULL OR chain_seq >= 1);

-- One row, ever: the position and hash of the newest sealed event.
CREATE TABLE audit_chain_head (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  last_seq BIGINT NOT NULL,
  last_hash VARCHAR(64) NOT NULL
);

INSERT INTO audit_chain_head (id, last_seq, last_hash)
VALUES (1, 0, repeat('0', 64));

-- --- canonical form + hash ------------------------------------------------

-- Length-prefixed fields ("<utf8 byte length>:<text>", "~" for null) in a
-- fixed order behind a format version. Unambiguous without separators, and
-- independent of session settings: uuid::text is canonical lower-case,
-- jsonb::text is the normalised jsonb rendering, and the timestamp is epoch
-- microseconds (extract() returns exact numeric on PostgreSQL 14+).
CREATE FUNCTION audit_event_canonical(
  p_id UUID,
  p_scope TEXT,
  p_document_id UUID,
  p_event_type TEXT,
  p_actor_id UUID,
  p_detail JSONB,
  p_created_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $fn$
  SELECT string_agg(
           CASE WHEN f IS NULL THEN '~' ELSE octet_length(convert_to(f, 'UTF8')) || ':' || f END,
           '' ORDER BY ord)
  FROM unnest(ARRAY[
         '1',
         p_id::text,
         p_scope,
         p_document_id::text,
         p_event_type,
         p_actor_id::text,
         p_detail::text,
         (extract(epoch FROM p_created_at) * 1000000)::bigint::text
       ]) WITH ORDINALITY AS t(f, ord)
$fn$;

-- --- seal (deferred, at commit) -------------------------------------------

CREATE FUNCTION audit_event_seal()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_seq BIGINT;
  v_prev VARCHAR(64);
  v_hash VARCHAR(64);
BEGIN
  -- The head lock serialises chain order only under READ COMMITTED; under a
  -- snapshot isolation a concurrent seal would surface as a serialisation
  -- failure at commit. Fail with a clear message instead.
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'audit events must be written in READ COMMITTED transactions (found %)',
      current_setting('transaction_isolation')
      USING ERRCODE = 'invalid_transaction_state';
  END IF;

  -- The row may already be gone again (inserted and cascade-deleted in the
  -- same transaction); then there is nothing to seal.
  PERFORM 1 FROM audit_event WHERE id = NEW.id AND chain_seq IS NULL;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  SELECT last_seq, last_hash INTO v_seq, v_prev
  FROM audit_chain_head WHERE id = 1 FOR UPDATE;

  v_hash := encode(
    sha256(convert_to(v_prev || audit_event_canonical(
      NEW.id, NEW.scope, NEW.document_id, NEW.event_type, NEW.actor_id,
      NEW.detail, NEW.created_at), 'UTF8')),
    'hex');

  UPDATE audit_event
     SET chain_seq = v_seq + 1, prev_hash = v_prev, row_hash = v_hash
   WHERE id = NEW.id;
  UPDATE audit_chain_head SET last_seq = v_seq + 1, last_hash = v_hash WHERE id = 1;
  RETURN NULL;
END;
$fn$;

-- --- guards ---------------------------------------------------------------

-- The only permitted UPDATE is the seal filling in the empty chain columns of
-- an otherwise unchanged row, from inside audit_event_seal (trigger depth > 1).
CREATE FUNCTION audit_event_reject_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
BEGIN
  IF pg_trigger_depth() > 1
     AND OLD.chain_seq IS NULL AND NEW.chain_seq IS NOT NULL
     AND NEW.id = OLD.id
     AND NEW.scope IS NOT DISTINCT FROM OLD.scope
     AND NEW.document_id IS NOT DISTINCT FROM OLD.document_id
     AND NEW.event_type IS NOT DISTINCT FROM OLD.event_type
     AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
     AND NEW.detail IS NOT DISTINCT FROM OLD.detail
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'audit_event is append-only: rows cannot be updated'
    USING ERRCODE = 'insufficient_privilege';
END;
$fn$;

-- Whether a row being deleted belongs to a document whose deletion this
-- transaction announced (document_mark_audit_cascade) and which is gone.
-- Only DOCUMENT rows qualify: SYSTEM rows have no document and never leave.
CREATE FUNCTION audit_event_cascade_sanctioned(p_scope TEXT, p_document_id UUID)
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

-- BEFORE DELETE on document: records the id in a transaction-local marker
-- before the referential action removes the document's audit rows.
CREATE FUNCTION document_mark_audit_cascade()
RETURNS TRIGGER
LANGUAGE plpgsql
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

-- A row may only go through the document cascade: issued from inside a
-- trigger (the referential action), for a DOCUMENT row whose document this
-- transaction is deleting. Everything else is rejected.
CREATE FUNCTION audit_event_reject_direct_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
BEGIN
  IF pg_trigger_depth() > 1
     AND audit_event_cascade_sanctioned(OLD.scope, OLD.document_id) THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'audit_event is append-only: rows are removed only with their document'
    USING ERRCODE = 'insufficient_privilege';
END;
$fn$;

-- New rows arrive unsealed (the seal assigns the chain columns), and the
-- tombstone event type is reserved for audit_event_record_prune.
CREATE FUNCTION audit_event_guard_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
BEGIN
  IF NEW.chain_seq IS NOT NULL OR NEW.prev_hash IS NOT NULL OR NEW.row_hash IS NOT NULL THEN
    RAISE EXCEPTION 'audit_event chain columns are assigned by the database at commit'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.event_type = 'audit.chain.pruned'
     AND (pg_trigger_depth() < 2
          OR coalesce(current_setting('qnop.audit_writing_tombstone', true), '') <> 'on') THEN
    RAISE EXCEPTION 'audit.chain.pruned events are written only by the database'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END;
$fn$;

-- --- tombstones for sanctioned (cascade) deletes ----------------------------

-- One `audit.chain.pruned` SYSTEM event per deleting statement, listing the
-- documents and every maximal run of consecutive removed chain positions with
-- the hash that led into it (prevHash) and the hash it ended on (lastHash).
-- The verifier bridges a hole only when such a range covers it exactly and
-- links up on both sides, and only for documents whose deletion the trail
-- explains. The sanction check is repeated here so that this trigger and the
-- row guard each refuse an unsanctioned delete on their own.
CREATE FUNCTION audit_event_record_prune()
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

-- --- backfill ---------------------------------------------------------------

-- Seal every pre-existing row once, oldest first. Runs before the guards
-- exist, so the plain UPDATE is allowed; the changeset runs exactly once.
DO $$
DECLARE
  r RECORD;
  v_seq BIGINT;
  v_prev VARCHAR(64);
BEGIN
  SELECT last_seq, last_hash INTO v_seq, v_prev FROM audit_chain_head WHERE id = 1;
  FOR r IN
    SELECT id, scope, document_id, event_type, actor_id, detail, created_at
    FROM audit_event
    WHERE chain_seq IS NULL
    ORDER BY created_at, id
  LOOP
    v_seq := v_seq + 1;
    UPDATE audit_event
       SET chain_seq = v_seq,
           prev_hash = v_prev,
           row_hash = encode(sha256(convert_to(v_prev || audit_event_canonical(
             r.id, r.scope, r.document_id, r.event_type, r.actor_id, r.detail,
             r.created_at), 'UTF8')), 'hex')
     WHERE id = r.id
     RETURNING row_hash INTO v_prev;
  END LOOP;
  UPDATE audit_chain_head SET last_seq = v_seq, last_hash = v_prev WHERE id = 1;
END;
$$;

-- --- search_path ----------------------------------------------------------

-- Every function resolves unqualified names in the schema it was created in,
-- with pg_temp explicitly last, so no session-local object can shadow a
-- table inside a trigger (essential for the SECURITY DEFINER ones).
DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_event_canonical(uuid, text, uuid, text, uuid, jsonb, timestamptz)',
    'audit_event_seal()',
    'audit_event_reject_update()',
    'audit_event_cascade_sanctioned(text, uuid)',
    'document_mark_audit_cascade()',
    'audit_event_reject_direct_delete()',
    'audit_event_guard_insert()',
    'audit_event_record_prune()']
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = %I, pg_temp', f, current_schema());
  END LOOP;
END;
$$;

-- --- triggers -------------------------------------------------------------

CREATE CONSTRAINT TRIGGER trg_audit_event_seal
  AFTER INSERT ON audit_event
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION audit_event_seal();

CREATE TRIGGER trg_audit_event_no_update
  BEFORE UPDATE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_reject_update();

CREATE TRIGGER trg_audit_event_no_direct_delete
  BEFORE DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_reject_direct_delete();

CREATE TRIGGER trg_audit_event_record_prune
  AFTER DELETE ON audit_event
  REFERENCING OLD TABLE AS removed
  FOR EACH STATEMENT EXECUTE FUNCTION audit_event_record_prune();

CREATE TRIGGER trg_audit_event_guard_insert
  BEFORE INSERT ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_guard_insert();

CREATE TRIGGER trg_document_mark_audit_cascade
  BEFORE DELETE ON document
  FOR EACH ROW EXECUTE FUNCTION document_mark_audit_cascade();

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

-- Rollback of 0042-audit-redaction (issue #952): removes the user-erasure path
-- and restores the 0040 versions of the seal and the guards. Rows already
-- redacted keep their pseudonymised content; their chain columns never
-- changed, but a verifier without the redaction logic reports them.

DROP TRIGGER IF EXISTS trg_qnop_user_redact_audit ON qnop_user;
DROP FUNCTION IF EXISTS qnop_user_redact_audit();
DROP FUNCTION IF EXISTS audit_event_redact_user(uuid, text[]);
DROP FUNCTION IF EXISTS audit_event_redact_detail(jsonb, text, text[], text);

CREATE OR REPLACE FUNCTION audit_event_seal()
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

CREATE OR REPLACE FUNCTION audit_event_reject_update()
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

CREATE OR REPLACE FUNCTION audit_event_guard_insert()
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

DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_event_seal()',
    'audit_event_reject_update()',
    'audit_event_guard_insert()']
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = %I, pg_temp', f, current_schema());
  END LOOP;
END;
$$;

ALTER TABLE audit_event DROP CONSTRAINT IF EXISTS ck_audit_event_redaction;
ALTER TABLE audit_event
  DROP COLUMN IF EXISTS actor_pseudonym,
  DROP COLUMN IF EXISTS redaction_id,
  DROP COLUMN IF EXISTS redacted_at;

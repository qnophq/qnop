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

-- Erasure of users the audit trail names (issue #952, ADR-0043 amendment).
--
-- Deleting a user pseudonymises every audit row that references them — as
-- actor, or by id inside the detail — instead of being blocked by the actor
-- foreign key. The redaction is the ONE sanctioned way the content of an audit
-- row may change:
--
--   * a BEFORE DELETE trigger on qnop_user runs audit_event_redact_user for
--     the user being deleted, so a user can only be erased from the trail by
--     deleting the account, and every account deletion erases;
--   * the function arms a transaction-local marker holding a fresh redaction
--     id; the update guard lets content change only under that marker, only
--     to that redaction id, and never touches the chain columns, the event
--     type, scope, document or time;
--   * actor_id becomes NULL (the pseudonym goes to actor_pseudonym), and the
--     user's id, username, display name and e-mail inside the detail become
--     the pseudonym 'erased-user:<random uuid>' — one per erasure, not
--     derivable from the user;
--   * chain_seq, prev_hash and row_hash stay as they were, so the chain still
--     links; the content of a redacted row is no longer verifiable, only its
--     existence and position. A DB-authored `audit.chain.redacted` SYSTEM event
--     lists every redacted position with its row_hash (and nothing personal),
--     and the verifier accepts a redacted row only against that listing.
--
-- fk_audit_event_actor stays ON DELETE RESTRICT: the trigger nulls the actor
-- before the foreign key is checked, and SET NULL would be a second, silent
-- write path that the update guard would (rightly) reject anyway.

-- --- schema ---------------------------------------------------------------

ALTER TABLE audit_event
  ADD COLUMN redacted_at TIMESTAMPTZ,
  ADD COLUMN redaction_id UUID,
  ADD COLUMN actor_pseudonym VARCHAR(64);

ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_redaction
  CHECK ((redacted_at IS NULL) = (redaction_id IS NULL)
         AND (actor_pseudonym IS NULL OR (redaction_id IS NOT NULL AND actor_id IS NULL)));

-- --- seal: hash what is stored at commit -----------------------------------

-- Re-created so the seal hashes the row as it is at COMMIT rather than as it
-- was inserted: a row redacted in the transaction that wrote it is sealed with
-- its redacted content (and verifies normally). Identical for every other row.
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

-- --- update guard ---------------------------------------------------------

-- Two updates are permitted, nothing else:
--   1. the seal filling in the empty chain columns of an otherwise unchanged
--      row, from inside audit_event_seal (trigger depth > 1);
--   2. a redaction: under the marker armed by audit_event_redact_user, setting
--      the row's redaction id to exactly the marker's, with the chain columns,
--      id, scope, document, event type and time unchanged and the actor either
--      unchanged or cleared.
CREATE OR REPLACE FUNCTION audit_event_reject_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
DECLARE
  v_marker TEXT := coalesce(current_setting('qnop.audit_redaction', true), '');
BEGIN
  IF NEW.id = OLD.id
     AND NEW.scope IS NOT DISTINCT FROM OLD.scope
     AND NEW.document_id IS NOT DISTINCT FROM OLD.document_id
     AND NEW.event_type IS NOT DISTINCT FROM OLD.event_type
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN

    IF pg_trigger_depth() > 1
       AND OLD.chain_seq IS NULL AND NEW.chain_seq IS NOT NULL
       AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
       AND NEW.detail IS NOT DISTINCT FROM OLD.detail
       AND NEW.redacted_at IS NOT DISTINCT FROM OLD.redacted_at
       AND NEW.redaction_id IS NOT DISTINCT FROM OLD.redaction_id
       AND NEW.actor_pseudonym IS NOT DISTINCT FROM OLD.actor_pseudonym THEN
      RETURN NEW;
    END IF;

    IF v_marker <> ''
       AND NEW.redaction_id IS NOT NULL
       AND NEW.redaction_id::text = v_marker
       AND NEW.redacted_at IS NOT NULL
       AND NEW.chain_seq IS NOT DISTINCT FROM OLD.chain_seq
       AND NEW.prev_hash IS NOT DISTINCT FROM OLD.prev_hash
       AND NEW.row_hash IS NOT DISTINCT FROM OLD.row_hash
       AND (NEW.actor_id IS NULL OR NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id) THEN
      RETURN NEW;
    END IF;
  END IF;
  RAISE EXCEPTION 'audit_event is append-only: rows cannot be updated'
    USING ERRCODE = 'insufficient_privilege';
END;
$fn$;

-- --- insert guard ---------------------------------------------------------

-- As before (unsealed rows only, tombstones only from the prune trigger), and
-- additionally: new rows arrive unredacted, and `audit.chain.redacted` events
-- are written only by audit_event_redact_user for the redaction it is running.
CREATE OR REPLACE FUNCTION audit_event_guard_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
DECLARE
  v_writing TEXT := coalesce(current_setting('qnop.audit_writing_redaction', true), '');
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
  IF NEW.event_type = 'audit.chain.redacted'
     AND (v_writing = ''
          OR NEW.scope <> 'SYSTEM'
          OR jsonb_typeof(NEW.detail) IS DISTINCT FROM 'object'
          OR (NEW.detail ->> 'redactionId') IS DISTINCT FROM v_writing) THEN
    RAISE EXCEPTION 'audit.chain.redacted events are written only by the database'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END;
$fn$;

-- --- detail pseudonymisation ----------------------------------------------

-- Returns p_detail with every reference to the user replaced by p_pseudonym:
-- any string exactly equal to one of p_names (the identifying strings the
-- caller passes; case-sensitive, so a username like "member" never swallows a
-- role value "MEMBER"), any occurrence of the user id inside a string
-- (case-insensitive), and the name fields of an object whose userId is the
-- user (the snapshot a rename may have made differ from the current name).
-- Structure and keys are kept.
CREATE FUNCTION audit_event_redact_detail(
  p_detail JSONB, p_user_id TEXT, p_names TEXT[], p_pseudonym TEXT)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $fn$
DECLARE
  v_result JSONB;
  v_subject BOOLEAN;
  v_text TEXT;
BEGIN
  CASE jsonb_typeof(p_detail)
    WHEN 'object' THEN
      v_subject := (p_detail ->> 'userId') = p_user_id;
      SELECT coalesce(jsonb_object_agg(
               e.key,
               CASE
                 WHEN v_subject
                      AND e.key IN ('userName', 'username', 'displayName', 'userDisplayName',
                                    'email', 'userEmail')
                      AND jsonb_typeof(e.value) = 'string'
                   THEN to_jsonb(p_pseudonym)
                 ELSE audit_event_redact_detail(e.value, p_user_id, p_names, p_pseudonym)
               END), '{}'::jsonb)
        INTO v_result
        FROM jsonb_each(p_detail) e;
      RETURN v_result;
    WHEN 'array' THEN
      SELECT coalesce(jsonb_agg(
               audit_event_redact_detail(a.value, p_user_id, p_names, p_pseudonym)
               ORDER BY a.ord), '[]'::jsonb)
        INTO v_result
        FROM jsonb_array_elements(p_detail) WITH ORDINALITY AS a(value, ord);
      RETURN v_result;
    WHEN 'string' THEN
      v_text := p_detail #>> '{}';
      IF v_text = ANY (p_names) THEN
        RETURN to_jsonb(p_pseudonym);
      END IF;
      IF position(p_user_id IN lower(v_text)) > 0 THEN
        -- A uuid's text holds no regular-expression metacharacters.
        RETURN to_jsonb(regexp_replace(v_text, p_user_id, p_pseudonym, 'gi'));
      END IF;
      RETURN p_detail;
    ELSE
      RETURN p_detail;
  END CASE;
END;
$fn$;

-- --- the redaction --------------------------------------------------------

-- Pseudonymises every audit row that names p_user_id (as actor, or by id in
-- the detail) and records it in one `audit.chain.redacted` event. Returns the
-- redaction id, or NULL when no row named the user. p_names are the user's
-- identifying strings (username, display name, slug, e-mail) to replace as
-- well, but only inside the rows that reference the user's id.
--
-- Sealed rows keep chain_seq/prev_hash/row_hash; the event lists them as
-- {seq, rowHash}. Rows not sealed yet (written earlier in this transaction)
-- are counted as pendingRows: they are sealed at commit over their redacted
-- content, so they verify like any other row.
CREATE FUNCTION audit_event_redact_user(p_user_id UUID, p_names TEXT[])
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_redaction UUID := gen_random_uuid();
  v_pseudonym TEXT := 'erased-user:' || gen_random_uuid()::text;
  v_id TEXT := p_user_id::text;
  v_names TEXT[];
  v_rows JSONB;
  v_pending BIGINT;
  v_total BIGINT;
BEGIN
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'audit_event_redact_user: user id is required';
  END IF;
  SELECT coalesce(array_agg(DISTINCT n), '{}')
    INTO v_names
    FROM unnest(coalesce(p_names, '{}'::text[])) n
   WHERE n IS NOT NULL AND n <> '';

  PERFORM set_config('qnop.audit_redaction', v_redaction::text, true);

  WITH targets AS (
    SELECT id
      FROM audit_event
     WHERE actor_id = p_user_id
        OR position(v_id IN lower(detail::text)) > 0
     FOR UPDATE
  ), changed AS (
    UPDATE audit_event e
       SET actor_pseudonym = CASE WHEN e.actor_id = p_user_id THEN v_pseudonym
                                  ELSE e.actor_pseudonym END,
           actor_id = CASE WHEN e.actor_id = p_user_id THEN NULL ELSE e.actor_id END,
           detail = CASE WHEN e.detail IS NULL THEN NULL
                         ELSE audit_event_redact_detail(e.detail, v_id, v_names, v_pseudonym) END,
           redacted_at = clock_timestamp(),
           redaction_id = v_redaction
      FROM targets t
     WHERE e.id = t.id
    RETURNING e.chain_seq, e.row_hash
  )
  SELECT jsonb_agg(jsonb_build_object('seq', chain_seq, 'rowHash', row_hash) ORDER BY chain_seq)
           FILTER (WHERE chain_seq IS NOT NULL),
         count(*) FILTER (WHERE chain_seq IS NULL),
         count(*)
    INTO v_rows, v_pending, v_total
    FROM changed;

  PERFORM set_config('qnop.audit_redaction', '', true);
  IF v_total = 0 THEN
    RETURN NULL;
  END IF;

  PERFORM set_config('qnop.audit_writing_redaction', v_redaction::text, true);
  INSERT INTO audit_event (id, scope, document_id, event_type, actor_id, detail, created_at)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'audit.chain.redacted', NULL,
          jsonb_build_object(
            'redactionId', v_redaction,
            'rows', coalesce(v_rows, '[]'::jsonb),
            'pendingRows', v_pending),
          clock_timestamp());
  PERFORM set_config('qnop.audit_writing_redaction', '', true);
  RETURN v_redaction;
END;
$fn$;

-- BEFORE DELETE on qnop_user: erase the user from the audit trail before the
-- actor foreign key is checked.
CREATE FUNCTION qnop_user_redact_audit()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
BEGIN
  PERFORM audit_event_redact_user(
    OLD.id, ARRAY[OLD.username, OLD.display_name, OLD.slug, OLD.email, lower(OLD.email)]);
  RETURN OLD;
END;
$fn$;

-- Only the trigger (running as the owner) may start a redaction: a role that
-- can merely call functions cannot pseudonymise a user who still exists.
REVOKE EXECUTE ON FUNCTION audit_event_redact_user(uuid, text[]) FROM PUBLIC;

-- --- search_path ----------------------------------------------------------

DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_event_seal()',
    'audit_event_reject_update()',
    'audit_event_guard_insert()',
    'audit_event_redact_detail(jsonb, text, text[], text)',
    'audit_event_redact_user(uuid, text[])',
    'qnop_user_redact_audit()']
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = %I, pg_temp', f, current_schema());
  END LOOP;
END;
$$;

-- --- trigger --------------------------------------------------------------

CREATE TRIGGER trg_qnop_user_redact_audit
  BEFORE DELETE ON qnop_user
  FOR EACH ROW EXECUTE FUNCTION qnop_user_redact_audit();

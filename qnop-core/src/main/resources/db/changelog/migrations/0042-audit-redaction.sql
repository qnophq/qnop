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
-- foreign key. This redaction is the ONE sanctioned way the content of an
-- audit row may change:
--
--   * a BEFORE DELETE trigger on qnop_user runs audit_event_redact_user for
--     the user being deleted: a user leaves the trail exactly when the account
--     is deleted, by whatever path;
--   * the function writes its parameters (user, redaction id, pseudonym,
--     time) into audit_redaction_context, a table no role but the schema owner
--     can touch, keyed by the current transaction. The update and insert
--     guards authorise a redaction only against that row, only from inside a
--     trigger (pg_trigger_depth() > 1), and only for the exact pseudonymised
--     form of the old row — a session cannot arm the guard with values of its
--     own choosing;
--   * chain_seq, prev_hash and row_hash stay as they were, so the chain still
--     links. A DB-authored `audit.chain.redacted` event, sealed after the rows,
--     lists every redacted row with its original row_hash AND a hash of its
--     redacted content: the verifier recomputes the latter, so the redacted
--     content stays tamper-evident;
--   * a DB-authored `user.erased` event records that an erasure happened and
--     who asked for it (the acting user the application announces), with the
--     redaction id as its only reference — it names no erased person.
--
-- fk_audit_event_actor stays ON DELETE RESTRICT: the trigger clears the actor
-- before the foreign key is checked, and SET NULL would be a second write path
-- outside the redaction context, which the update guard rejects.

-- --- schema ---------------------------------------------------------------

ALTER TABLE audit_event
  ADD COLUMN redacted_at TIMESTAMPTZ,
  ADD COLUMN redaction_id UUID,
  ADD COLUMN actor_pseudonym VARCHAR(64);

ALTER TABLE audit_event ADD CONSTRAINT ck_audit_event_redaction
  CHECK ((redacted_at IS NULL) = (redaction_id IS NULL)
         AND (actor_pseudonym IS NULL OR (redaction_id IS NOT NULL AND actor_id IS NULL)));

-- The redaction in progress in a transaction. Written and cleared only by
-- audit_event_redact_user (SECURITY DEFINER); every privilege is revoked below,
-- including any a default-privileges rule granted to the application role.
CREATE TABLE audit_redaction_context (
  txid BIGINT PRIMARY KEY,
  user_id UUID NOT NULL,
  redaction_id UUID NOT NULL,
  pseudonym TEXT NOT NULL,
  redacted_at TIMESTAMPTZ NOT NULL,
  -- 'redact' while rows are rewritten, 'record' while the events are written
  phase TEXT NOT NULL CHECK (phase IN ('redact', 'record')),
  -- the one event the insert guard currently admits
  event_type TEXT,
  event_actor UUID,
  event_detail JSONB
);

DO $$
DECLARE
  g TEXT;
BEGIN
  REVOKE ALL ON audit_redaction_context FROM PUBLIC;
  FOR g IN
    SELECT DISTINCT grantee FROM information_schema.role_table_grants
     WHERE table_schema = current_schema()
       AND table_name = 'audit_redaction_context'
       AND grantee <> current_user
  LOOP
    EXECUTE format('REVOKE ALL ON audit_redaction_context FROM %I', g);
  END LOOP;
END;
$$;

-- The current transaction's redaction context (all-NULL when there is none).
-- SECURITY DEFINER so the guards can read it whoever runs the statement; it
-- only reads, so it stays executable by everyone.
CREATE FUNCTION audit_redaction_context_current()
RETURNS audit_redaction_context
LANGUAGE sql
VOLATILE
SECURITY DEFINER
AS $fn$
  SELECT * FROM audit_redaction_context WHERE txid = txid_current()
$fn$;

-- --- content hash of a redacted row ----------------------------------------

-- The redaction fields, length-prefixed like audit_event_canonical behind
-- their own version tag. A redacted row's content hash is
--   hex(sha256(utf8(prev || audit_event_canonical(row) || this)))
-- with prev = the row's prev_hash, or '' for a row not yet sealed. Mirrored in
-- Java by AuditChainHasher.redactionCanonical / contentHash.
CREATE FUNCTION audit_event_redaction_canonical(
  p_pseudonym TEXT, p_redaction_id UUID, p_redacted_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $fn$
  SELECT string_agg(
           CASE WHEN f IS NULL THEN '~' ELSE octet_length(convert_to(f, 'UTF8')) || ':' || f END,
           '' ORDER BY ord)
  FROM unnest(ARRAY[
         'r1',
         p_pseudonym,
         p_redaction_id::text,
         (extract(epoch FROM p_redacted_at) * 1000000)::bigint::text
       ]) WITH ORDINALITY AS t(f, ord)
$fn$;

-- --- seal: hash what is stored at commit -----------------------------------

-- Re-created so the seal hashes the row as it is at COMMIT rather than as it
-- was inserted: a row redacted in the transaction that wrote it is sealed with
-- its redacted content. Identical for every other row.
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

-- --- detail pseudonymisation ----------------------------------------------

-- Returns p_detail with the references to the user replaced by p_pseudonym,
-- and nothing else touched:
--   * every string value that IS the user's id (case-insensitive);
--   * inside an object whose "userId" is the user, the values of its name
--     keys (userName, username, name, displayName, userDisplayName, email,
--     userEmail) — the snapshot written next to the id.
-- Strings that merely equal a name elsewhere are left alone: names are chosen
-- by users, so matching them anywhere would let one user's name blank out
-- unrelated content. Free text that mentions a person is not covered.
--
-- The walk is bounded (audit_event_redact_detail_at): from nesting depth 32
-- on, a subtree that contains the user's id anywhere is replaced as a whole by
-- the fixed string '[erased: nested too deep]', and any other subtree is kept
-- as it is. An arbitrarily deep detail — which any role that may INSERT audit
-- rows can write — can therefore neither exhaust the stack inside the
-- user-delete trigger (which would make that user undeletable for good) nor
-- keep the user's id. The update guard calls this same function, so it admits
-- exactly the bounded result.
CREATE FUNCTION audit_event_redact_detail_at(
  p_detail JSONB, p_user_id TEXT, p_pseudonym TEXT, p_depth INTEGER)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $fn$
DECLARE
  v_result JSONB;
  v_subject BOOLEAN;
BEGIN
  IF p_depth >= 32 THEN
    IF position(p_user_id IN lower(p_detail::text)) > 0 THEN
      RETURN to_jsonb('[erased: nested too deep]'::text);
    END IF;
    RETURN p_detail;
  END IF;
  CASE jsonb_typeof(p_detail)
    WHEN 'object' THEN
      v_subject := lower(p_detail ->> 'userId') = p_user_id;
      SELECT coalesce(jsonb_object_agg(
               e.key,
               CASE
                 WHEN v_subject
                      AND e.key IN ('userName', 'username', 'name', 'displayName',
                                    'userDisplayName', 'email', 'userEmail')
                      AND jsonb_typeof(e.value) = 'string'
                   THEN to_jsonb(p_pseudonym)
                 ELSE audit_event_redact_detail_at(e.value, p_user_id, p_pseudonym, p_depth + 1)
               END), '{}'::jsonb)
        INTO v_result
        FROM jsonb_each(p_detail) e;
      RETURN v_result;
    WHEN 'array' THEN
      SELECT coalesce(jsonb_agg(
               audit_event_redact_detail_at(a.value, p_user_id, p_pseudonym, p_depth + 1)
               ORDER BY a.ord),
             '[]'::jsonb)
        INTO v_result
        FROM jsonb_array_elements(p_detail) WITH ORDINALITY AS a(value, ord);
      RETURN v_result;
    WHEN 'string' THEN
      IF lower(p_detail #>> '{}') = p_user_id THEN
        RETURN to_jsonb(p_pseudonym);
      END IF;
      RETURN p_detail;
    ELSE
      RETURN p_detail;
  END CASE;
END;
$fn$;

CREATE FUNCTION audit_event_redact_detail(p_detail JSONB, p_user_id TEXT, p_pseudonym TEXT)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
AS $fn$
  SELECT audit_event_redact_detail_at(p_detail, p_user_id, p_pseudonym, 0)
$fn$;

-- --- update guard ---------------------------------------------------------

-- Two updates are permitted, nothing else:
--   1. the seal filling in the empty chain columns of an otherwise unchanged
--      row, from inside audit_event_seal (trigger depth > 1);
--   2. a redaction, from inside a trigger (depth > 1) while the transaction's
--      redaction context is in its 'redact' phase, producing EXACTLY the
--      pseudonymised form of the old row for that context: the detail is
--      audit_event_redact_detail(old detail); an actor equal to the erased
--      user becomes NULL with the context's pseudonym, any other actor and
--      pseudonym stay; redaction id and time are the context's; id, scope,
--      document, type, time and the chain columns are unchanged; and the row
--      must actually name the user. A row redacted before may be redacted
--      again only on these terms, i.e. when it also names this user.
CREATE OR REPLACE FUNCTION audit_event_reject_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $fn$
DECLARE
  ctx audit_redaction_context;
  v_detail JSONB;
BEGIN
  IF pg_trigger_depth() > 1
     AND NEW.id = OLD.id
     AND NEW.scope IS NOT DISTINCT FROM OLD.scope
     AND NEW.document_id IS NOT DISTINCT FROM OLD.document_id
     AND NEW.event_type IS NOT DISTINCT FROM OLD.event_type
     AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN

    IF OLD.chain_seq IS NULL AND NEW.chain_seq IS NOT NULL
       AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
       AND NEW.detail IS NOT DISTINCT FROM OLD.detail
       AND NEW.redacted_at IS NOT DISTINCT FROM OLD.redacted_at
       AND NEW.redaction_id IS NOT DISTINCT FROM OLD.redaction_id
       AND NEW.actor_pseudonym IS NOT DISTINCT FROM OLD.actor_pseudonym THEN
      RETURN NEW;
    END IF;

    ctx := audit_redaction_context_current();
    IF ctx.txid IS NOT NULL AND ctx.phase = 'redact'
       AND NEW.chain_seq IS NOT DISTINCT FROM OLD.chain_seq
       AND NEW.prev_hash IS NOT DISTINCT FROM OLD.prev_hash
       AND NEW.row_hash IS NOT DISTINCT FROM OLD.row_hash
       AND NEW.redaction_id = ctx.redaction_id
       AND NEW.redacted_at = ctx.redacted_at THEN
      v_detail := CASE WHEN OLD.detail IS NULL THEN NULL
                       ELSE audit_event_redact_detail(OLD.detail, ctx.user_id::text, ctx.pseudonym) END;
      IF NEW.detail IS NOT DISTINCT FROM v_detail
         AND ((OLD.actor_id = ctx.user_id
               AND NEW.actor_id IS NULL
               AND NEW.actor_pseudonym = ctx.pseudonym)
              OR (OLD.actor_id IS DISTINCT FROM ctx.user_id
                  AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
                  AND NEW.actor_pseudonym IS NOT DISTINCT FROM OLD.actor_pseudonym
                  AND NEW.detail IS DISTINCT FROM OLD.detail)) THEN
        RETURN NEW;
      END IF;
    END IF;
  END IF;
  RAISE EXCEPTION 'audit_event is append-only: rows cannot be updated'
    USING ERRCODE = 'insufficient_privilege';
END;
$fn$;

-- --- insert guard ---------------------------------------------------------

-- As before (unsealed rows only, tombstones only from the prune trigger), and
-- additionally: new rows arrive unredacted, and the reserved types
-- `audit.chain.redacted` and `user.erased` are admitted only from inside a
-- trigger, while the redaction context is recording, and only as exactly the
-- event the context announces.
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

-- --- the redaction --------------------------------------------------------

-- Pseudonymises every audit row that names p_user_id (as actor, or by id in
-- the detail), records it, and returns the redaction id (NULL when no row
-- named the user). Always writes one `user.erased` event attributed to
-- p_actor_id. Only runs from the qnop_user trigger: called directly, its
-- writes are not inside a trigger and the guards reject them.
--
-- The `audit.chain.redacted` detail lists, per redacted row, its id, its
-- chain position and original row_hash (null for a row not sealed yet), and
-- contentHash over its redacted content (see audit_event_redaction_canonical).
CREATE FUNCTION audit_event_redact_user(p_user_id UUID, p_actor_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $fn$
DECLARE
  v_ctx audit_redaction_context;
  v_id TEXT := p_user_id::text;
  v_rows JSONB;
  v_pending BIGINT;
  v_total BIGINT;
  v_redaction UUID;
BEGIN
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'audit_event_redact_user: user id is required';
  END IF;

  INSERT INTO audit_redaction_context (txid, user_id, redaction_id, pseudonym, redacted_at, phase)
  VALUES (txid_current(), p_user_id, gen_random_uuid(),
          'erased-user:' || gen_random_uuid()::text, clock_timestamp(), 'redact')
  RETURNING * INTO v_ctx;

  WITH changed AS (
    UPDATE audit_event e
       SET actor_pseudonym = CASE WHEN e.actor_id = p_user_id THEN v_ctx.pseudonym
                                  ELSE e.actor_pseudonym END,
           actor_id = CASE WHEN e.actor_id = p_user_id THEN NULL ELSE e.actor_id END,
           detail = CASE WHEN e.detail IS NULL THEN NULL
                         ELSE audit_event_redact_detail(e.detail, v_id, v_ctx.pseudonym) END,
           redacted_at = v_ctx.redacted_at,
           redaction_id = v_ctx.redaction_id
     WHERE e.actor_id = p_user_id
        OR (position(v_id IN lower(e.detail::text)) > 0
            AND audit_event_redact_detail(e.detail, v_id, v_ctx.pseudonym) IS DISTINCT FROM e.detail)
    RETURNING e.id, e.chain_seq, e.row_hash,
              encode(sha256(convert_to(
                coalesce(e.prev_hash, '')
                  || audit_event_canonical(e.id, e.scope, e.document_id, e.event_type,
                                           e.actor_id, e.detail, e.created_at)
                  || audit_event_redaction_canonical(e.actor_pseudonym, e.redaction_id,
                                                     e.redacted_at),
                'UTF8')), 'hex') AS content_hash
  )
  SELECT jsonb_agg(jsonb_build_object('id', id, 'seq', chain_seq, 'rowHash', row_hash,
                                      'contentHash', content_hash)
                   ORDER BY chain_seq NULLS LAST, id),
         count(*) FILTER (WHERE chain_seq IS NULL),
         count(*)
    INTO v_rows, v_pending, v_total
    FROM changed;

  IF v_total > 0 THEN
    v_redaction := v_ctx.redaction_id;
    UPDATE audit_redaction_context
       SET phase = 'record', event_type = 'audit.chain.redacted', event_actor = NULL,
           event_detail = jsonb_build_object('redactionId', v_redaction, 'rows', v_rows,
                                             'pendingRows', v_pending)
     WHERE txid = v_ctx.txid
     RETURNING * INTO v_ctx;
    INSERT INTO audit_event (id, scope, document_id, event_type, actor_id, detail, created_at)
    VALUES (gen_random_uuid(), 'SYSTEM', NULL, v_ctx.event_type, NULL, v_ctx.event_detail,
            clock_timestamp());
  END IF;

  UPDATE audit_redaction_context
     SET phase = 'record', event_type = 'user.erased', event_actor = p_actor_id,
         event_detail = jsonb_build_object('redactionId', v_redaction)
   WHERE txid = v_ctx.txid
   RETURNING * INTO v_ctx;
  INSERT INTO audit_event (id, scope, document_id, event_type, actor_id, detail, created_at)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, v_ctx.event_type, v_ctx.event_actor,
          v_ctx.event_detail, clock_timestamp());

  DELETE FROM audit_redaction_context WHERE txid = v_ctx.txid;
  RETURN v_redaction;
END;
$fn$;

-- BEFORE DELETE on qnop_user: erase the user from the audit trail before the
-- actor foreign key is checked. The acting user is whatever the application
-- announced in qnop.erasing_actor for this deletion (attribution only — it
-- authorises nothing); unknown, unparsable or the erased user itself → NULL.
CREATE FUNCTION qnop_user_redact_audit()
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

-- Only the trigger (running as the owner) starts a redaction.
REVOKE EXECUTE ON FUNCTION audit_event_redact_user(uuid, uuid) FROM PUBLIC;

-- --- search_path ----------------------------------------------------------

DO $$
DECLARE
  f TEXT;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'audit_redaction_context_current()',
    'audit_event_redaction_canonical(text, uuid, timestamptz)',
    'audit_event_seal()',
    'audit_event_redact_detail_at(jsonb, text, text, integer)',
    'audit_event_redact_detail(jsonb, text, text)',
    'audit_event_reject_update()',
    'audit_event_guard_insert()',
    'audit_event_redact_user(uuid, uuid)',
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

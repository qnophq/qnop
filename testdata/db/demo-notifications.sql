-- Copyright (c) 2026-present devtank42 GmbH
-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Fills the in-app inboxes (issue #538) with a realistic backlog for manual
-- testing: ~130 notifications per user, spread over the last twelve weeks,
-- varied in type, mostly read with a recent unread head.
--
-- DEV ONLY, and deliberately NOT part of seed.sql: the integration tests load
-- clean.sql + seed.sql and assert exact counts, so thousands of rows there
-- would slow every seeded IT and break those assertions. Run it by hand:
--
--   PGPASSWORD=qnop psql -h 127.0.0.1 -U qnop -d qnop -f testdata/db/demo-notifications.sql
--
-- It is additive and repeatable — each run appends another batch. To start over:
--   DELETE FROM notification;
--
-- Two properties make the result actually usable rather than merely numerous:
--
--   * Every row points at a review its recipient may really see (owner, direct
--     participant or via a team). The detail view re-checks visibility and
--     renders a tombstone otherwise, so a naive cross join would produce an
--     inbox of "A review you no longer have access to".
--   * Annotation- and comment-shaped types reuse the REAL annotation and
--     comment ids of their document, so the deep links land somewhere.
--
-- Everything stays inside the default retention window (notifications.retain_days
-- = 90, issue #626) so the notificationSweep job does not quietly eat the demo
-- data on its next run.

WITH
  -- Who may see which review — the same three paths DocumentAccessService uses.
  visible AS (
    SELECT owner_id AS recipient_id, id AS document_id FROM document
    UNION
    SELECT p.user_id, p.document_id
      FROM review_participant p
     WHERE p.user_id IS NOT NULL
    UNION
    SELECT tm.user_id, p.document_id
      FROM review_participant p
      JOIN team_membership tm ON tm.team_id = p.team_id
     WHERE p.team_id IS NOT NULL
  ),
  ranked AS (
    SELECT v.recipient_id,
           v.document_id,
           row_number() OVER (PARTITION BY v.recipient_id ORDER BY v.document_id) - 1 AS doc_idx,
           count(*)     OVER (PARTITION BY v.recipient_id)                          AS doc_count
      FROM visible v
      JOIN qnop_user u ON u.id = v.recipient_id AND u.enabled
  ),
  recipients AS (
    SELECT DISTINCT recipient_id, doc_count FROM ranked
  ),
  -- 130 per recipient, cycling through the reviews they can see.
  gen AS (
    SELECT r.recipient_id, r.doc_count, g.n
      FROM recipients r
      CROSS JOIN generate_series(1, 130) AS g(n)
  ),
  -- One quotable line per row; the pool is small on purpose, real inboxes repeat.
  lines AS (
    SELECT * FROM (VALUES
      (0,  'The liability cap in section 7 contradicts the indemnity clause.'),
      (1,  'Can we align this wording with the master agreement?'),
      (2,  'This paragraph still refers to the previous vendor.'),
      (3,  'Numbers here do not add up against the appendix.'),
      (4,  'Suggest dropping this sentence entirely — it repeats §2.'),
      (5,  'Please confirm the effective date before we sign off.'),
      (6,  'Typo: "notwithstanding" is misspelled twice on this page.'),
      (7,  'Is this clause still required after the scope change?'),
      (8,  'The termination notice period differs from what we agreed.'),
      (9,  'Good catch — I have reworded it in the new version.'),
      (10, 'Legal asked for a stricter formulation here.'),
      (11, 'This table lost its header in the last export.')
    ) AS t(idx, body)
  )
INSERT INTO notification (
  id, recipient_id, type, actor_id, document_id, annotation_id, comment_id,
  excerpt, decision, version_number, from_state, to_state, read_at, created_at
)
SELECT
  gen_random_uuid(),
  gen.recipient_id,
  kind.type,
  actor.id,
  ranked.document_id,
  CASE WHEN kind.type IN ('MENTION', 'COMMENT_ADDED', 'ANNOTATION_CREATED', 'ANNOTATION_DECIDED')
       THEN anno.id END,
  CASE WHEN kind.type IN ('MENTION', 'COMMENT_ADDED') THEN cmt.id END,
  CASE WHEN kind.type IN ('MENTION', 'COMMENT_ADDED', 'ANNOTATION_CREATED', 'ANNOTATION_DECIDED')
       THEN lines.body END,
  CASE kind.type WHEN 'ANNOTATION_DECIDED'
       THEN (ARRAY['resolved', 'reopened', 'dismissed'])[1 + (gen.n % 3)] END,
  CASE kind.type WHEN 'VERSION_UPLOADED' THEN 2 + (gen.n % 4) END,
  CASE kind.type WHEN 'WORKFLOW_CHANGED'
       THEN (ARRAY['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED'])[1 + (gen.n % 3)] END,
  CASE kind.type WHEN 'WORKFLOW_CHANGED'
       THEN (ARRAY['IN_REVIEW', 'CHANGES_REQUESTED', 'FINALIZED'])[1 + (gen.n % 3)] END,
  -- Unread is the recent head plus the odd older one somebody skipped.
  CASE WHEN gen.n <= 28 OR (gen.n % 23) = 0
       THEN NULL
       ELSE stamp.at + interval '3 hours' END,
  stamp.at
FROM gen
JOIN ranked
  ON ranked.recipient_id = gen.recipient_id
 AND ranked.doc_idx = gen.n % gen.doc_count
-- n = 1 is the newest; older rows fan out across twelve weeks.
CROSS JOIN LATERAL (
  SELECT now()
         - (interval '84 days' * gen.n / 130.0)
         - (interval '1 minute' * ((gen.n * 37) % 1440)) AS at
) AS stamp
-- A weighted type mix: conversation dominates, invitations are rare.
CROSS JOIN LATERAL (
  SELECT CASE
           WHEN gen.n % 20 < 7  THEN 'COMMENT_ADDED'
           WHEN gen.n % 20 < 11 THEN 'ANNOTATION_CREATED'
           WHEN gen.n % 20 < 14 THEN 'MENTION'
           WHEN gen.n % 20 < 16 THEN 'ANNOTATION_DECIDED'
           WHEN gen.n % 20 < 18 THEN 'VERSION_UPLOADED'
           WHEN gen.n % 20 < 19 THEN 'WORKFLOW_CHANGED'
           ELSE 'PARTICIPANT_ADDED'
         END AS type
) AS kind
JOIN lines ON lines.idx = gen.n % 12
-- Somebody from the review's own circle, never the recipient themselves.
CROSS JOIN LATERAL (
  SELECT c.id
    FROM (
      SELECT d.owner_id AS id FROM document d WHERE d.id = ranked.document_id
      UNION
      SELECT p.user_id FROM review_participant p
       WHERE p.document_id = ranked.document_id AND p.user_id IS NOT NULL
    ) AS c
   WHERE c.id <> gen.recipient_id
   ORDER BY md5(c.id::text || gen.n::text)
   LIMIT 1
) AS actor
-- Real ids where the document has them, so the deep links resolve.
LEFT JOIN LATERAL (
  SELECT a.id FROM annotation a
   WHERE a.document_id = ranked.document_id
   ORDER BY a.created_at
   LIMIT 1
) AS anno ON true
LEFT JOIN LATERAL (
  SELECT c.id FROM comment c
    JOIN annotation a ON a.id = c.annotation_id
   WHERE a.document_id = ranked.document_id
   ORDER BY c.created_at
   LIMIT 1
) AS cmt ON true;

-- What landed, per inbox.
SELECT u.display_name,
       count(*)                                    AS total,
       count(*) FILTER (WHERE n.read_at IS NULL)   AS unread,
       count(DISTINCT n.type)                      AS types,
       min(n.created_at)::date                     AS oldest,
       max(n.created_at)::date                     AS newest
  FROM notification n
  JOIN qnop_user u ON u.id = n.recipient_id
 GROUP BY u.display_name
 ORDER BY u.display_name;

-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Shared dummy dataset for the integration-test suite (issue #163).
--
-- Loaded per test by io.qnop.testsupport.SeededIntegrationTest on the
-- transaction-bound connection; the test is @Transactional, so every row here
-- is rolled back after each test (no pollution of the JVM-shared container).
--
-- Only tables that are EMPTY in the migrated baseline are seeded here
-- (qnop_user, team, team_membership, oidc_provider). application_setting and
-- mail_template already carry migration seeds and are left untouched.
--
-- All passwords are the bcrypt of "Test-Pass-1234!" (SeededIntegrationTest.SEED_PASSWORD).
-- Fixed UUIDs let tests reference rows by stable id (see SeededIntegrationTest).

-- ---------------------------------------------------------------------------
-- Users: one per role / source / state.
-- ---------------------------------------------------------------------------
INSERT INTO qnop_user
  (id, display_name, email, source, username, password_hash, enabled,
   password_change_required, role, last_login_at, created_at, updated_at, version)
VALUES
  ('a0000000-0000-0000-0000-000000000001', 'Ada Admin', 'admin@qnop.test', 'INTERNAL',
   'admin', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   false, 'ADMIN', TIMESTAMPTZ '2026-02-01 09:00:00+00', TIMESTAMPTZ '2026-01-01 08:00:00+00', TIMESTAMPTZ '2026-01-01 08:00:00+00', 0),
  ('a0000000-0000-0000-0000-000000000008', 'Boss Admin', 'admin2@qnop.test', 'INTERNAL',
   'admin2', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   false, 'ADMIN', NULL, TIMESTAMPTZ '2026-01-01 08:05:00+00', TIMESTAMPTZ '2026-01-01 08:05:00+00', 0),
  ('a0000000-0000-0000-0000-000000000002', 'Mia Member', 'member@qnop.test', 'INTERNAL',
   'member', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   false, 'MEMBER', TIMESTAMPTZ '2026-02-02 12:30:00+00', TIMESTAMPTZ '2026-01-01 08:10:00+00', TIMESTAMPTZ '2026-01-01 08:10:00+00', 0),
  ('a0000000-0000-0000-0000-000000000003', 'Max Member', 'member2@qnop.test', 'INTERNAL',
   'member2', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-01 08:15:00+00', TIMESTAMPTZ '2026-01-01 08:15:00+00', 0),
  ('a0000000-0000-0000-0000-000000000004', 'Avery Auditor', 'auditor@qnop.test', 'INTERNAL',
   'auditor', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   false, 'AUDITOR', NULL, TIMESTAMPTZ '2026-01-01 08:20:00+00', TIMESTAMPTZ '2026-01-01 08:20:00+00', 0),
  ('a0000000-0000-0000-0000-000000000005', 'Dana Disabled', 'disabled@qnop.test', 'INTERNAL',
   'disabled', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', false,
   false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-01 08:25:00+00', TIMESTAMPTZ '2026-01-01 08:25:00+00', 0),
  ('a0000000-0000-0000-0000-000000000006', 'Pat Pending', 'pwchange@qnop.test', 'INTERNAL',
   'pwchange', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true,
   true, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-01 08:30:00+00', TIMESTAMPTZ '2026-01-01 08:30:00+00', 0),
  ('a0000000-0000-0000-0000-000000000007', 'Ext Ernal', 'external@qnop.test', 'EXTERNAL',
   NULL, NULL, true,
   false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-01 08:35:00+00', TIMESTAMPTZ '2026-01-01 08:35:00+00', 0);

-- ---------------------------------------------------------------------------
-- Teams + memberships.
-- ---------------------------------------------------------------------------
INSERT INTO team (id, name, description, enabled, created_at, updated_at, version)
VALUES
  ('b0000000-0000-0000-0000-000000000001', 'Alpha', 'Primary review team', true,
   TIMESTAMPTZ '2026-01-02 08:00:00+00', TIMESTAMPTZ '2026-01-02 08:00:00+00', 0),
  ('b0000000-0000-0000-0000-000000000002', 'Beta', 'Secondary review team', true,
   TIMESTAMPTZ '2026-01-02 08:05:00+00', TIMESTAMPTZ '2026-01-02 08:05:00+00', 0);

INSERT INTO team_membership (id, team_id, user_id, team_role, joined_at, version)
VALUES
  ('c1000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001',
   'a0000000-0000-0000-0000-000000000001', 'LEAD', TIMESTAMPTZ '2026-01-02 09:00:00+00', 0),
  ('c1000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000001',
   'a0000000-0000-0000-0000-000000000002', 'MEMBER', TIMESTAMPTZ '2026-01-02 09:01:00+00', 0),
  ('c1000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000001',
   'a0000000-0000-0000-0000-000000000004', 'MEMBER', TIMESTAMPTZ '2026-01-02 09:02:00+00', 0),
  ('c1000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000002',
   'a0000000-0000-0000-0000-000000000002', 'LEAD', TIMESTAMPTZ '2026-01-02 09:03:00+00', 0),
  ('c1000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000002',
   'a0000000-0000-0000-0000-000000000003', 'MEMBER', TIMESTAMPTZ '2026-01-02 09:04:00+00', 0);

-- ---------------------------------------------------------------------------
-- OIDC providers: one enabled (with secret), one disabled (no secret).
--
-- client_secret_encrypted is read through EncryptedStringConverter, which
-- decrypts on entity load — so the value MUST be a real ciphertext produced by
-- Encryptors.delux with the integration-test key/salt (AbstractIntegrationTest).
-- The literal below is the encryption of "seed-dummy-secret"; the disabled
-- provider stores NULL to exercise the no-secret path (hasClientSecret=false).
-- ---------------------------------------------------------------------------
INSERT INTO oidc_provider
  (id, name, provider_type, enabled, client_id, client_secret_encrypted, issuer_uri,
   scope, created_at, updated_at)
VALUES
  ('d0000000-0000-0000-0000-000000000001', 'Seeded Google', 'GOOGLE', true,
   'seed-client-google',
   'd87d2739523ce0d827fb5b56b26019ed803139c074194c98394602a53ece3dd3d5288dcf0ef4535907065ec9e65a9381e1',
   'https://accounts.google.example',
   'openid email profile', TIMESTAMPTZ '2026-01-03 08:00:00+00', TIMESTAMPTZ '2026-01-03 08:00:00+00'),
  ('d0000000-0000-0000-0000-000000000002', 'Seeded OIDC (disabled)', 'OIDC', false,
   'seed-client-oidc', NULL, 'https://idp.example',
   'openid email', TIMESTAMPTZ '2026-01-03 08:05:00+00', TIMESTAMPTZ '2026-01-03 08:05:00+00');

-- ---------------------------------------------------------------------------
-- Crowd users (issue #401): twenty regular members for manual testing —
-- populated pickers, team assignment, realistic participant lists. Same
-- shared password; ids continue the a0000000 scheme (…09 – …1c). Roles are
-- MEMBER only: SeededAdminUsersIT pins the ADMIN (2) and AUDITOR (1) counts.
-- ---------------------------------------------------------------------------
INSERT INTO qnop_user
  (id, display_name, email, source, username, password_hash, enabled,
   password_change_required, role, last_login_at, created_at, updated_at, version)
VALUES
  ('a0000000-0000-0000-0000-000000000009', 'Nora Weber',    'nora@qnop.test',  'INTERNAL', 'nora',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:00:00+00', TIMESTAMPTZ '2026-01-05 09:00:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000a', 'Felix Braun',   'felix@qnop.test', 'INTERNAL', 'felix', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:01:00+00', TIMESTAMPTZ '2026-01-05 09:01:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000b', 'Lena Fischer',  'lena@qnop.test',  'INTERNAL', 'lena',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:02:00+00', TIMESTAMPTZ '2026-01-05 09:02:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000c', 'Jonas Keller',  'jonas@qnop.test', 'INTERNAL', 'jonas', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:03:00+00', TIMESTAMPTZ '2026-01-05 09:03:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000d', 'Clara Vogt',    'clara@qnop.test', 'INTERNAL', 'clara', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:04:00+00', TIMESTAMPTZ '2026-01-05 09:04:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000e', 'Paul Richter',  'paul@qnop.test',  'INTERNAL', 'paul',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:05:00+00', TIMESTAMPTZ '2026-01-05 09:05:00+00', 0),
  ('a0000000-0000-0000-0000-00000000000f', 'Ida Hoffmann',  'ida@qnop.test',   'INTERNAL', 'ida',   '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:06:00+00', TIMESTAMPTZ '2026-01-05 09:06:00+00', 0),
  ('a0000000-0000-0000-0000-000000000010', 'Tom Schneider', 'tom@qnop.test',   'INTERNAL', 'tom',   '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:07:00+00', TIMESTAMPTZ '2026-01-05 09:07:00+00', 0),
  ('a0000000-0000-0000-0000-000000000011', 'Eva Brandt',    'eva@qnop.test',   'INTERNAL', 'eva',   '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:08:00+00', TIMESTAMPTZ '2026-01-05 09:08:00+00', 0),
  ('a0000000-0000-0000-0000-000000000012', 'Nils Weiss',    'nils@qnop.test',  'INTERNAL', 'nils',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:09:00+00', TIMESTAMPTZ '2026-01-05 09:09:00+00', 0),
  ('a0000000-0000-0000-0000-000000000013', 'Anna Krause',   'anna@qnop.test',  'INTERNAL', 'anna',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:10:00+00', TIMESTAMPTZ '2026-01-05 09:10:00+00', 0),
  ('a0000000-0000-0000-0000-000000000014', 'Ben Roth',      'ben@qnop.test',   'INTERNAL', 'ben',   '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:11:00+00', TIMESTAMPTZ '2026-01-05 09:11:00+00', 0),
  ('a0000000-0000-0000-0000-000000000015', 'Marie Lang',    'marie@qnop.test', 'INTERNAL', 'marie', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:12:00+00', TIMESTAMPTZ '2026-01-05 09:12:00+00', 0),
  ('a0000000-0000-0000-0000-000000000016', 'Leo Hartmann',  'leo@qnop.test',   'INTERNAL', 'leo',   '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:13:00+00', TIMESTAMPTZ '2026-01-05 09:13:00+00', 0),
  ('a0000000-0000-0000-0000-000000000017', 'Sofia Berg',    'sofia@qnop.test', 'INTERNAL', 'sofia', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:14:00+00', TIMESTAMPTZ '2026-01-05 09:14:00+00', 0),
  ('a0000000-0000-0000-0000-000000000018', 'Emil Franke',   'emil@qnop.test',  'INTERNAL', 'emil',  '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:15:00+00', TIMESTAMPTZ '2026-01-05 09:15:00+00', 0),
  ('a0000000-0000-0000-0000-000000000019', 'Greta Simon',   'greta@qnop.test', 'INTERNAL', 'greta', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:16:00+00', TIMESTAMPTZ '2026-01-05 09:16:00+00', 0),
  ('a0000000-0000-0000-0000-00000000001a', 'Oskar Thiel',   'oskar@qnop.test', 'INTERNAL', 'oskar', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:17:00+00', TIMESTAMPTZ '2026-01-05 09:17:00+00', 0),
  ('a0000000-0000-0000-0000-00000000001b', 'Julia Winter',  'julia@qnop.test', 'INTERNAL', 'julia', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:18:00+00', TIMESTAMPTZ '2026-01-05 09:18:00+00', 0),
  ('a0000000-0000-0000-0000-00000000001c', 'David Sommer',  'david@qnop.test', 'INTERNAL', 'david', '$2a$10$MGmtbGfpKSL2Ev2Bl8XxzOu/D4yAsfZaVchQlI2meWVmp9O3LKBV6', true, false, 'MEMBER', NULL, TIMESTAMPTZ '2026-01-05 09:19:00+00', TIMESTAMPTZ '2026-01-05 09:19:00+00', 0);

-- ---------------------------------------------------------------------------
-- Crowd teams (issue #401): five departments, 3 crowd users each (one LEAD),
-- five crowd users deliberately teamless (emil, greta, oskar, julia, david).
-- Alpha/Beta above stay untouched — SeededTeamIT pins their member counts.
-- ---------------------------------------------------------------------------
INSERT INTO team (id, name, description, enabled, created_at, updated_at, version)
VALUES
  ('b0000000-0000-0000-0000-000000000003', 'Legal',       'Contract review',        true, TIMESTAMPTZ '2026-01-05 10:00:00+00', TIMESTAMPTZ '2026-01-05 10:00:00+00', 0),
  ('b0000000-0000-0000-0000-000000000004', 'Compliance',  'Policy conformance',     true, TIMESTAMPTZ '2026-01-05 10:01:00+00', TIMESTAMPTZ '2026-01-05 10:01:00+00', 0),
  ('b0000000-0000-0000-0000-000000000005', 'Finance',     'Commercial terms',       true, TIMESTAMPTZ '2026-01-05 10:02:00+00', TIMESTAMPTZ '2026-01-05 10:02:00+00', 0),
  ('b0000000-0000-0000-0000-000000000006', 'Procurement', 'Supplier agreements',    true, TIMESTAMPTZ '2026-01-05 10:03:00+00', TIMESTAMPTZ '2026-01-05 10:03:00+00', 0),
  ('b0000000-0000-0000-0000-000000000007', 'Engineering', 'Technical annexes',      true, TIMESTAMPTZ '2026-01-05 10:04:00+00', TIMESTAMPTZ '2026-01-05 10:04:00+00', 0);

INSERT INTO team_membership (id, team_id, user_id, team_role, joined_at, version)
VALUES
  ('c1000000-0000-0000-0000-000000000006', 'b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000009', 'LEAD',   TIMESTAMPTZ '2026-01-05 11:00:00+00', 0),
  ('c1000000-0000-0000-0000-000000000007', 'b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-00000000000a', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:01:00+00', 0),
  ('c1000000-0000-0000-0000-000000000008', 'b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-00000000000b', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:02:00+00', 0),
  ('c1000000-0000-0000-0000-000000000009', 'b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-00000000000c', 'LEAD',   TIMESTAMPTZ '2026-01-05 11:03:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000a', 'b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-00000000000d', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:04:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000b', 'b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-00000000000e', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:05:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000c', 'b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-00000000000f', 'LEAD',   TIMESTAMPTZ '2026-01-05 11:06:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000d', 'b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000010', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:07:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000e', 'b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000011', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:08:00+00', 0),
  ('c1000000-0000-0000-0000-00000000000f', 'b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000012', 'LEAD',   TIMESTAMPTZ '2026-01-05 11:09:00+00', 0),
  ('c1000000-0000-0000-0000-000000000010', 'b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000013', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:10:00+00', 0),
  ('c1000000-0000-0000-0000-000000000011', 'b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000014', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:11:00+00', 0),
  ('c1000000-0000-0000-0000-000000000012', 'b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000015', 'LEAD',   TIMESTAMPTZ '2026-01-05 11:12:00+00', 0),
  ('c1000000-0000-0000-0000-000000000013', 'b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000016', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:13:00+00', 0),
  ('c1000000-0000-0000-0000-000000000014', 'b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000017', 'MEMBER', TIMESTAMPTZ '2026-01-05 11:14:00+00', 0);

-- ---------------------------------------------------------------------------
-- Mail: point the (Liquibase-seeded) SMTP settings at the docker-compose
-- Mailpit (issue #401) — localhost:1025, no auth, no TLS; inbox on :8025.
-- UPDATEs, not INSERTs: application_setting carries migration seeds and
-- survives clean.sql by design.
-- ---------------------------------------------------------------------------
UPDATE application_setting AS s
SET setting_value = v.new_value
FROM (VALUES
  ('smtp.enabled',   'true'),
  ('smtp.host',      'localhost'),
  ('smtp.port',      '1025'),
  ('smtp.username',  ''),
  ('smtp.encryption', 'none'),
  ('smtp.from',      'noreply@qnop.test'),
  ('smtp.from_name', 'qnop')
) AS v(key, new_value)
WHERE s.setting_key = v.key;

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

-- SPDX-License-Identifier: AGPL-3.0-only
--
-- Wipes the tables seeded by seed.sql (issue #163) so every seeded integration
-- test starts from a known-empty slate and leaves the JVM-shared Testcontainers
-- database clean for non-seeded IT classes afterwards.
--
-- CASCADE also clears anything a test created that references these rows
-- (e.g. user settings, team memberships). The migration-seeded tables
-- application_setting and mail_template are intentionally NOT touched.
TRUNCATE TABLE team_membership, team, oidc_provider, qnop_user RESTART IDENTITY CASCADE;

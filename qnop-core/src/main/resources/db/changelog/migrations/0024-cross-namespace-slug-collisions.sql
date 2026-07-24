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

-- One-time cross-namespace collision resolution (issue #595, ADR-0048): a team
-- whose slug collides case-insensitively with a USER slug is re-allocated to
-- its next free base-n candidate, probing BOTH tables. Teams yield because
-- user slugs are external identity (profile links, @mention tokens inside
-- stored Markdown bodies); team slugs are internal /my-teams/… links. Expected
-- to be a no-op on real data; idempotent, so re-running it is safe.
DO $$
DECLARE
  r RECORD;
  base TEXT;
  candidate TEXT;
  n INT;
BEGIN
  FOR r IN
    SELECT t.id, t.slug
    FROM team t
    WHERE t.slug IS NOT NULL
      AND EXISTS (SELECT 1 FROM qnop_user u WHERE lower(u.slug) = lower(t.slug))
    ORDER BY t.created_at, t.id
  LOOP
    base := r.slug;
    candidate := base;
    n := 1;
    WHILE EXISTS (SELECT 1 FROM qnop_user WHERE lower(slug) = lower(candidate))
          OR EXISTS (SELECT 1 FROM team WHERE lower(slug) = lower(candidate) AND id <> r.id)
    LOOP
      n := n + 1;
      candidate := trim(both '-' from substr(base, 1, 64 - length('-' || n::text))) || '-' || n;
    END LOOP;
    UPDATE team SET slug = candidate WHERE id = r.id;
  END LOOP;
END $$;

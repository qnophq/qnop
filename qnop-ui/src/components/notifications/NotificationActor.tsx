/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import Box from '@mui/material/Box';
import { Link as RouterLink } from 'react-router';
import { useUserProfile } from '../../api/hooks/useUsers';
import { UserHoverCard } from '../people/UserHoverCard';
import { UserAvatar } from '../shell/UserAvatar';

/**
 * Whoever triggered a notification, rendered the way every other name in the
 * app is (issues #482/#486): their real avatar, the player card on hover, and a
 * link to their public profile.
 *
 * <p>Everything hangs off the slug, resolved through the profile cache shared
 * with the profile page and the hover card — so the avatar is the person's
 * actual picture, not a guess from initials.
 *
 * <p>Without a slug the trigger renders as a plain initials avatar with no
 * card and no link. That is not a degraded state, it is the point: a
 * pseudonymised actor in an anonymous review (ADR-0038) ships no slug, and an
 * avatar or profile link would undo the pseudonym the name so carefully keeps.
 */
export function NotificationActor({
  name,
  slug,
  size = 28,
  /** Renders the name next to the avatar; the row variant lets the title carry it. */
  showName = false,
}: {
  name: string | null | undefined;
  slug: string | null | undefined;
  size?: number;
  showName?: boolean;
}) {
  const profile = useUserProfile(slug ?? '', Boolean(slug)).data;
  const displayName = profile?.displayName ?? name ?? 'Unknown';

  const avatar = (
    <UserAvatar name={displayName} size={size} imageUrl={profile?.avatarUrl ?? null} />
  );

  const face = showName ? (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
      {avatar}
      <Box
        component="span"
        sx={{ fontSize: 14, fontWeight: 600, wordBreak: 'break-word', color: 'text.primary' }}
      >
        {displayName}
      </Box>
    </Box>
  ) : (
    avatar
  );

  if (!slug) {
    return face;
  }

  // The anchor is built from the SLUG, not from the loaded profile: the hover
  // card only renders a link once it knows the real user id, which would leave
  // the avatar unlinked while the profile loads — and forever if that request
  // fails. Same division of labour as the @mention pill (#462): we own the
  // link, the card is a pure preview (link={false} — nested anchors are
  // invalid).
  const link = (
    <Box
      component={RouterLink}
      to={`/users/${slug}`}
      aria-label={`View ${displayName}'s profile`}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        minWidth: 0,
        textDecoration: 'none',
        borderRadius: '999px',
        '&:focus-visible': { outline: 'none', boxShadow: (t) => t.qnop.focusRing },
      }}
    >
      {face}
    </Box>
  );

  return (
    <UserHoverCard
      userId={profile?.id ?? null}
      slug={slug}
      link={false}
      sx={{ display: 'inline-flex', minWidth: 0 }}
    >
      {link}
    </UserHoverCard>
  );
}

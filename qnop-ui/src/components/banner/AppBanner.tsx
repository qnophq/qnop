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

import { useState } from 'react';
import Collapse from '@mui/material/Collapse';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useBanner } from '../../api/hooks/useBanner';
import { useAuthStore } from '../../stores/authStore';
import { InfoBanner } from './InfoBanner';
import {
  bannerFingerprint,
  readDismissedFingerprint,
  rememberBannerDismissal,
} from './bannerDismissal';

/**
 * The operator's notice for signed-in users (issue #664), above everything the
 * shell routes to.
 *
 * <p>Dismissal is per browser and per message: waving away "maintenance on
 * Saturday" is a statement about that sentence, not about banners in general, so
 * an edited notice returns rather than staying hidden behind an earlier click.
 */
export function AppBanner() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  const { data } = useBanner(isAuthenticated);
  const [dismissed, setDismissed] = useState<string | null>(() => readDismissedFingerprint());

  const banner = data?.banner;
  const fingerprint = banner ? bannerFingerprint(banner) : null;
  const visible = Boolean(banner) && dismissed !== fingerprint;

  return (
    // One movement, deliberately: the banner unfolds into the layout rather than
    // appearing under the pointer, and it folds away the same way. Nothing else
    // on this surface animates.
    <Collapse in={visible} timeout={reduceMotion ? 0 : 180} unmountOnExit>
      {banner && (
        <InfoBanner
          banner={banner}
          variant="bar"
          onDismiss={() => {
            rememberBannerDismissal(banner);
            setDismissed(bannerFingerprint(banner));
          }}
        />
      )}
    </Collapse>
  );
}

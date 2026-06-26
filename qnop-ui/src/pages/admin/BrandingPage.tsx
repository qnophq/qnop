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

import Alert from '@mui/material/Alert';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import type { ServerConfigBrandingSlot } from '../../api/generated';
import { useConfig } from '../../api/hooks/useConfig';
import { BrandingSlotCard } from '../../components/admin/branding/BrandingSlotCard';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import type { BrandingSlot } from '../../api/branding';

interface SlotDef {
  slot: BrandingSlot;
  label: string;
  description: string;
  dark?: boolean;
  asset?: ServerConfigBrandingSlot;
}

/** Admin branding: upload/replace/remove the light & dark logos and the logomark (#106). */
export function BrandingPage() {
  const { data: config, isLoading, isError } = useConfig();
  const { toast, notify, clear } = useToast();

  const branding = config?.branding;
  const slots: SlotDef[] = [
    {
      slot: 'logo-light',
      label: 'Logo (light)',
      description: 'Shown on light backgrounds.',
      asset: branding?.logoLight,
    },
    {
      slot: 'logo-dark',
      label: 'Logo (dark)',
      description: 'Shown on dark backgrounds.',
      dark: true,
      asset: branding?.logoDark,
    },
    {
      slot: 'logomark',
      label: 'Logomark',
      description: 'Compact mark / favicon.',
      asset: branding?.logomark,
    },
  ];

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Branding"
        description="Replace the default logos shown across the app — drag an image in or browse. Raster uploads open a cropper to cut the exact framing; SVG uploads as-is. PNG, WebP or SVG, up to 512 KiB; each slot falls back to the qnop default until you upload your own."
      />

      {isError ? (
        <Alert severity="error">The branding configuration could not be loaded.</Alert>
      ) : (
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ alignItems: 'stretch' }}>
          {slots.map((s) =>
            isLoading || !s.asset ? (
              <Skeleton
                key={s.slot}
                variant="rounded"
                height={232}
                sx={{ flex: 1, minWidth: 240 }}
              />
            ) : (
              <BrandingSlotCard
                key={s.slot}
                slot={s.slot}
                label={s.label}
                description={s.description}
                dark={s.dark}
                source={s.asset.source}
                url={s.asset.url}
                onNotify={notify}
              />
            ),
          )}
        </Stack>
      )}

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}

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

import { useRef, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ImageOff, Trash2, Upload } from 'lucide-react';
import { BRANDING_ACCEPT, BRANDING_MAX_SIZE_BYTES, type BrandingSlot } from '../../../api/branding';
import { useDeleteBrandingAsset, useUploadBrandingAsset } from '../../../api/hooks/useBranding';
import { apiErrorMessage } from '../../../utils/apiError';
import { ConfirmDialog } from '../ConfirmDialog';
import { ToneBadge } from '../ToneBadge';

interface BrandingSlotCardProps {
  slot: BrandingSlot;
  label: string;
  description: string;
  /** Where the effective asset comes from — drives the badge and the Replace/Remove affordances. */
  source: 'CUSTOM' | 'DEFAULT';
  /** Effective asset URL (custom upload or factory default), with a cache-busting `?v=` token. */
  url: string;
  /** Render the preview on a dark surface (for the dark-mode logo). */
  dark?: boolean;
  onNotify: (message: string, severity: 'success' | 'error') => void;
}

const ALLOWED = BRANDING_ACCEPT.split(',');

/**
 * One branding slot: a live preview of the effective logo (custom upload or factory default),
 * clearly badged as one or the other, with upload/replace and — only for a custom upload — remove.
 * The source and URL come from the server config (the single source of truth), so the card never
 * guesses "has asset" from an image load.
 */
export function BrandingSlotCard({
  slot,
  label,
  description,
  source,
  url,
  dark,
  onNotify,
}: BrandingSlotCardProps) {
  const theme = useTheme();
  const upload = useUploadBrandingAsset();
  const remove = useDeleteBrandingAsset();
  const inputRef = useRef<HTMLInputElement>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Show a skeleton until the current URL's image loads; an error shows a fallback rather than an
  // endless skeleton. Comparing against the URL resets both whenever the effective asset changes.
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const loaded = loadedUrl === url;
  const failed = failedUrl === url;

  const isCustom = source === 'CUSTOM';
  const busy = upload.isPending || remove.isPending;

  const onPick = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!ALLOWED.includes(file.type)) {
      onNotify('Unsupported file type. Use PNG, WebP or SVG.', 'error');
      return;
    }
    if (file.size > BRANDING_MAX_SIZE_BYTES) {
      onNotify('File is too large (max 512 KiB).', 'error');
      return;
    }
    try {
      await upload.mutateAsync({ slot, file });
      onNotify(`${label} updated.`, 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The upload failed.'), 'error');
    }
  };

  const onDelete = async () => {
    setConfirmDelete(false);
    try {
      await remove.mutateAsync(slot);
      onNotify(`${label} reset to default.`, 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The asset could not be removed.'), 'error');
    }
  };

  return (
    <Paper
      variant="outlined"
      sx={{ p: 2.5, flex: 1, minWidth: 240, display: 'flex', flexDirection: 'column' }}
    >
      <Stack spacing={2} sx={{ flex: 1 }}>
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between' }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontWeight: 600 }}>{label}</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 13 }}>
              {description}
            </Typography>
          </Box>
          <ToneBadge tone={isCustom ? 'blue' : 'neutral'} label={isCustom ? 'Custom' : 'Default'} />
        </Stack>

        <Box
          sx={{
            position: 'relative',
            height: 120,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 1.5,
            border: 1,
            borderColor: 'divider',
            bgcolor: dark ? theme.qnop.brand.navy : theme.qnop.surface2,
            overflow: 'hidden',
            p: 1.5,
          }}
        >
          {!loaded && !failed && <Skeleton variant="rounded" width="55%" height={26} />}
          {failed && (
            <Stack spacing={0.5} sx={{ alignItems: 'center', color: 'text.disabled' }}>
              <ImageOff size={22} />
              <Typography sx={{ fontSize: 12 }}>Preview unavailable</Typography>
            </Stack>
          )}
          <Box
            component="img"
            key={url}
            src={url}
            alt={`${label} preview`}
            onLoad={() => setLoadedUrl(url)}
            onError={() => setFailedUrl(url)}
            sx={{
              maxHeight: '100%',
              maxWidth: '100%',
              objectFit: 'contain',
              display: loaded && !failed ? 'block' : 'none',
            }}
          />
        </Box>

        <Stack direction="row" spacing={1} sx={{ mt: 'auto' }}>
          <Button
            size="small"
            variant={isCustom ? 'outlined' : 'contained'}
            startIcon={<Upload size={15} />}
            onClick={() => inputRef.current?.click()}
            disabled={busy}
          >
            {isCustom ? 'Replace' : 'Upload'}
          </Button>
          {isCustom && (
            <Button
              size="small"
              color="error"
              startIcon={<Trash2 size={15} />}
              onClick={() => setConfirmDelete(true)}
              disabled={busy}
            >
              Remove
            </Button>
          )}
          <input
            ref={inputRef}
            type="file"
            accept={BRANDING_ACCEPT}
            hidden
            aria-label={`Upload ${label}`}
            onChange={onPick}
          />
        </Stack>
      </Stack>

      <ConfirmDialog
        open={confirmDelete}
        title="Remove logo"
        message={`Remove the custom ${label.toLowerCase()}? The app falls back to the default ${label.toLowerCase()}.`}
        confirmLabel="Remove"
        destructive
        onConfirm={onDelete}
        onClose={() => setConfirmDelete(false)}
      />
    </Paper>
  );
}

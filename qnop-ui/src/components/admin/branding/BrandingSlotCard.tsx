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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ImageOff, Trash2, Upload } from 'lucide-react';
import {
  BRANDING_ACCEPT,
  BRANDING_MAX_SIZE_BYTES,
  brandingAssetUrl,
  type BrandingSlot,
} from '../../../api/branding';
import { useDeleteBrandingAsset, useUploadBrandingAsset } from '../../../api/hooks/useBranding';
import { apiErrorMessage } from '../../../utils/apiError';
import { ConfirmDialog } from '../ConfirmDialog';

interface BrandingSlotCardProps {
  slot: BrandingSlot;
  label: string;
  description: string;
  /** Render the preview on a dark surface (for the dark-mode logo). */
  dark?: boolean;
  onNotify: (message: string, severity: 'success' | 'error') => void;
}

const ALLOWED = BRANDING_ACCEPT.split(',');

/** One branding slot: live preview, upload (with client-side validation), and delete. */
export function BrandingSlotCard({
  slot,
  label,
  description,
  dark,
  onNotify,
}: BrandingSlotCardProps) {
  const upload = useUploadBrandingAsset();
  const remove = useDeleteBrandingAsset();
  const inputRef = useRef<HTMLInputElement>(null);

  // Bumped after every change to bust the browser cache for the preview <img>.
  const [version, setVersion] = useState(1);
  const [hasAsset, setHasAsset] = useState<boolean | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

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
      setHasAsset(true);
      setVersion((v) => v + 1);
      onNotify(`${label} updated.`, 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The upload failed.'), 'error');
    }
  };

  const onDelete = async () => {
    setConfirmDelete(false);
    try {
      await remove.mutateAsync(slot);
      setHasAsset(false);
      setVersion((v) => v + 1);
      onNotify(`${label} removed.`, 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The asset could not be removed.'), 'error');
    }
  };

  const busy = upload.isPending || remove.isPending;

  return (
    <Paper variant="outlined" sx={{ p: 2.5, flex: 1, minWidth: 240 }}>
      <Stack spacing={2}>
        <Box>
          <Typography sx={{ fontWeight: 600 }}>{label}</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 13 }}>
            {description}
          </Typography>
        </Box>

        <Box
          sx={{
            height: 120,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 1.5,
            border: 1,
            borderColor: 'divider',
            bgcolor: dark ? 'grey.900' : 'background.default',
            overflow: 'hidden',
            p: 1.5,
          }}
        >
          <Box
            component="img"
            src={brandingAssetUrl(slot, version)}
            alt={`${label} preview`}
            onLoad={() => setHasAsset(true)}
            onError={() => setHasAsset(false)}
            sx={{
              maxHeight: '100%',
              maxWidth: '100%',
              objectFit: 'contain',
              display: hasAsset ? 'block' : 'none',
            }}
          />
          {hasAsset === false && (
            <Stack spacing={0.5} sx={{ alignItems: 'center', color: 'text.disabled' }}>
              <ImageOff size={22} />
              <Typography sx={{ fontSize: 12 }}>No image</Typography>
            </Stack>
          )}
        </Box>

        <Stack direction="row" spacing={1}>
          <Button
            size="small"
            variant="outlined"
            startIcon={<Upload size={15} />}
            onClick={() => inputRef.current?.click()}
            disabled={busy}
          >
            {hasAsset ? 'Replace' : 'Upload'}
          </Button>
          {hasAsset && (
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
          <input ref={inputRef} type="file" accept={BRANDING_ACCEPT} hidden onChange={onPick} />
        </Stack>
      </Stack>

      <ConfirmDialog
        open={confirmDelete}
        title="Remove asset"
        message={`Remove the ${label.toLowerCase()}? The app falls back to its default.`}
        confirmLabel="Remove"
        destructive
        onConfirm={onDelete}
        onClose={() => setConfirmDelete(false)}
      />
    </Paper>
  );
}

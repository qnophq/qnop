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

import { useRef, useState, type ChangeEvent, type DragEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ImageOff, Trash2, Upload, UploadCloud } from 'lucide-react';
import { BRANDING_ACCEPT, BRANDING_MAX_SIZE_BYTES, type BrandingSlot } from '../../../api/branding';
import { useDeleteBrandingAsset, useUploadBrandingAsset } from '../../../api/hooks/useBranding';
import { apiErrorMessage } from '../../../utils/apiError';
import { ConfirmDialog } from '../ConfirmDialog';
import { ToneBadge } from '../ToneBadge';
import { BrandingCropDialog, type AspectPreset, type AspectValue } from './BrandingCropDialog';

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
const SVG_TYPE = 'image/svg+xml';

/** Per-slot crop framing: a logomark is square; wordmark logos keep their own ratio or a banner. */
const WORDMARK_PRESETS: AspectPreset[] = [
  { label: 'Original', value: 'original' },
  { label: '3 : 1', value: 3 },
  { label: '16 : 9', value: 16 / 9 },
  { label: 'Square', value: 1 },
];
const SLOT_CROP: Record<
  BrandingSlot,
  { presets: AspectPreset[]; defaultAspect: AspectValue; maxWidth: number; maxHeight: number }
> = {
  'logo-light': {
    presets: WORDMARK_PRESETS,
    defaultAspect: 'original',
    maxWidth: 1024,
    maxHeight: 384,
  },
  'logo-dark': {
    presets: WORDMARK_PRESETS,
    defaultAspect: 'original',
    maxWidth: 1024,
    maxHeight: 384,
  },
  logomark: {
    presets: [{ label: 'Square', value: 1 }],
    defaultAspect: 1,
    maxWidth: 512,
    maxHeight: 512,
  },
};

/**
 * One branding slot: a live preview of the effective logo (custom upload or factory default),
 * clearly badged as one or the other, with upload/replace and — only for a custom upload — remove.
 * Raster uploads (drag-and-drop or browse) open a per-slot cropper so the exact framing is cut;
 * SVG is vector and uploaded as-is. The source and URL come from the server config (the single
 * source of truth), so the card never guesses "has asset" from an image load.
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
  const [dragOver, setDragOver] = useState(false);
  const [cropSrc, setCropSrc] = useState<string | null>(null);

  // Show a skeleton until the current URL's image loads; an error shows a fallback rather than an
  // endless skeleton. Comparing against the URL resets both whenever the effective asset changes.
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const loaded = loadedUrl === url;
  const failed = failedUrl === url;

  const isCustom = source === 'CUSTOM';
  const busy = upload.isPending || remove.isPending;
  const cropConfig = SLOT_CROP[slot];

  const doUpload = async (file: Blob, filename: string) => {
    try {
      await upload.mutateAsync({ slot, file, filename });
      onNotify(`${label} updated.`, 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The upload failed.'), 'error');
    }
  };

  const accept = (file: File) => {
    if (!ALLOWED.includes(file.type)) {
      onNotify('Unsupported file type. Use PNG, WebP or SVG.', 'error');
      return;
    }
    if (file.size > BRANDING_MAX_SIZE_BYTES) {
      onNotify('File is too large (max 512 KiB).', 'error');
      return;
    }
    if (file.type === SVG_TYPE) {
      // SVG is vector — rasterizing it to crop would lose quality, so upload it as-is.
      void doUpload(file, file.name || 'logo.svg');
      return;
    }
    setCropSrc(URL.createObjectURL(file));
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file) accept(file);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragOver(false);
    if (busy) return;
    const file = event.dataTransfer.files?.[0];
    if (file) accept(file);
  };

  const closeCrop = () => {
    if (cropSrc) URL.revokeObjectURL(cropSrc);
    setCropSrc(null);
  };

  const onCropped = async (blob: Blob) => {
    closeCrop();
    if (blob.size > BRANDING_MAX_SIZE_BYTES) {
      onNotify('The cropped image is too large (max 512 KiB). Try a tighter crop.', 'error');
      return;
    }
    await doUpload(blob, 'logo.png');
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
          onDragOver={(e) => {
            e.preventDefault();
            if (!busy) setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          sx={{
            position: 'relative',
            height: 120,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 1.5,
            border: dragOver ? '1.5px dashed' : '1px solid',
            borderColor: dragOver ? 'primary.main' : 'divider',
            bgcolor: dark ? theme.qnop.brand.navy : theme.qnop.surface2,
            overflow: 'hidden',
            p: 1.5,
            transition: 'border-color 150ms ease',
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
          {dragOver && (
            <Stack
              spacing={0.5}
              sx={{
                position: 'absolute',
                inset: 0,
                alignItems: 'center',
                justifyContent: 'center',
                bgcolor: 'rgba(18,146,238,0.10)',
                color: 'primary.main',
                pointerEvents: 'none',
              }}
            >
              <UploadCloud size={22} />
              <Typography sx={{ fontSize: 12, fontWeight: 600 }}>Drop to upload</Typography>
            </Stack>
          )}
        </Box>

        <Stack direction="row" spacing={1} sx={{ mt: 'auto', alignItems: 'center' }}>
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
          <Typography sx={{ fontSize: 12, color: 'text.disabled', ml: 'auto' }}>
            or drag &amp; drop
          </Typography>
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

      {cropSrc && (
        <BrandingCropDialog
          open
          imageSrc={cropSrc}
          label={label}
          presets={cropConfig.presets}
          defaultAspect={cropConfig.defaultAspect}
          maxWidth={cropConfig.maxWidth}
          maxHeight={cropConfig.maxHeight}
          busy={busy}
          onCancel={closeCrop}
          onCropped={onCropped}
        />
      )}

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

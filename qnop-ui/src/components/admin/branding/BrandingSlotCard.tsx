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
import type { ParseKeys } from 'i18next';
import { ImageOff, Trash2, Upload, UploadCloud } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { BRANDING_ACCEPT, BRANDING_MAX_SIZE_BYTES, type BrandingSlot } from '../../../api/branding';
import { useDeleteBrandingAsset, useUploadBrandingAsset } from '../../../api/hooks/useBranding';
import { apiErrorMessage } from '../../../utils/apiError';
import { ConfirmDialog } from '../ConfirmDialog';
import { ToneBadge } from '../ToneBadge';
import { BrandingCropDialog } from './BrandingCropDialog';

interface BrandingSlotCardProps {
  slot: BrandingSlot;
  /** The slot's display name, e.g. as a card heading (already translated). */
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

/**
 * Per-slot crop framing: the logomark locks to a square; wordmark logos crop freely. The output is
 * bounded so the saved PNG stays well under the 512 KiB cap, and the recommendation is surfaced in
 * the cropper so the operator knows the target size.
 */
const SLOT_CROP: Record<
  BrandingSlot,
  { aspect?: number; maxWidth: number; maxHeight: number; recommendedKey: ParseKeys<'admin'> }
> = {
  'logo-light': {
    maxWidth: 1024,
    maxHeight: 384,
    recommendedKey: 'branding.crop.recommendedWordmark',
  },
  'logo-dark': {
    maxWidth: 1024,
    maxHeight: 384,
    recommendedKey: 'branding.crop.recommendedWordmark',
  },
  logomark: {
    aspect: 1,
    maxWidth: 512,
    maxHeight: 512,
    recommendedKey: 'branding.crop.recommendedLogomark',
  },
};

/**
 * The slot's name as it reads inside a sentence (`admin` catalog key). English lower-cases it
 * ("the custom logo (light)"); German keeps the noun capitalised — so it is its own key rather
 * than a `toLowerCase()` of the label.
 */
const SLOT_INLINE_NAME: Record<BrandingSlot, ParseKeys<'admin'>> = {
  'logo-light': 'branding.slots.logoLight.inline',
  'logo-dark': 'branding.slots.logoDark.inline',
  logomark: 'branding.slots.logomark.inline',
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
  const { t } = useTranslation(['admin', 'common']);
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
  const inlineName = t(SLOT_INLINE_NAME[slot]);

  const doUpload = async (file: Blob, filename: string, successMessage?: string) => {
    try {
      await upload.mutateAsync({ slot, file, filename });
      onNotify(successMessage ?? t('branding.toast.updated', { label }), 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, t('branding.errors.upload')), 'error');
    }
  };

  const accept = (file: File) => {
    if (!ALLOWED.includes(file.type)) {
      onNotify(t('branding.errors.unsupportedType'), 'error');
      return;
    }
    if (file.size > BRANDING_MAX_SIZE_BYTES) {
      onNotify(t('branding.errors.tooLarge'), 'error');
      return;
    }
    if (file.type === SVG_TYPE) {
      // SVG is vector — rasterizing it to crop would lose quality, so upload it as-is and say so.
      void doUpload(file, file.name || 'logo.svg', t('branding.toast.updatedSvg', { label }));
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
      onNotify(t('branding.errors.croppedTooLarge'), 'error');
      return;
    }
    await doUpload(blob, 'logo.png');
  };

  const onDelete = async () => {
    setConfirmDelete(false);
    try {
      await remove.mutateAsync(slot);
      onNotify(t('branding.toast.reset', { label }), 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, t('branding.errors.remove')), 'error');
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
          <ToneBadge
            tone={isCustom ? 'blue' : 'neutral'}
            label={isCustom ? t('branding.source.custom') : t('branding.source.default')}
          />
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
              <Typography sx={{ fontSize: 12 }}>{t('branding.card.previewUnavailable')}</Typography>
            </Stack>
          )}
          <Box
            component="img"
            key={url}
            src={url}
            alt={t('branding.card.previewAlt', { label })}
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
              <Typography sx={{ fontSize: 12, fontWeight: 600 }}>
                {t('branding.card.dropToUpload')}
              </Typography>
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
            {isCustom ? t('branding.card.replace') : t('branding.card.upload')}
          </Button>
          {isCustom && (
            <Button
              size="small"
              color="error"
              startIcon={<Trash2 size={15} />}
              onClick={() => setConfirmDelete(true)}
              disabled={busy}
            >
              {t('common:actions.remove')}
            </Button>
          )}
          <Typography sx={{ fontSize: 12, color: 'text.disabled', ml: 'auto' }}>
            {t('branding.card.orDragAndDrop')}
          </Typography>
          <input
            ref={inputRef}
            type="file"
            accept={BRANDING_ACCEPT}
            hidden
            aria-label={t('branding.card.uploadAria', { label })}
            onChange={onPick}
          />
        </Stack>
      </Stack>

      {cropSrc && (
        <BrandingCropDialog
          open
          imageSrc={cropSrc}
          name={inlineName}
          dark={dark}
          aspect={cropConfig.aspect}
          maxWidth={cropConfig.maxWidth}
          maxHeight={cropConfig.maxHeight}
          recommended={t(cropConfig.recommendedKey)}
          busy={busy}
          onCancel={closeCrop}
          onCropped={onCropped}
        />
      )}

      <ConfirmDialog
        open={confirmDelete}
        title={t('branding.remove.title')}
        message={t('branding.remove.message', { name: inlineName })}
        confirmLabel={t('common:actions.remove')}
        destructive
        onConfirm={onDelete}
        onClose={() => setConfirmDelete(false)}
      />
    </Paper>
  );
}

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

import { useRef, useState, type DragEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ImagePlus, Trash2 } from 'lucide-react';
import { UserAvatar } from '../shell/UserAvatar';
import { ConfirmDialog } from '../admin/ConfirmDialog';
import { AvatarCropDialog } from './AvatarCropDialog';

/** Accepted types and size cap — mirror the server (AvatarLimits) for fast, local feedback. */
const ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/webp'];
const MAX_BYTES = 1024 * 1024;

interface AvatarUploaderProps {
  /** Name for the initials fallback in the preview. */
  name: string;
  /** Current avatar URL, or null. */
  imageUrl: string | null;
  /** Whether an upload/remove is in flight. */
  busy?: boolean;
  /** Called with the cropped, ready-to-upload square image. */
  onSelect: (blob: Blob) => void;
  /** Called to remove the current picture. */
  onRemove: () => void;
}

/**
 * Reusable avatar upload control (issue #117): a live circular preview plus a drag-and-drop / browse
 * dropzone that validates type and size, opens the square cropper, and emits the cropped blob.
 * Presentational — the parent owns the upload/remove mutations, so the same control serves the
 * self-service profile screen and the admin user dialog.
 */
export function AvatarUploader({ name, imageUrl, busy, onSelect, onRemove }: AvatarUploaderProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [cropSrc, setCropSrc] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);

  const accept = (file: File) => {
    setError(null);
    if (!ACCEPTED_TYPES.includes(file.type)) {
      setError('Please choose a PNG, JPEG or WebP image.');
      return;
    }
    if (file.size > MAX_BYTES) {
      setError('That image is larger than 1 MB.');
      return;
    }
    setCropSrc(URL.createObjectURL(file));
  };

  const onInputChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) accept(file);
    event.target.value = ''; // allow re-picking the same file
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

  const onCropped = (blob: Blob) => {
    onSelect(blob);
    closeCrop();
  };

  return (
    <Stack spacing={2}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={3}
        sx={{ alignItems: { sm: 'center' } }}
      >
        <Box
          sx={{ position: 'relative', flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'auto' } }}
        >
          <UserAvatar name={name} size={96} imageUrl={imageUrl} />
          {busy && (
            <Box
              sx={{
                position: 'absolute',
                inset: 0,
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                bgcolor: 'rgba(0,0,0,0.45)',
              }}
            >
              <CircularProgress size={28} sx={{ color: '#fff' }} />
            </Box>
          )}
        </Box>

        <Box sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <Box
            role="button"
            tabIndex={0}
            aria-label="Upload a profile picture"
            onClick={() => !busy && inputRef.current?.click()}
            onKeyDown={(e) => {
              if ((e.key === 'Enter' || e.key === ' ') && !busy) {
                e.preventDefault();
                inputRef.current?.click();
              }
            }}
            onDragOver={(e) => {
              e.preventDefault();
              if (!busy) setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 0.75,
              textAlign: 'center',
              px: 2,
              py: 2.5,
              cursor: busy ? 'default' : 'pointer',
              borderRadius: 2.5,
              border: '1.5px dashed',
              borderColor: dragOver ? 'primary.main' : 'divider',
              bgcolor: dragOver ? 'action.hover' : 'transparent',
              color: dragOver ? 'primary.main' : 'text.secondary',
              transition: 'border-color 150ms ease, background-color 150ms ease, color 150ms ease',
              outline: 'none',
              '&:hover': busy ? {} : { borderColor: 'primary.main', color: 'primary.main' },
              '&:focus-visible': { borderColor: 'primary.main', color: 'primary.main' },
            }}
          >
            <ImagePlus size={22} />
            <Typography sx={{ fontSize: 14 }}>
              Drag an image here or{' '}
              <Box component="span" sx={{ fontWeight: 600 }}>
                browse
              </Box>
            </Typography>
            <Typography sx={{ fontSize: 12, color: 'text.disabled' }}>
              PNG, JPEG or WebP · up to 1 MB
            </Typography>
          </Box>

          {imageUrl && (
            <Button
              size="small"
              color="error"
              startIcon={<Trash2 size={15} />}
              onClick={() => setConfirmRemove(true)}
              disabled={busy}
              sx={{ mt: 1, textTransform: 'none' }}
            >
              Remove photo
            </Button>
          )}
        </Box>
      </Stack>

      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPTED_TYPES.join(',')}
        hidden
        onChange={onInputChange}
      />

      {cropSrc && (
        <AvatarCropDialog
          open
          imageSrc={cropSrc}
          busy={busy}
          onCancel={closeCrop}
          onCropped={onCropped}
        />
      )}

      <ConfirmDialog
        open={confirmRemove}
        title="Remove profile picture"
        message="This removes the profile picture and falls back to the initials avatar. A new one can be uploaded anytime."
        confirmLabel="Remove"
        destructive
        onConfirm={() => {
          setConfirmRemove(false);
          onRemove();
        }}
        onClose={() => setConfirmRemove(false)}
      />
    </Stack>
  );
}

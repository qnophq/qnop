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

import { useEffect, useRef, useState, type SyntheticEvent } from 'react';
import ReactCrop, { centerCrop, makeAspectCrop, type Crop, type PixelCrop } from 'react-image-crop';
import 'react-image-crop/dist/ReactCrop.css';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Crop as CropIcon } from 'lucide-react';

interface BrandingCropDialogProps {
  open: boolean;
  imageSrc: string;
  label: string;
  /** Preview the result on the slot's surface (dark for the dark-mode logo). */
  dark?: boolean;
  /** Locked aspect (e.g. 1 for the square logomark); omit for a free-form crop (wordmark logos). */
  aspect?: number;
  /** Output bounds — the crop is scaled to fit, so the saved PNG stays well under 512 KiB. */
  maxWidth: number;
  maxHeight: number;
  /** One-line guidance shown above the cropper. */
  recommended: string;
  busy?: boolean;
  onCancel: () => void;
  onCropped: (blob: Blob) => void;
}

/**
 * Crops a raster branding logo before upload (issue #106). Unlike the square avatar cropper, this
 * uses a handle-based selection (drag a box around exactly the part you want) with a live readout of
 * the resulting pixel size and a preview of the cropped result on the slot's surface — so it is
 * obvious which size is being cut. The logomark locks to a square; wordmark logos crop freely.
 * The preview canvas is also the exported PNG, so what you see is exactly what is saved.
 */
export function BrandingCropDialog({
  open,
  imageSrc,
  label,
  dark,
  aspect,
  maxWidth,
  maxHeight,
  recommended,
  busy,
  onCancel,
  onCropped,
}: BrandingCropDialogProps) {
  const theme = useTheme();
  const imgRef = useRef<HTMLImageElement | null>(null);
  const previewRef = useRef<HTMLCanvasElement | null>(null);
  const [crop, setCrop] = useState<Crop>();
  const [completed, setCompleted] = useState<PixelCrop>();
  const [output, setOutput] = useState<{ width: number; height: number } | null>(null);
  const [exporting, setExporting] = useState(false);

  const disabled = busy || exporting;

  const onImageLoad = (event: SyntheticEvent<HTMLImageElement>) => {
    const { width, height } = event.currentTarget;
    const initial: Crop = aspect
      ? centerCrop(makeAspectCrop({ unit: '%', width: 80 }, aspect, width, height), width, height)
      : { unit: '%', x: 5, y: 5, width: 90, height: 90 };
    setCrop(initial);
  };

  // Draw the selection onto the preview canvas at the final (bounded) output size. This canvas is
  // both the on-screen preview and the exported PNG, so they can never disagree.
  useEffect(() => {
    const image = imgRef.current;
    const canvas = previewRef.current;
    if (!completed || !image || !canvas || completed.width === 0 || completed.height === 0) return;
    const scaleX = image.naturalWidth / image.width;
    const scaleY = image.naturalHeight / image.height;
    const cropW = completed.width * scaleX;
    const cropH = completed.height * scaleY;
    const scale = Math.min(1, maxWidth / cropW, maxHeight / cropH);
    const outW = Math.max(1, Math.round(cropW * scale));
    const outH = Math.max(1, Math.round(cropH * scale));
    canvas.width = outW;
    canvas.height = outH;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, outW, outH);
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(
      image,
      completed.x * scaleX,
      completed.y * scaleY,
      cropW,
      cropH,
      0,
      0,
      outW,
      outH,
    );
    setOutput({ width: outW, height: outH });
  }, [completed, maxWidth, maxHeight]);

  const apply = () => {
    const canvas = previewRef.current;
    if (!canvas || !output) return;
    setExporting(true);
    canvas.toBlob((blob) => {
      setExporting(false);
      if (blob) onCropped(blob);
    }, 'image/png');
  };

  return (
    <Dialog open={open} onClose={disabled ? undefined : onCancel} maxWidth="sm" fullWidth>
      <DialogTitle>Crop {label.toLowerCase()}</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1.5 }}>
          {aspect
            ? 'Drag the square handles over your mark. '
            : 'Drag a box around exactly the part you want. '}
          {recommended}
        </Typography>

        <Box
          sx={{
            display: 'flex',
            justifyContent: 'center',
            bgcolor: 'common.black',
            borderRadius: 2,
            p: 1,
            '& .ReactCrop__image': { maxHeight: 320 },
          }}
        >
          <ReactCrop
            crop={crop}
            aspect={aspect}
            keepSelection
            minWidth={24}
            minHeight={24}
            onChange={(_, percentCrop) => setCrop(percentCrop)}
            onComplete={(pixelCrop) => setCompleted(pixelCrop)}
          >
            <img
              ref={imgRef}
              src={imageSrc}
              alt=""
              onLoad={onImageLoad}
              style={{ maxHeight: 320, maxWidth: '100%', display: 'block' }}
            />
          </ReactCrop>
        </Box>

        <Stack direction="row" spacing={2} sx={{ mt: 2, alignItems: 'center' }}>
          <Box
            sx={{
              width: 132,
              height: 72,
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: 1.5,
              border: 1,
              borderColor: 'divider',
              bgcolor: dark ? theme.qnop.brand.navy : theme.qnop.surface2,
              overflow: 'hidden',
              p: 0.75,
            }}
          >
            <canvas
              ref={previewRef}
              style={{ maxWidth: '100%', maxHeight: '100%', display: output ? 'block' : 'none' }}
            />
          </Box>
          <Box>
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              Saved size (transparent PNG)
            </Typography>
            <Typography sx={{ fontWeight: 600, fontSize: 15 }}>
              {output ? `${output.width} × ${output.height} px` : '—'}
            </Typography>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onCancel} color="inherit" disabled={disabled}>
          Cancel
        </Button>
        <Button
          onClick={apply}
          variant="contained"
          startIcon={<CropIcon size={16} />}
          disabled={disabled || !output}
        >
          Use selection
        </Button>
      </DialogActions>
    </Dialog>
  );
}

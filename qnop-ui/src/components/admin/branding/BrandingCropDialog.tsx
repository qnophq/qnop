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

import { useCallback, useState } from 'react';
import Cropper, { type Area, type MediaSize } from 'react-easy-crop';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Slider from '@mui/material/Slider';
import Stack from '@mui/material/Stack';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';
import { ZoomIn } from 'lucide-react';
import { getCroppedImageBlob } from '../../../utils/cropImage';

export type AspectValue = number | 'original';
export interface AspectPreset {
  label: string;
  value: AspectValue;
}

interface BrandingCropDialogProps {
  open: boolean;
  imageSrc: string;
  label: string;
  presets: AspectPreset[];
  defaultAspect: AspectValue;
  /** Output bounds (the crop is scaled to fit, preserving its aspect). */
  maxWidth: number;
  maxHeight: number;
  busy?: boolean;
  onCancel: () => void;
  onCropped: (blob: Blob) => void;
}

const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

/**
 * Crops a raster branding logo to a chosen aspect before upload (issue #106). Unlike the square
 * avatar cropper, the aspect is configurable per slot — a logomark is square, while wordmark logos
 * keep their own proportions ("Original") or a banner ratio. Exports a transparent PNG bounded to
 * the slot's output size so it stays well under the 512 KiB cap.
 */
export function BrandingCropDialog({
  open,
  imageSrc,
  label,
  presets,
  defaultAspect,
  maxWidth,
  maxHeight,
  busy,
  onCancel,
  onCropped,
}: BrandingCropDialogProps) {
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [area, setArea] = useState<Area | null>(null);
  const [selected, setSelected] = useState<AspectValue>(defaultAspect);
  const [originalAspect, setOriginalAspect] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);

  const aspect = selected === 'original' ? (originalAspect ?? 1) : selected;
  const disabled = busy || exporting;

  const onCropComplete = useCallback((_: Area, areaPixels: Area) => setArea(areaPixels), []);
  const onMediaLoaded = useCallback(
    (media: MediaSize) => setOriginalAspect(media.naturalWidth / media.naturalHeight),
    [],
  );

  const apply = async () => {
    if (!area) return;
    setExporting(true);
    try {
      onCropped(
        await getCroppedImageBlob(imageSrc, area, { maxWidth, maxHeight, type: 'image/png' }),
      );
    } finally {
      setExporting(false);
    }
  };

  return (
    <Dialog open={open} onClose={disabled ? undefined : onCancel} maxWidth="sm" fullWidth>
      <DialogTitle>Crop {label.toLowerCase()}</DialogTitle>
      <DialogContent>
        {presets.length > 1 && (
          <ToggleButtonGroup
            size="small"
            exclusive
            value={selected}
            onChange={(_, value) => value != null && setSelected(value as AspectValue)}
            sx={{ mb: 2, flexWrap: 'wrap' }}
            aria-label="Crop aspect ratio"
          >
            {presets.map((p) => (
              <ToggleButton key={String(p.value)} value={p.value} sx={{ textTransform: 'none' }}>
                {p.label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        )}

        <Box
          sx={{
            position: 'relative',
            width: '100%',
            height: 300,
            bgcolor: 'common.black',
            borderRadius: 2,
            overflow: 'hidden',
          }}
        >
          <Cropper
            image={imageSrc}
            crop={crop}
            zoom={zoom}
            aspect={aspect}
            cropShape="rect"
            showGrid
            restrictPosition={false}
            onCropChange={setCrop}
            onZoomChange={setZoom}
            onCropComplete={onCropComplete}
            onMediaLoaded={onMediaLoaded}
          />
        </Box>

        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 2 }}>
          <ZoomIn size={18} />
          <Slider
            min={MIN_ZOOM}
            max={MAX_ZOOM}
            step={0.01}
            value={zoom}
            onChange={(_, value) => setZoom(value as number)}
            aria-label="Zoom"
            disabled={disabled}
          />
        </Stack>
        <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
          Drag to reposition, zoom to crop, and pick a ratio. Exported as a transparent PNG.
        </Typography>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onCancel} color="inherit" disabled={disabled}>
          Cancel
        </Button>
        <Button onClick={apply} variant="contained" disabled={disabled || !area}>
          Use selection
        </Button>
      </DialogActions>
    </Dialog>
  );
}

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
import Cropper, { type Area } from 'react-easy-crop';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Slider from '@mui/material/Slider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ZoomIn } from 'lucide-react';
import { getCroppedAvatarBlob } from '../../utils/cropImage';

interface AvatarCropDialogProps {
  open: boolean;
  imageSrc: string;
  busy?: boolean;
  onCancel: () => void;
  onCropped: (blob: Blob) => void;
}

const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

/**
 * Square avatar cropper (issue #117): pan + zoom over the picked image with a round mask, then
 * export the selection as a canonical square via {@link getCroppedAvatarBlob}.
 */
export function AvatarCropDialog({
  open,
  imageSrc,
  busy,
  onCancel,
  onCropped,
}: AvatarCropDialogProps) {
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [area, setArea] = useState<Area | null>(null);
  const [exporting, setExporting] = useState(false);

  const onCropComplete = useCallback((_: Area, areaPixels: Area) => setArea(areaPixels), []);

  const apply = async () => {
    if (!area) return;
    setExporting(true);
    try {
      onCropped(await getCroppedAvatarBlob(imageSrc, area));
    } finally {
      setExporting(false);
    }
  };

  const disabled = busy || exporting;

  return (
    <Dialog open={open} onClose={disabled ? undefined : onCancel} maxWidth="xs" fullWidth>
      <DialogTitle>Adjust your picture</DialogTitle>
      <DialogContent>
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
            aspect={1}
            cropShape="round"
            showGrid={false}
            onCropChange={setCrop}
            onZoomChange={setZoom}
            onCropComplete={onCropComplete}
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
          Drag to reposition, use the slider to zoom.
        </Typography>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onCancel} color="inherit" disabled={disabled}>
          Cancel
        </Button>
        <Button onClick={apply} variant="contained" disabled={disabled || !area}>
          Use photo
        </Button>
      </DialogActions>
    </Dialog>
  );
}

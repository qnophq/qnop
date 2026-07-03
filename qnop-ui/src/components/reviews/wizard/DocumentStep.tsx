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

import { useRef, useState } from 'react';
import type { DragEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { FileText, Upload, X } from 'lucide-react';
import { formatFileSize } from './wizardModel';

interface DocumentStepProps {
  file: File | null;
  title: string;
  /** Validation error from the last file pick, if any. */
  fileError: string | null;
  maxSizeMb: number;
  onFilePicked: (file: File) => void;
  onFileCleared: () => void;
  onTitleChange: (title: string) => void;
}

/** Step 1 — pick the PDF (dropzone or picker) and name the review. */
export function DocumentStep({
  file,
  title,
  fileError,
  maxSizeMb,
  onFilePicked,
  onFileCleared,
  onTitleChange,
}: DocumentStepProps) {
  const theme = useTheme();
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);

  const handleDrop = (event: DragEvent) => {
    event.preventDefault();
    setIsDragOver(false);
    const dropped = event.dataTransfer.files[0];
    if (dropped) onFilePicked(dropped);
  };

  return (
    <Stack spacing={2.5}>
      {!file ? (
        <Box
          data-testid="wizard-dropzone"
          onClick={() => inputRef.current?.click()}
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragOver(true);
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
          sx={{
            border: '2px dashed',
            borderColor: isDragOver ? theme.qnop.brand.blue : theme.palette.divider,
            borderRadius: 3,
            px: 3,
            py: 7,
            textAlign: 'center',
            cursor: 'pointer',
            bgcolor: isDragOver ? theme.palette.primary.light : 'transparent',
            transition: 'border-color 160ms ease, background-color 160ms ease',
          }}
        >
          <Box
            sx={{
              width: 60,
              height: 60,
              borderRadius: 3.5,
              bgcolor: theme.palette.primary.light,
              color: theme.qnop.brand.blue,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              mb: 2,
            }}
          >
            <Upload size={26} aria-hidden />
          </Box>
          <Typography sx={{ fontWeight: 600, fontSize: 18, mb: 0.5 }}>
            Drop your document here or choose a file
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            PDF · max. {maxSizeMb} MB
          </Typography>
          <Button variant="contained" startIcon={<Upload size={16} />}>
            Choose file
          </Button>
        </Box>
      ) : (
        <Stack
          direction="row"
          spacing={1.75}
          sx={{
            alignItems: 'center',
            p: 2,
            borderRadius: 2,
            bgcolor: theme.qnop.surface2,
          }}
        >
          <Box
            sx={{
              width: 44,
              height: 44,
              borderRadius: 2,
              bgcolor: theme.qnop.brand.blue,
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
            }}
          >
            <FileText size={20} aria-hidden />
          </Box>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="body2" noWrap sx={{ fontWeight: 500 }}>
              {file.name}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {formatFileSize(file.size)}
            </Typography>
          </Box>
          <Tooltip title="Remove file">
            <IconButton size="small" onClick={onFileCleared} aria-label="Remove file">
              <X size={16} />
            </IconButton>
          </Tooltip>
        </Stack>
      )}

      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
        hidden
        data-testid="wizard-file-input"
        onChange={(e) => {
          const picked = e.target.files?.[0];
          if (picked) onFilePicked(picked);
          e.target.value = '';
        }}
      />

      {fileError && <Alert severity="error">{fileError}</Alert>}

      <TextField
        label="Review title"
        value={title}
        onChange={(e) => onTitleChange(e.target.value)}
        placeholder="e.g. NDA Acme Corp"
        helperText="Shown in the overview and to every reviewer."
        fullWidth
      />
    </Stack>
  );
}

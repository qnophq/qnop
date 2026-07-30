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
import type { DragEvent, ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { FileText, Upload, X } from 'lucide-react';
import { formatFileSize, type AcceptedUploads } from '../wizard/wizardModel';

interface DocumentDropzoneProps {
  file: File | null;
  /** Validation error from the last pick, if any. */
  error?: string | null;
  maxSizeMb: number;
  /** Which formats this server takes, and how to name them (issue #343). */
  accepted: AcceptedUploads;
  onFilePicked: (file: File) => void;
  onFileCleared: () => void;
  /** Locks the control while its file is being uploaded. */
  disabled?: boolean;
  /**
   * How much of the surface this control is.
   *
   * <p>`inline` (the wizard): the frame is the empty state, and a chosen file
   * collapses to a compact row so the fields below move up to meet it.
   *
   * <p>`stage` (the dialog): the control *is* the content, so the frame stays and
   * the chosen file is confirmed inside it. Collapsing there would drop ~200px out
   * of the dialog and make it jump as it re-centres; merely padding the gap would
   * leave the file floating in dead space. Keeping the frame also keeps it a drop
   * target, so a second file replaces the first.
   */
  variant?: 'inline' | 'stage';
  /** Distinguishes the two mounts for tests; the wizard and the dialog differ. */
  testId?: string;
}

/**
 * Choosing a document: a drop target, and then the document that was chosen.
 *
 * <p>Shared on purpose (issue #652). Starting a review and uploading a new version
 * into one are the same act, and they used to look like different products — the
 * wizard had this, the review hub had a bare file chooser. One component rather
 * than two copies is what keeps them from drifting the next time either is
 * touched.
 *
 * <p>The picked file is shown rather than sent: the caller decides when it goes,
 * so there is a moment in which the wrong file can be noticed.
 */
export function DocumentDropzone({
  file,
  error,
  maxSizeMb,
  accepted,
  onFilePicked,
  onFileCleared,
  disabled = false,
  variant = 'inline',
  testId = 'document-dropzone',
}: DocumentDropzoneProps) {
  const theme = useTheme();
  const inputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const staged = variant === 'stage';

  const openPicker = () => {
    if (!disabled) inputRef.current?.click();
  };

  const handleDrop = (event: DragEvent) => {
    event.preventDefault();
    setIsDragOver(false);
    if (disabled) return;
    const dropped = event.dataTransfer.files[0];
    if (dropped) onFilePicked(dropped);
  };

  /** The dashed drop target, whatever it currently holds. */
  const frame = (children: ReactNode) => (
    <Box
      data-testid={testId}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-label={file ? 'Choose a different document' : 'Choose a document'}
      aria-disabled={disabled || undefined}
      onClick={openPicker}
      onKeyDown={(event) => {
        // A div that behaves like a button has to answer the keyboard like one.
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          openPicker();
        }
      }}
      onDragOver={(event) => {
        event.preventDefault();
        if (!disabled) setIsDragOver(true);
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={handleDrop}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: staged ? 260 : undefined,
        border: '2px dashed',
        borderColor: isDragOver ? theme.qnop.brand.blue : theme.palette.divider,
        borderRadius: 3,
        px: 3,
        py: staged ? 4 : 7,
        textAlign: 'center',
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.6 : 1,
        bgcolor: isDragOver ? theme.palette.primary.light : 'transparent',
        transition: 'border-color 160ms ease, background-color 160ms ease',
        '&:focus-visible': {
          outline: `2px solid ${theme.qnop.brand.blue}`,
          outlineOffset: 2,
        },
      }}
    >
      {children}
    </Box>
  );

  const prompt = (
    <>
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
        {accepted.label} · max. {maxSizeMb} MB
      </Typography>
      <Button variant="contained" startIcon={<Upload size={16} />} disabled={disabled}>
        Choose file
      </Button>
    </>
  );

  /** The chosen file, filling the frame it was dropped into. */
  const confirmation = file && (
    <>
      <Box
        sx={{
          width: 60,
          height: 60,
          borderRadius: 3.5,
          bgcolor: theme.qnop.brand.blue,
          color: '#fff',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          mb: 2,
        }}
      >
        <FileText size={26} aria-hidden />
      </Box>
      <Typography sx={{ fontWeight: 600, fontSize: 17, mb: 0.25, wordBreak: 'break-all' }}>
        {file.name}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {formatFileSize(file.size)}
      </Typography>
      <Button
        variant="outlined"
        size="small"
        disabled={disabled}
        onClick={(event) => {
          // The frame itself opens the picker; this clears the choice instead.
          event.stopPropagation();
          onFileCleared();
        }}
      >
        Choose a different file
      </Button>
    </>
  );

  /** The compact row the wizard collapses to, so its fields move up to meet it. */
  const row = file && (
    <Stack
      direction="row"
      spacing={1.75}
      sx={{ alignItems: 'center', p: 2, borderRadius: 2, bgcolor: theme.qnop.surface2 }}
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
        <span>
          <IconButton
            size="small"
            onClick={onFileCleared}
            aria-label="Remove file"
            disabled={disabled}
          >
            <X size={16} />
          </IconButton>
        </span>
      </Tooltip>
    </Stack>
  );

  return (
    <Stack spacing={2}>
      {file && !staged ? row : frame(file ? confirmation : prompt)}

      <input
        ref={inputRef}
        type="file"
        accept={accepted.accept}
        hidden
        data-testid={`${testId}-input`}
        onChange={(event) => {
          const picked = event.target.files?.[0];
          if (picked) onFilePicked(picked);
          event.target.value = '';
        }}
      />

      {error && <Alert severity="error">{error}</Alert>}
    </Stack>
  );
}

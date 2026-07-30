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

import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import { DocumentDropzone } from '../upload/DocumentDropzone';
import { type AcceptedUploads } from './wizardModel';

interface DocumentStepProps {
  file: File | null;
  title: string;
  /** Validation error from the last file pick, if any. */
  fileError: string | null;
  /** Optional review slug (issue #411) — auto-suggested from the title. */
  slug: string;
  /** Client- or server-side slug rejection to attach to the field. */
  slugError: string | null;
  maxSizeMb: number;
  /** Which formats this server takes, and how to name them (issue #343). */
  accepted: AcceptedUploads;
  onFilePicked: (file: File) => void;
  onFileCleared: () => void;
  onTitleChange: (title: string) => void;
  onSlugChange: (slug: string) => void;
}

/** Step 1 — pick the document (dropzone or picker) and name the review. */
export function DocumentStep({
  file,
  title,
  fileError,
  slug,
  slugError,
  maxSizeMb,
  accepted,
  onFilePicked,
  onFileCleared,
  onTitleChange,
  onSlugChange,
}: DocumentStepProps) {
  return (
    <Stack spacing={2.5}>
      <DocumentDropzone
        file={file}
        error={fileError}
        maxSizeMb={maxSizeMb}
        accepted={accepted}
        onFilePicked={onFilePicked}
        onFileCleared={onFileCleared}
        testId="wizard-dropzone"
      />

      <TextField
        label="Review title"
        value={title}
        onChange={(e) => onTitleChange(e.target.value)}
        placeholder="e.g. NDA Acme Corp"
        helperText="Shown in the overview and to every reviewer."
        fullWidth
      />

      <TextField
        label="URL slug (optional)"
        value={slug}
        onChange={(e) => onSlugChange(e.target.value)}
        placeholder="e.g. nda-acme-corp"
        error={slugError !== null}
        helperText={
          slugError ??
          'A readable address for this review: lowercase letters, digits and hyphens. Cannot be changed later.'
        }
        slotProps={{ htmlInput: { 'data-testid': 'wizard-slug-input', maxLength: 64 } }}
        fullWidth
      />
    </Stack>
  );
}

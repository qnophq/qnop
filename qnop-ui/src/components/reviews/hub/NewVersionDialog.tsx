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

import { useState } from 'react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Upload } from 'lucide-react';
import { useUploadVersion } from '../../../api/hooks/useReviews';
import { apiErrorMessage } from '../../../utils/apiError';
import type { ToastSeverity } from '../../admin/layout/useToast';
import { DocumentDropzone } from '../upload/DocumentDropzone';
import { validateDocumentFile, type AcceptedUploads } from '../wizard/wizardModel';

interface NewVersionDialogProps {
  documentId: string;
  open: boolean;
  onClose: () => void;
  maxSizeMb: number;
  accepted: AcceptedUploads;
  notify: (message: string, severity?: ToastSeverity) => void;
  onUploaded: (versionNumber: number) => void;
}

/**
 * Uploading a new version of the document under review (issue #652).
 *
 * <p>The same dropzone the wizard uses, because it is the same act: this is not a
 * lesser back-door for replacing a document, and it used to feel like one — a
 * button wired straight to the OS file chooser, with the upload starting the
 * instant a file was picked.
 *
 * <p>Two things that needs. A new version becomes the version everyone reviews,
 * so there is now a moment between choosing and sending in which the wrong file
 * can be noticed. And it re-anchors every existing annotation (ADR-0009), which
 * the old button never said out loud.
 *
 * <p>Mirrors {@link ParticipantsDialog} and {@link DueDateDialog} as the review
 * hub's owner affordances.
 */
export function NewVersionDialog({
  documentId,
  open,
  onClose,
  maxSizeMb,
  accepted,
  notify,
  onUploaded,
}: NewVersionDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [wasOpen, setWasOpen] = useState(open);
  const upload = useUploadVersion(documentId);

  // Reset the draft each time the dialog opens — the recommended "adjust state
  // during render" pattern rather than an effect, as DueDateDialog does.
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setFile(null);
      setError(null);
      setProgress(0);
    }
  }

  const pending = upload.isPending;

  const handleFilePicked = (picked: File) => {
    const rejection = validateDocumentFile(picked, maxSizeMb, accepted);
    // A rejected file is still shown as the error, not silently ignored: the
    // reader needs to know which pick was refused.
    setError(rejection);
    setFile(rejection ? null : picked);
  };

  const handleUpload = () => {
    if (!file) return;
    setProgress(0);
    upload.mutate(
      { file, onProgress: setProgress },
      {
        onSuccess: (result) => {
          notify(`Version ${result.versionNumber} uploaded.`);
          onUploaded(result.versionNumber);
          onClose();
        },
        onError: (uploadError) => {
          setError(apiErrorMessage(uploadError, 'The new version could not be uploaded.'));
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      // Not dismissible mid-upload: closing would leave a request in flight with
      // nothing left to report where it went.
      onClose={pending ? undefined : onClose}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>Upload a new version</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5}>
          <Typography variant="body2" color="text.secondary">
            It becomes the version everyone reviews. Existing annotations are carried over and
            re-anchored to the new document; any that no longer fit are flagged for you to place
            again.
          </Typography>

          <DocumentDropzone
            file={file}
            error={error}
            maxSizeMb={maxSizeMb}
            accepted={accepted}
            onFilePicked={handleFilePicked}
            onFileCleared={() => {
              setFile(null);
              setError(null);
            }}
            disabled={pending}
            variant="stage"
            testId="new-version-dropzone"
          />

          {pending && (
            <Stack spacing={0.75}>
              {/* Determinate, because the upload reports its bytes — a spinner on a
                  50 MB document over a slow link is indistinguishable from a hang. */}
              <LinearProgress
                variant={progress > 0 && progress < 1 ? 'determinate' : 'indeterminate'}
                value={Math.round(progress * 100)}
                aria-label="Upload progress"
              />
              <Typography variant="caption" color="text.secondary">
                {progress >= 1
                  ? 'Processing the document…'
                  : `Uploading… ${Math.round(progress * 100)}%`}
              </Typography>
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={pending} color="inherit">
          Cancel
        </Button>
        <Button
          variant="contained"
          startIcon={<Upload size={16} />}
          onClick={handleUpload}
          disabled={!file || pending}
        >
          {pending ? 'Uploading…' : 'Upload version'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

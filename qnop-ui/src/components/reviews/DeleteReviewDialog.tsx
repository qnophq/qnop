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
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Trash2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { useDeleteReview } from '../../api/hooks/useReviews';
import { apiErrorMessage } from '../../utils/apiError';

interface DeleteReviewDialogProps {
  documentId: string;
  title: string;
  open: boolean;
  onClose: () => void;
  /** What the deletion will take with it, so the reader can weigh it. */
  versionCount?: number;
  annotationCount?: number;
  onDeleted: () => void;
}

/**
 * Confirming the deletion of a review (issue #421).
 *
 * <p>The title has to be typed. That is heavier than a confirm button on purpose: this destroys
 * other people's annotations and discussions along with the document, there is no undo and no
 * recycle bin, and the admins who can do it are the same people working through a list of
 * look-alike reviews. A misclick has to be impossible, not merely unlikely.
 *
 * <p>It also says what goes, in numbers. "This cannot be undone" is a warning; "3 versions and 12
 * annotations" is information.
 */
export function DeleteReviewDialog({
  documentId,
  title,
  open,
  onClose,
  versionCount,
  annotationCount,
  onDeleted,
}: DeleteReviewDialogProps) {
  const { t } = useTranslation(['reviews', 'common']);
  const [typed, setTyped] = useState('');
  // The failure's cause, worded at render so the message follows a language switch.
  const [error, setError] = useState<{ cause: unknown } | null>(null);
  const [wasOpen, setWasOpen] = useState(open);
  const remove = useDeleteReview(documentId);

  // Reset the draft each time it opens, so a previous attempt's typing can never
  // arm the button for a different review.
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setTyped('');
      setError(null);
    }
  }

  const confirmed = typed.trim() === title.trim();
  const pending = remove.isPending;

  const versions =
    versionCount === undefined ? null : t('delete.single.versions', { count: versionCount });
  const annotationsText =
    annotationCount === undefined
      ? null
      : t('delete.single.annotations', { count: annotationCount });
  const scope =
    versions && annotationsText
      ? t('delete.single.scopeBoth', { versions, annotations: annotationsText })
      : (versions ?? annotationsText);

  const handleDelete = () => {
    remove.mutate(undefined, {
      onSuccess: onDeleted,
      onError: (deleteError) => setError({ cause: deleteError }),
    });
  };

  return (
    <Dialog open={open} onClose={pending ? undefined : onClose} fullWidth maxWidth="xs">
      <DialogTitle>{t('delete.single.title')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <Typography variant="body2" color="text.secondary">
            {/* The title goes in as a child, never an interpolated value: Trans parses the
                string for tags, so a title like "<b>…" would otherwise turn into markup. */}
            <Trans
              t={t}
              i18nKey={scope ? 'delete.single.bodyWithScope' : 'delete.single.body'}
              values={scope ? { scope } : undefined}
              components={{ title: <strong>{title}</strong> }}
            />
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('delete.single.archiveHint')}
          </Typography>
          <TextField
            label={t('delete.single.confirmLabel')}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            disabled={pending}
            autoComplete="off"
            fullWidth
            size="small"
            slotProps={{ htmlInput: { 'data-testid': 'delete-confirm-input' } }}
          />
          {error && (
            <Alert severity="error">
              {apiErrorMessage(error.cause, t('delete.single.failed'))}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={pending} color="inherit">
          {t('common:actions.cancel')}
        </Button>
        <Button
          variant="contained"
          color="error"
          startIcon={<Trash2 size={16} />}
          disabled={!confirmed || pending}
          onClick={handleDelete}
        >
          {pending ? t('delete.single.deleting') : t('delete.single.submit')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

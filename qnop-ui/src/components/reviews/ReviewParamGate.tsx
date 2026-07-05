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

import type { ReactNode } from 'react';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useDocumentBySlug } from '../../api/hooks/useDocuments';
import { ReviewDocumentIdContext } from './reviewDocumentId';

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Resolves the /reviews/:documentId segment before the page renders (issue
 * #411): a UUID passes through untouched; anything else is treated as a review
 * slug and resolved via the by-slug endpoint, so pages only ever work with the
 * canonical id while the pretty URL stays in the address bar.
 */
export function ReviewParamGate({ children }: { children: ReactNode }) {
  const { documentId: segment = '' } = useParams();
  const isId = UUID_SHAPE.test(segment);
  const bySlug = useDocumentBySlug(segment, !isId && segment !== '');

  if (isId) return children;

  if (bySlug.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} aria-label="Resolving review" />
      </Stack>
    );
  }

  if (bySlug.isError || !bySlug.data?.id) {
    return (
      <Stack spacing={1} sx={{ alignItems: 'center', py: 10 }}>
        <Typography variant="h6">Review not found</Typography>
        <Typography variant="body2" color="text.secondary">
          No review answers to “{segment}”. The link may be outdated.
        </Typography>
        <Button component={RouterLink} to="/reviews" sx={{ mt: 1 }}>
          Back to reviews
        </Button>
      </Stack>
    );
  }

  return (
    <ReviewDocumentIdContext.Provider value={bySlug.data.id}>
      {children}
    </ReviewDocumentIdContext.Provider>
  );
}

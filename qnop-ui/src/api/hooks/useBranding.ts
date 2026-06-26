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

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { deleteBrandingAsset, uploadBrandingAsset, type BrandingSlot } from '../branding';
import { configKeys } from './useConfig';

/**
 * Uploads a branding asset for a slot, then invalidates the server config so the page re-reads the
 * slot's effective source (custom vs default) and its cache-busting URL — the single source of truth
 * for the preview, rather than guessing from an image load.
 */
export function useUploadBrandingAsset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { slot: BrandingSlot; file: File }) =>
      uploadBrandingAsset(vars.slot, vars.file),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: configKeys.all }),
  });
}

/** Deletes the branding asset for a slot (falling back to the default), then refreshes the config. */
export function useDeleteBrandingAsset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (slot: BrandingSlot) => deleteBrandingAsset(slot),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: configKeys.all }),
  });
}

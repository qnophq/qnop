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

import { axiosInstance } from './config';

/**
 * Branding assets are served and uploaded outside the generated OpenAPI client
 * (ADR-0028): they are multipart uploads and binary responses with ETag
 * caching, which sit off the published JSON surface. These thin helpers use the
 * shared axios instance so they inherit the bearer-token + refresh interceptors.
 */

export const BRANDING_SLOTS = ['logo-light', 'logo-dark', 'logomark'] as const;
export type BrandingSlot = (typeof BRANDING_SLOTS)[number];

/** Allowed upload types and size cap, mirroring the backend (BrandingLimits). */
export const BRANDING_ACCEPT = 'image/png,image/webp,image/svg+xml';
export const BRANDING_MAX_SIZE_BYTES = 512 * 1024;

export interface StoredAsset {
  contentType: string;
  sha256: string;
  sizeBytes: number;
}

/**
 * Public URL of a slot's asset (no auth). Pass a changing `version` to bust the
 * browser cache after an upload or delete so the preview reloads.
 */
export function brandingAssetUrl(slot: BrandingSlot, version?: number): string {
  const url = `/api/v1/branding/${slot}`;
  return version ? `${url}?v=${version}` : url;
}

/** Uploads (replacing any existing) the asset for a slot. */
export async function uploadBrandingAsset(slot: BrandingSlot, file: File): Promise<StoredAsset> {
  const form = new FormData();
  form.append('file', file);
  const response = await axiosInstance.post<StoredAsset>(`/admin/branding/${slot}`, form);
  return response.data;
}

/** Removes the asset for a slot (idempotent). */
export async function deleteBrandingAsset(slot: BrandingSlot): Promise<void> {
  await axiosInstance.delete(`/admin/branding/${slot}`);
}

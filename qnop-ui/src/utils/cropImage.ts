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

/**
 * Client-side avatar cropping (issue #117). The cropper hands us a pixel rectangle; we draw it onto
 * a fixed square canvas and export a bounded image. Producing a canonical square here means the
 * upload is already small and square, so the server only validates (it never resizes), and the
 * "avoid serving oversized originals" requirement is satisfied at the source.
 */
export const AVATAR_OUTPUT_SIZE = 512;

export interface PixelArea {
  x: number;
  y: number;
  width: number;
  height: number;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.addEventListener('load', () => resolve(image));
    image.addEventListener('error', () => reject(new Error('The image could not be read.')));
    image.src = src;
  });
}

function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (webp) => {
        if (webp) {
          resolve(webp);
          return;
        }
        // Older browsers may not encode WebP from a canvas; fall back to PNG.
        canvas.toBlob(
          (png) => (png ? resolve(png) : reject(new Error('The image could not be processed.'))),
          'image/png',
        );
      },
      'image/webp',
      0.9,
    );
  });
}

export interface CropOutput {
  /** Bounds the output (the crop is scaled down to fit, preserving its aspect). */
  maxWidth: number;
  maxHeight: number;
  /** Output type. PNG (default) preserves transparency — important for logos. */
  type?: 'image/png' | 'image/webp';
}

function encode(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('The image could not be processed.'))),
      type,
      quality,
    );
  });
}

/**
 * Crops {@link area} out of the source image at its selected aspect, scaled to fit within
 * {@link CropOutput.maxWidth}×{@link CropOutput.maxHeight} (preserving the crop's proportions), and
 * returns a blob. Used for branding logos, which keep their own aspect ratio (unlike the square
 * avatar) and need a transparent PNG by default.
 */
export async function getCroppedImageBlob(
  imageSrc: string,
  area: PixelArea,
  output: CropOutput,
): Promise<Blob> {
  const image = await loadImage(imageSrc);
  const scale = Math.min(1, output.maxWidth / area.width, output.maxHeight / area.height);
  const width = Math.max(1, Math.round(area.width * scale));
  const height = Math.max(1, Math.round(area.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Image editing is not supported in this browser.');
  }
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(image, area.x, area.y, area.width, area.height, 0, 0, width, height);
  return encode(
    canvas,
    output.type ?? 'image/png',
    output.type === 'image/webp' ? 0.92 : undefined,
  );
}

/** Crops {@link area} out of the source image and returns a square {@link AVATAR_OUTPUT_SIZE} blob. */
export async function getCroppedAvatarBlob(imageSrc: string, area: PixelArea): Promise<Blob> {
  const image = await loadImage(imageSrc);
  const canvas = document.createElement('canvas');
  canvas.width = AVATAR_OUTPUT_SIZE;
  canvas.height = AVATAR_OUTPUT_SIZE;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Image editing is not supported in this browser.');
  }
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(
    image,
    area.x,
    area.y,
    area.width,
    area.height,
    0,
    0,
    AVATAR_OUTPUT_SIZE,
    AVATAR_OUTPUT_SIZE,
  );
  return canvasToBlob(canvas);
}

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
 * Text-marker colours. The classic highlighter yellow is used in BOTH themes —
 * markers paint on the page itself, and the PDF pixels are white regardless of
 * the UI theme. Keeping the base yellow also leaves amber unambiguous as the
 * MOVED placement cue (ADR-0009).
 */

/** Solid highlighter yellow — the base of open marks (text and region). */
export const MARKER_YELLOW = '#FFE000';

/** Darker yellow for the rubber-band outline while a region is being drawn. */
export const MARKER_YELLOW_BORDER = '#D9AD00';

/**
 * The live-selection / pending-preview fill (translucent so the glyphs stay
 * readable): identical everywhere a mark is being drawn, so releasing the
 * mouse and creating the annotation never changes the mark's colour.
 */
export const SELECTION_MARKER_BG = 'rgba(255, 224, 0, 0.45)';

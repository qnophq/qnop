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
package io.qnop.service.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the re-anchoring cascade (ADR-0009, issue #320), previously hard-coded magic
 * numbers. Every value is overridable via {@code qnop.reanchoring.*} (or the matching {@code
 * QNOP_REANCHORING_*} environment variable); an unset value falls back to the documented default.
 * The defaults are deliberately conservative — the cascade never guesses, so raising thresholds
 * only ever makes it more cautious.
 *
 * @param similarityThreshold minimum normalized similarity for a fuzzy match to be accepted (0.75)
 * @param ambiguityMargin minimum score lead the winner must hold over the runner-up (0.05)
 * @param contextLength characters of prefix/suffix context regenerated around a re-placed quote
 *     (32)
 * @param maxCandidates cap on candidate windows scanned per surface — bounds pathological input
 *     (64)
 * @param quoteWeight weight of the quote's own similarity in the blended score (0.7)
 * @param contextWeight weight of the surrounding context similarity in the blended score (0.3)
 */
@ConfigurationProperties(prefix = "qnop.reanchoring")
public record ReanchoringProperties(
    Double similarityThreshold,
    Double ambiguityMargin,
    Integer contextLength,
    Integer maxCandidates,
    Double quoteWeight,
    Double contextWeight) {

  public ReanchoringProperties {
    similarityThreshold = similarityThreshold == null ? 0.75 : similarityThreshold;
    ambiguityMargin = ambiguityMargin == null ? 0.05 : ambiguityMargin;
    contextLength = contextLength == null ? 32 : contextLength;
    maxCandidates = maxCandidates == null ? 64 : maxCandidates;
    quoteWeight = quoteWeight == null ? 0.7 : quoteWeight;
    contextWeight = contextWeight == null ? 0.3 : contextWeight;
  }

  /** The documented ADR-0009 defaults — for direct construction in tests and non-Spring callers. */
  public static ReanchoringProperties defaults() {
    return new ReanchoringProperties(null, null, null, null, null, null);
  }
}

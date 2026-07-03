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

import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobHandler;
import io.qnop.spi.extract.RenderedDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the async re-anchoring of a version's {@code PENDING} placements (issue #248, ADR-0009):
 * loads the version's canonical {@link RenderedDocument} and resolves every pending placement
 * through the {@link AnchorResolver} cascade — {@code PLACED}, {@code MOVED}, or {@code ORPHANED}.
 *
 * <p>Enqueued by the extraction job once the version is {@code READY} (the cascade needs the new
 * text layer), transactionally with the READY write. <strong>Idempotent</strong> (ADR-0033): only
 * {@code PENDING} placements are touched, and the resolver is deterministic — a replay recomputes
 * identical outcomes; a deleted version is a no-op.
 */
@Component
public class ReanchorJobHandler implements JobHandler {

  /** The job type; payload is {@code {"versionId": "..."}} like the extraction job's. */
  public static final String TYPE = "document.reanchor";

  private static final Logger log = LoggerFactory.getLogger(ReanchorJobHandler.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentVersionRepository versions;
  private final AnnotationPlacementRepository placements;
  private final AnchorResolver resolver = new AnchorResolver();

  public ReanchorJobHandler(
      DocumentVersionRepository versions, AnnotationPlacementRepository placements) {
    this.versions = versions;
    this.placements = placements;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(String payload) {
    UUID versionId = parseVersionId(payload);
    Optional<DocumentVersion> found = versions.findById(versionId);
    if (found.isEmpty()) {
      log.info("Re-anchoring skipped: version {} no longer exists.", versionId);
      return; // idempotent: the document was deleted after enqueue
    }
    DocumentVersion version = found.get();
    if (version.getExtractionStatus() != ExtractionStatus.READY
        || version.getRenderedDocument() == null) {
      // Enqueued transactionally with the READY write, so this can only be a broken invariant —
      // retryable, and FAILED after max attempts surfaces it.
      throw new IllegalStateException(
          "version " + versionId + " has no rendered document to re-anchor against");
    }
    RenderedDocument rendered = readRendered(version);

    List<AnnotationPlacement> pending =
        placements.findByDocumentVersionIdAndStatus(versionId, PlacementStatus.PENDING);
    for (AnnotationPlacement placement : pending) {
      AnchorResolver.Resolution resolution = resolver.resolve(placement.getAnchor(), rendered);
      switch (resolution.outcome()) {
        case PLACED -> placement.markPlaced(resolution.anchorJson());
        case MOVED -> placement.markMoved(resolution.anchorJson());
        case ORPHANED -> placement.markOrphaned();
      }
      placements.save(placement);
    }
    if (!pending.isEmpty()) {
      log.info("Re-anchored {} placement(s) on version {}.", pending.size(), versionId);
    }
  }

  private static RenderedDocument readRendered(DocumentVersion version) {
    try {
      return MAPPER.readValue(version.getRenderedDocument(), RenderedDocument.class);
    } catch (JacksonException e) {
      throw new IllegalStateException(
          "stored rendered document of version " + version.getId() + " is unreadable", e);
    }
  }

  private static UUID parseVersionId(String payload) {
    try {
      JsonNode node = MAPPER.readTree(payload);
      return UUID.fromString(node.get("versionId").asText());
    } catch (JacksonException | NullPointerException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed re-anchor payload: " + payload, e);
    }
  }
}

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
package io.qnop.service.diff;

import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.VersionDiff;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.VersionDiffRepository;
import io.qnop.service.diff.VersionDiffEngine.Change;
import io.qnop.service.diff.VersionDiffEngine.Location;
import io.qnop.service.document.DocumentAccessService;
import io.qnop.service.document.DocumentValidationException;
import io.qnop.spi.extract.RenderedDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * "What changed since the last version?" (issue #249, ADR-0034): the on-demand inter-version diff
 * with its permanent cache. Versions are immutable, so a computed diff is stable forever — the
 * first request computes and caches it ({@code version_diff}, unique on the pair), every later
 * request reads the cache. Arbitrary pairs are diffable, not only adjacent ones. Visibility is the
 * document's (participants/admin, otherwise 404 — anti-enumeration); both sides must have a READY
 * extraction (the same 409 gates as the rendered representation).
 */
@Service
public class VersionDiffService {

  // Jackson 3 (tools.jackson) — the same stack the extraction pipeline stores jsonb with, so the
  // cached payload and the SPI records share one (de)serialization line.
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final DocumentVersionRepository versions;
  private final VersionDiffRepository diffs;
  private final DocumentAccessService documentAccess;

  public VersionDiffService(
      DocumentVersionRepository versions,
      VersionDiffRepository diffs,
      DocumentAccessService documentAccess) {
    this.versions = versions;
    this.diffs = diffs;
    this.documentAccess = documentAccess;
  }

  /**
   * The located changes of {@code from → to}. Status and box values cross the layer boundary as
   * plain strings/doubles so the web layer maps them without entity/SPI types (ADR-0004).
   */
  public record DiffView(int fromVersion, int toVersion, List<ChangeView> changes) {}

  /** One contiguous change; {@code type} is a {@link VersionDiffEngine.ChangeType} name. */
  public record ChangeView(
      String type,
      String fromText,
      List<LocationView> fromLocations,
      String toText,
      List<LocationView> toLocations) {}

  /** One highlighted rectangle: a surface index and a 0..1-normalized box. */
  public record LocationView(int surfaceIndex, double x, double y, double width, double height) {}

  /** The (cached) diff between two versions of a visible document. */
  @Transactional
  public DiffView diff(UUID documentId, int fromNumber, int toNumber, UUID actor, boolean admin) {
    if (!documentAccess.isVisible(documentId, actor, admin)) {
      throw DocumentValidationException.notFound("no such document: " + documentId);
    }
    if (fromNumber == toNumber) {
      throw DocumentValidationException.invalidRequest("from and to must be different versions");
    }
    DocumentVersion from = requireReadyVersion(documentId, fromNumber);
    DocumentVersion to = requireReadyVersion(documentId, toNumber);

    List<ChangeView> changes =
        diffs
            .findByFromVersionIdAndToVersionId(from.getId(), to.getId())
            .map(cached -> readPayload(cached.getPayload()))
            .orElseGet(() -> computeAndCache(documentId, from, to));
    return new DiffView(fromNumber, toNumber, changes);
  }

  private List<ChangeView> computeAndCache(
      UUID documentId, DocumentVersion from, DocumentVersion to) {
    RenderedDocument left = readRendered(from);
    RenderedDocument right = readRendered(to);
    List<ChangeView> changes =
        VersionDiffEngine.diff(left, right).stream().map(VersionDiffService::toView).toList();
    try {
      diffs.save(
          new VersionDiff(
              documentId, from.getId(), to.getId(), MAPPER.writeValueAsString(changes)));
    } catch (DataIntegrityViolationException e) {
      // A concurrent first request won the unique-pair race — its result is identical (immutable
      // versions), so ours is just as valid to return.
    }
    return changes;
  }

  /** The version, gated exactly like the rendered representation (ADR-0032). */
  private DocumentVersion requireReadyVersion(UUID documentId, int versionNumber) {
    DocumentVersion version =
        versions
            .findByDocumentIdAndVersionNumber(documentId, versionNumber)
            .orElseThrow(
                () -> DocumentValidationException.notFound("no such version: " + versionNumber));
    if (version.getExtractionStatus() == ExtractionStatus.FAILED) {
      throw DocumentValidationException.renderingUnavailable(
          "EXTRACTION_FAILED",
          "extraction failed for version " + versionNumber + "; upload a new version");
    }
    if (version.getExtractionStatus() != ExtractionStatus.READY
        || version.getRenderedDocument() == null) {
      throw DocumentValidationException.renderingUnavailable(
          "EXTRACTION_PENDING", "extraction has not completed yet for version " + versionNumber);
    }
    return version;
  }

  private static RenderedDocument readRendered(DocumentVersion version) {
    return MAPPER.readValue(version.getRenderedDocument(), RenderedDocument.class);
  }

  private static List<ChangeView> readPayload(String payload) {
    return List.of(MAPPER.readValue(payload, ChangeView[].class));
  }

  private static ChangeView toView(Change change) {
    return new ChangeView(
        change.type().name(),
        change.fromText(),
        change.fromLocations().stream().map(VersionDiffService::toView).toList(),
        change.toText(),
        change.toLocations().stream().map(VersionDiffService::toView).toList());
  }

  private static LocationView toView(Location location) {
    return new LocationView(
        location.surfaceIndex(),
        location.box().x(),
        location.box().y(),
        location.box().width(),
        location.box().height());
  }
}

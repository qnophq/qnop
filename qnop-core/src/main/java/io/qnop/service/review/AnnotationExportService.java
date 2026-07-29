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

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.document.DocumentValidationException;
import io.qnop.service.review.AnnotationService.AnnotationView;
import io.qnop.service.review.AnnotationService.CommentView;
import io.qnop.service.review.export.AnnotationExportColumn;
import io.qnop.service.review.export.AnnotationExportFormat;
import io.qnop.service.review.export.AnnotationExportModel;
import io.qnop.service.review.export.AnnotationExportRenderer;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a review's annotations into a downloadable file (issues #547/#635, ADR-0052).
 *
 * <p>This class owns everything that must not be duplicated per format, and nothing that varies by
 * it. It reads through {@link AnnotationService#list} — the very source the Tasks workspace uses —
 * rather than the repository, and that is the point rather than a convenience: the list already
 * filters PRIVATE threads by visibility and resolves author names through {@link
 * ReviewIdentityResolver} (ADR-0038). Building on it means an anonymous review cannot leak a real
 * name into a file, by construction rather than by remembering to.
 *
 * <p>The read produces an {@link AnnotationExportModel} — plain data — which an {@link
 * AnnotationExportRenderer} turns into bytes. A renderer has no repository and no {@code
 * EntityManager}, so a new format cannot reach past the visibility rules; the worst it can do is
 * lay out what it was given badly.
 *
 * <p>Rows come out in reading order (see {@link AnnotationPosition}), which is new behaviour: the
 * annotation model is position-free (ADR-0009), so nothing else in qnop sorts this way.
 */
@Service
public class AnnotationExportService {

  private static final Logger log = LoggerFactory.getLogger(AnnotationExportService.class);

  private final AnnotationService annotations;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final Map<AnnotationExportFormat, AnnotationExportRenderer> renderers;

  public AnnotationExportService(
      AnnotationService annotations,
      DocumentRepository documents,
      DocumentVersionRepository versions,
      List<AnnotationExportRenderer> renderers) {
    this.annotations = annotations;
    this.documents = documents;
    this.versions = versions;
    Map<AnnotationExportFormat, AnnotationExportRenderer> byFormat = new LinkedHashMap<>();
    for (AnnotationExportRenderer renderer : renderers) {
      byFormat.put(renderer.format(), renderer);
    }
    this.renderers = Map.copyOf(byFormat);
  }

  /** The finished file plus the title the download filename is built from. */
  public record Export(byte[] content, String documentTitle, AnnotationExportFormat format) {}

  /** What the wizard asked for: which format, which columns, which slice, threads or not. */
  public record ExportRequest(
      AnnotationExportFormat format,
      List<String> columnIds,
      String scope,
      boolean includeComments) {

    public static ExportRequest everything() {
      return new ExportRequest(AnnotationExportFormat.DEFAULT, List.of(), "all", false);
    }
  }

  /**
   * Builds the export for one review, containing exactly the annotations {@code actor} may see.
   *
   * @param versionNumber which version's placements decide the positions; null = latest
   */
  @Transactional(readOnly = true)
  public Export export(UUID documentId, Integer versionNumber, UUID actor, boolean admin) {
    return export(documentId, versionNumber, ExportRequest.everything(), actor, admin);
  }

  /**
   * Builds the export for one review, containing exactly the annotations {@code actor} may see,
   * narrowed to the requested format, columns and scope.
   */
  @Transactional(readOnly = true)
  public Export export(
      UUID documentId, Integer versionNumber, ExportRequest request, UUID actor, boolean admin) {
    AnnotationExportFormat format = request.format();
    AnnotationExportRenderer renderer = renderers.get(format);
    if (renderer == null) {
      // A format the enum knows but nothing renders yet would otherwise fail with an
      // NPE deep inside the download; say what is actually missing.
      throw new AnnotationExportException(
          documentId, new IllegalStateException("no renderer for format " + format.getId()));
    }

    AnnotationExportModel model = model(documentId, versionNumber, request, actor, admin);
    try {
      return new Export(renderer.render(model), model.documentTitle(), format);
    } catch (IOException e) {
      throw new AnnotationExportException(documentId, e);
    }
  }

  /** The authorized read, the ordering and the task keys — everything a renderer must not redo. */
  private AnnotationExportModel model(
      UUID documentId, Integer versionNumber, ExportRequest request, UUID actor, boolean admin) {
    // The version must be resolved, not left null: list() only loads placements for
    // a concrete version, and without them every anchor is empty and the whole
    // reading order collapses back to creation order. The Tasks page passes the
    // latest version for the same reason.
    Integer version =
        versionNumber != null
            ? versionNumber
            : versions
                .findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                .map(DocumentVersion::getVersionNumber)
                .orElse(null);

    // Authorization and privacy both live in list(): a caller who may not see the
    // review gets the same refusal the Tasks page would give them.
    List<AnnotationView> views = annotations.list(documentId, version, null, null, actor, admin);

    // Task keys number the WHOLE review, so T-7 stays T-7 in an export narrowed
    // to the open items — a key that renumbers per filter would be worthless
    // for talking about an annotation.
    Map<UUID, String> taskKeys = taskKeys(views);

    List<AnnotationExportModel.Row> rows =
        views.stream()
            .filter(view -> matchesScope(view, request.scope()))
            .map(
                view ->
                    new AnnotationExportModel.Row(
                        taskKeys.getOrDefault(view.id(), ""),
                        view,
                        AnnotationPosition.parse(view.anchorJson()),
                        request.includeComments() ? thread(view.id(), actor, admin) : List.of()))
            .sorted(
                Comparator.comparing(
                        AnnotationExportModel.Row::position, AnnotationPosition.READING_ORDER)
                    // A stable tail so an unchanged review exports identically twice.
                    .thenComparing(row -> row.view().createdAt())
                    .thenComparing(row -> row.view().id()))
            .toList();

    String title = documents.findById(documentId).map(Document::getTitle).orElse("annotations");
    return new AnnotationExportModel(
        title,
        version,
        rows,
        AnnotationExportColumn.resolve(request.columnIds()),
        request.includeComments());
  }

  /**
   * One annotation's thread, read through the authorized path.
   *
   * <p>{@link AnnotationService#listComments} applies the PRIVATE-thread check and resolves each
   * author through {@link ReviewIdentityResolver}, so a pseudonymised author stays pseudonymised in
   * every format. That costs a query round per annotation; an export is a rare, deliberate action,
   * and re-implementing the visibility rules to batch it is a far worse trade than the round trips.
   */
  private List<CommentView> thread(UUID annotationId, UUID actor, boolean admin) {
    try {
      return annotations.listComments(annotationId, actor, admin);
    } catch (DocumentValidationException e) {
      // Only the not-found case is tolerated, and only because the reads are not one
      // snapshot: an annotation deleted mid-export must not take the whole download
      // down with it. Anything else propagates.
      log.warn("Annotation {} disappeared while exporting its comments", annotationId, e);
      return List.of();
    }
  }

  /** Whether an annotation belongs in the requested slice; anything unknown means "all". */
  private static boolean matchesScope(AnnotationView view, String scope) {
    if (scope == null || "all".equalsIgnoreCase(scope)) {
      return true;
    }
    boolean resolved = "RESOLVED".equalsIgnoreCase(view.status());
    return "resolved".equalsIgnoreCase(scope) == resolved;
  }

  /**
   * {@code T-1}, {@code T-2}, … in creation order — the shorthand people use to talk about an
   * annotation. The rule is mirrored from the Tasks board's {@code taskKeys()}; keep the two in
   * step, since a key that disagrees with the screen is worse than no key at all.
   */
  static Map<UUID, String> taskKeys(List<AnnotationView> views) {
    List<AnnotationView> byCreation =
        views.stream()
            .sorted(
                Comparator.comparing(AnnotationView::createdAt).thenComparing(AnnotationView::id))
            .toList();
    Map<UUID, String> keys = new LinkedHashMap<>();
    for (int index = 0; index < byCreation.size(); index++) {
      keys.put(byCreation.get(index).id(), "T-" + (index + 1));
    }
    return keys;
  }

  /** The file could not be assembled — an infrastructure failure, not a user error. */
  public static class AnnotationExportException extends RuntimeException {
    public AnnotationExportException(UUID documentId, Throwable cause) {
      super("Could not build the annotation export for document " + documentId, cause);
    }
  }
}

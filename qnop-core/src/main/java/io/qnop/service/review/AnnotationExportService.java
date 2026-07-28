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
import io.qnop.service.review.AnnotationService.AnnotationView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a review's annotations into an Excel workbook (issue #547, ADR-0052).
 *
 * <p>It reads through {@link AnnotationService#list} — the very source the Tasks workspace uses —
 * rather than the repository, and that is the point rather than a convenience: the list already
 * filters PRIVATE threads by visibility and resolves author names through {@link
 * ReviewIdentityResolver} (ADR-0038). Building the sheet on it means an anonymous review cannot
 * leak a real name into a spreadsheet, by construction rather than by remembering to.
 *
 * <p>Rows come out in reading order (see {@link AnnotationPosition}), which is new behaviour: the
 * annotation model is position-free (ADR-0009), so nothing else in qnop sorts this way.
 */
@Service
public class AnnotationExportService {

  /** Excel's own hard ceiling is 32767; a shorter cap keeps the sheet readable. */
  private static final int SUMMARY_MAX = 500;

  private final AnnotationService annotations;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;

  public AnnotationExportService(
      AnnotationService annotations,
      DocumentRepository documents,
      DocumentVersionRepository versions) {
    this.annotations = annotations;
    this.documents = documents;
    this.versions = versions;
  }

  /** The finished workbook plus the title the download filename is built from. */
  public record Export(byte[] workbook, String documentTitle) {}

  /** What the wizard asked for: which columns, and which slice of the review. */
  public record ExportRequest(List<String> columnIds, String scope) {
    public static ExportRequest everything() {
      return new ExportRequest(List.of(), "all");
    }
  }

  /**
   * Builds the workbook for one review, containing exactly the annotations {@code actor} may see.
   *
   * @param versionNumber which version's placements decide the positions; null = latest
   */
  @Transactional(readOnly = true)
  public Export export(UUID documentId, Integer versionNumber, UUID actor, boolean admin) {
    return export(documentId, versionNumber, ExportRequest.everything(), actor, admin);
  }

  /**
   * Builds the workbook for one review, containing exactly the annotations {@code actor} may see,
   * narrowed to the requested columns and scope.
   */
  @Transactional(readOnly = true)
  public Export export(
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
    views = views.stream().filter(view -> matchesScope(view, request.scope())).toList();
    List<AnnotationExportColumn> columns = AnnotationExportColumn.resolve(request.columnIds());

    String title = documents.findById(documentId).map(Document::getTitle).orElse("annotations");

    List<Ordered> ordered =
        views.stream()
            .map(view -> new Ordered(view, AnnotationPosition.parse(view.anchorJson())))
            .sorted(
                Comparator.comparing(Ordered::position, AnnotationPosition.READING_ORDER)
                    // A stable tail so an unchanged review exports identically twice.
                    .thenComparing(o -> o.view().createdAt())
                    .thenComparing(o -> o.view().id()))
            .toList();

    return new Export(write(ordered, taskKeys, columns), title);
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

  private byte[] write(
      List<Ordered> rows, Map<UUID, String> taskKeys, List<AnnotationExportColumn> columns) {
    // Streaming workbook: a review with thousands of annotations must not have to
    // fit in memory as a DOM.
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Annotations");
      CellStyle headerStyle = headerStyle(workbook);
      CellStyle dateStyle = dateStyle(workbook);

      Row header = sheet.createRow(0);
      for (int index = 0; index < columns.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(columns.get(index).getHeader());
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 1;
      for (Ordered row : rows) {
        writeRow(sheet.createRow(rowIndex++), row, taskKeys, columns, dateStyle);
      }

      // Freeze the header and switch on the filter dropdowns, so Excel's own
      // per-column sort works the moment the file opens — the whole reason the
      // cells are typed rather than pre-formatted strings.
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows.size(), 1), 0, columns.size() - 1));
      for (int index = 0; index < columns.size(); index++) {
        sheet.setColumnWidth(index, columnWidth(columns.get(index)));
      }

      workbook.write(out);
      workbook.dispose(); // drops the streaming temp files
      return out.toByteArray();
    } catch (IOException e) {
      throw new AnnotationExportException(documentIdOf(rows), e);
    }
  }

  private void writeRow(
      Row row,
      Ordered ordered,
      Map<UUID, String> taskKeys,
      List<AnnotationExportColumn> columns,
      CellStyle dateStyle) {
    AnnotationView view = ordered.view();
    for (int index = 0; index < columns.size(); index++) {
      switch (columns.get(index)) {
        case TASK_KEY -> row.createCell(index).setCellValue(taskKeys.getOrDefault(view.id(), ""));
        case PAGE -> {
          Integer page = ordered.position().pageNumber();
          if (page != null) {
            // Numeric, not text: "10" must sort after "9" in Excel.
            row.createCell(index).setCellValue(page);
          }
        }
        case STATUS -> row.createCell(index).setCellValue(humanize(view.status()));
        case TYPE -> row.createCell(index).setCellValue(humanize(view.type()));
        case PRIORITY -> row.createCell(index).setCellValue(humanize(view.priority()));
        case SUMMARY -> row.createCell(index).setCellValue(summary(view.firstComment()));
        case AUTHOR ->
            row.createCell(index)
                .setCellValue(view.authorDisplayName() == null ? "" : view.authorDisplayName());
        case COMMENTS -> row.createCell(index).setCellValue(view.commentCount());
        case PLACEMENT -> row.createCell(index).setCellValue(humanize(view.placementStatus()));
        case CREATED -> writeDate(row, index, view.createdAt(), dateStyle);
        case UPDATED -> writeDate(row, index, view.updatedAt(), dateStyle);
      }
    }
  }

  private static void writeDate(Row row, int column, Instant instant, CellStyle style) {
    if (instant == null) {
      return;
    }
    Cell cell = row.createCell(column);
    // A real Excel date, not a formatted string: sorting and date filters depend on it.
    cell.setCellValue(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    cell.setCellStyle(style);
  }

  /** {@code CHANGES_REQUESTED} → {@code Changes requested}; null/blank → empty cell. */
  static String humanize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String lower = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  /** The opening comment as one flat cell — markdown noise stripped, length capped. */
  static String summary(String firstComment) {
    if (firstComment == null) {
      return "";
    }
    String flat =
        firstComment
            .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
            .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
            .replaceAll("[`*_>#~]", "")
            .replaceAll("\\s+", " ")
            .trim();
    return flat.length() <= SUMMARY_MAX ? flat : flat.substring(0, SUMMARY_MAX - 1) + "…";
  }

  private static CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font bold = workbook.createFont();
    bold.setBold(true);
    style.setFont(bold);
    style.setBorderBottom(BorderStyle.THIN);
    return style;
  }

  private static CellStyle dateStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    CreationHelper helper = workbook.getCreationHelper();
    style.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
    return style;
  }

  /** Roughly sized columns; the summary gets the room, the rest stay compact. */
  private static int columnWidth(AnnotationExportColumn column) {
    int characters = column == AnnotationExportColumn.SUMMARY ? 70 : 16;
    return characters * 256;
  }

  private static UUID documentIdOf(List<Ordered> rows) {
    return rows.isEmpty() ? null : rows.getFirst().view().documentId();
  }

  private record Ordered(AnnotationView view, AnnotationPosition position) {}

  /** The workbook could not be assembled — an infrastructure failure, not a user error. */
  public static class AnnotationExportException extends RuntimeException {
    public AnnotationExportException(UUID documentId, Throwable cause) {
      super("Could not build the annotation export for document " + documentId, cause);
    }
  }
}

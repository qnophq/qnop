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
package io.qnop.service.notification;

import io.qnop.entity.NotificationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a {@link DigestContent} into the two blocks the mail template drops in (issue #680).
 *
 * <p>Rendered here rather than in the template because Mustache has no counting or pluralisation:
 * the alternative is a template full of pre-computed booleans, which is harder to read than the
 * sentence it produces.
 *
 * <p>Counts, never one line per event — "3 new annotations, 7 comments" is what somebody can act
 * on, and fifty individual lines are precisely what the digest exists to replace.
 *
 * <p>Each document carries its own link, because the digest's job is not to inform but to get
 * somebody back to the right review. One link to the list would make the reader search for what
 * they just read about.
 */
public final class DigestRenderer {

  /** The order these read best in a summary, most substantive first. */
  private static final List<NotificationType> ORDER =
      List.of(
          NotificationType.ANNOTATION_CREATED,
          NotificationType.COMMENT_ADDED,
          NotificationType.ANNOTATION_DECIDED,
          NotificationType.VERSION_UPLOADED,
          NotificationType.WORKFLOW_CHANGED,
          NotificationType.PARTICIPANT_ADDED,
          NotificationType.REVIEW_DELETED,
          NotificationType.MENTION);

  private DigestRenderer() {}

  /**
   * One document as the digest refers to it: what it is called and where it lives.
   *
   * <p>The URL comes from the caller rather than being assembled here, so the review path stays in
   * one place and this class stays about wording.
   */
  public record Target(String title, String url) {}

  /** The plain-text block: one line per document, its counts, then the link on its own line. */
  public static String plain(DigestContent content, Map<UUID, Target> targets) {
    StringBuilder out = new StringBuilder();
    for (DigestContent.DocumentSummary document : content.documents()) {
      Target target = targetOf(document, targets);
      out.append("* ")
          .append(target.title())
          .append(" — ")
          .append(String.join(", ", phrases(document)))
          .append('\n');
      if (target.url() != null && !target.url().isBlank()) {
        out.append("  ").append(target.url()).append('\n');
      }
    }
    return out.toString().stripTrailing();
  }

  /** The same, as list items whose titles are the links. */
  public static String html(DigestContent content, Map<UUID, Target> targets) {
    StringBuilder out = new StringBuilder("<ul style=\"margin:0 0 4px;padding-left:18px;\">");
    for (DigestContent.DocumentSummary document : content.documents()) {
      Target target = targetOf(document, targets);
      String label = escape(target.title());
      out.append("<li style=\"margin:0 0 8px;color:#3d3f47;font-size:15px;line-height:1.6;\">");
      if (target.url() == null || target.url().isBlank()) {
        out.append("<strong>").append(label).append("</strong>");
      } else {
        out.append("<a href=\"")
            .append(escape(target.url()))
            .append("\" style=\"color:#18191f;font-weight:600;\">")
            .append(label)
            .append("</a>");
      }
      out.append(" — ").append(escape(String.join(", ", phrases(document)))).append("</li>");
    }
    return out.append("</ul>").toString();
  }

  /** A phrase for the total, pluralised — Mustache cannot, and the subject line needs one. */
  public static String totalPhrase(int total) {
    return total + (total == 1 ? " update" : " updates");
  }

  /** e.g. ["3 new annotations", "7 comments"] — singular where it is one. */
  private static List<String> phrases(DigestContent.DocumentSummary document) {
    List<String> phrases = new ArrayList<>();
    for (NotificationType type : ORDER) {
      int count = document.counts().getOrDefault(type, 0);
      if (count > 0) {
        phrases.add(count + " " + noun(type, count));
      }
    }
    // A type nobody thought to name still gets counted rather than vanishing.
    document
        .counts()
        .forEach(
            (type, count) -> {
              if (!ORDER.contains(type) && count > 0) {
                phrases.add(count + " " + (count == 1 ? "update" : "updates"));
              }
            });
    return phrases;
  }

  private static String noun(NotificationType type, int count) {
    boolean one = count == 1;
    return switch (type) {
      case ANNOTATION_CREATED -> one ? "new annotation" : "new annotations";
      case COMMENT_ADDED -> one ? "comment" : "comments";
      case ANNOTATION_DECIDED -> one ? "decision" : "decisions";
      case VERSION_UPLOADED -> one ? "new version" : "new versions";
      case WORKFLOW_CHANGED -> one ? "status change" : "status changes";
      case PARTICIPANT_ADDED -> one ? "new participant" : "new participants";
      case REVIEW_DELETED -> one ? "deleted review" : "deleted reviews";
      // Mentions are mailed immediately and so are usually already read by now —
      // but an unread one still belongs in the summary rather than nowhere.
      case MENTION -> one ? "mention" : "mentions";
    };
  }

  private static Target targetOf(
      DigestContent.DocumentSummary document, Map<UUID, Target> targets) {
    if (document.documentId() == null) {
      return new Target("Your workspace", null);
    }
    // A document deleted between the notification and the digest still gets a
    // line, without a link: the count is true, and pretending it did not happen
    // is worse than a line that goes nowhere.
    return targets.getOrDefault(
        document.documentId(), new Target("A review you take part in", null));
  }

  private static String escape(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns a {@link DigestContent} into the two blocks the mail template drops in (issue #680).
 *
 * <p>Rendered here rather than in the template because Mustache has no counting or pluralisation:
 * the alternative is a template full of pre-computed booleans, which is harder to read than the
 * sentence it produces.
 *
 * <p>Two levels on purpose. The counted headline — "3 new annotations, 7 comments" — is what
 * somebody can act on at a glance; underneath it the events are listed in the order they happened,
 * so the reader can follow what went on without opening the review. The headline alone was too
 * little to decide whether to open anything; a bare list of fifty lines is what the digest exists
 * to replace.
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

  /** Long enough to recognise the thread, short enough not to reproduce it. */
  private static final int EXCERPT_LIMIT = 140;

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

  private DigestRenderer() {}

  /**
   * One document as the digest refers to it: what it is called and where it lives.
   *
   * <p>The URL comes from the caller rather than being assembled here, so the review path stays in
   * one place and this class stays about wording.
   */
  public record Target(String title, String url) {}

  /**
   * The plain-text block.
   *
   * @param actorNames resolves an actor id to the name <em>this recipient</em> may see; under
   *     per-review anonymity that is a pseudonym, so the caller owns this and the renderer never
   *     looks a name up itself
   * @param zone the recipient's timezone, so the times read as their own clock
   */
  public static String plain(
      DigestContent content,
      Map<UUID, Target> targets,
      Function<UUID, String> actorNames,
      ZoneId zone) {
    StringBuilder out = new StringBuilder();
    for (DigestContent.DocumentSummary document : content.documents()) {
      Target target = targetOf(document, targets);
      out.append(target.title())
          .append(" — ")
          .append(String.join(", ", phrases(document)))
          .append('\n');
      for (DigestContent.Event event : document.events()) {
        out.append("    ")
            .append(time(event.at(), zone))
            .append("  ")
            .append(sentence(event, actorNames))
            .append('\n');
        String excerpt = excerptOf(event);
        if (excerpt != null) {
          out.append("            \"").append(excerpt).append("\"\n");
        }
      }
      if (target.url() != null && !target.url().isBlank()) {
        out.append("    ").append(target.url()).append('\n');
      }
      out.append('\n');
    }
    return out.toString().stripTrailing();
  }

  /** The same as a block per document: a linked heading, its counts, then the timeline. */
  public static String html(
      DigestContent content,
      Map<UUID, Target> targets,
      Function<UUID, String> actorNames,
      ZoneId zone) {
    StringBuilder out = new StringBuilder();
    for (DigestContent.DocumentSummary document : content.documents()) {
      Target target = targetOf(document, targets);
      String label = escape(target.title());
      out.append(
          "<div style=\"margin:0 0 18px;padding:0 0 2px;border-left:3px solid #e4e7ec;"
              + "padding-left:14px;\">");
      out.append("<p style=\"margin:0 0 2px;font-size:15px;line-height:1.5;\">");
      if (target.url() == null || target.url().isBlank()) {
        out.append("<strong style=\"color:#18191f;\">").append(label).append("</strong>");
      } else {
        out.append("<a href=\"")
            .append(escape(target.url()))
            .append("\" style=\"color:#18191f;font-weight:600;text-decoration:none;\">")
            .append(label)
            .append("</a>");
      }
      out.append("</p>");
      out.append("<p style=\"margin:0 0 8px;color:#6b6d76;font-size:13px;\">")
          .append(escape(String.join(", ", phrases(document))))
          .append("</p>");
      out.append("<table role=\"presentation\" style=\"border-collapse:collapse;width:100%;\">");
      for (DigestContent.Event event : document.events()) {
        out.append("<tr>")
            .append(
                "<td style=\"padding:2px 10px 2px 0;color:#9a9ea8;font-size:13px;"
                    + "white-space:nowrap;vertical-align:top;\">")
            .append(escape(time(event.at(), zone)))
            .append("</td>")
            .append("<td style=\"padding:2px 0;color:#3d3f47;font-size:14px;line-height:1.5;\">")
            .append(escape(sentence(event, actorNames)));
        String excerpt = excerptOf(event);
        if (excerpt != null) {
          out.append("<br><span style=\"color:#6b6d76;font-style:italic;\">")
              .append(escape("\u201c" + excerpt + "\u201d"))
              .append("</span>");
        }
        out.append("</td></tr>");
      }
      out.append("</table></div>");
    }
    return out.toString();
  }

  /** e.g. "Aug 3, 09:12" — dated, because a digest may cover more than one day. */
  private static String time(Instant at, ZoneId zone) {
    return at == null ? "" : TIME.format(at.atZone(zone));
  }

  /** What happened, as a sentence: who did what. */
  private static String sentence(DigestContent.Event event, Function<UUID, String> actorNames) {
    String actor = actorNames.apply(event.actorId());
    return switch (event.type()) {
      case ANNOTATION_CREATED -> actor + " raised an annotation";
      case COMMENT_ADDED -> actor + " replied in a thread";
      case ANNOTATION_DECIDED -> actor + " decided an annotation";
      case VERSION_UPLOADED ->
          actor
              + " uploaded a new version"
              + (event.versionNumber() == null ? "" : " (v" + event.versionNumber() + ")");
      case WORKFLOW_CHANGED -> actor + " changed the review status";
      case PARTICIPANT_ADDED -> actor + " added you to the review";
      case REVIEW_DELETED -> actor + " deleted the review";
      case MENTION -> actor + " mentioned you";
    };
  }

  /** The quoted line under an event, shortened — a digest is not the place to re-read a thread. */
  private static String excerptOf(DigestContent.Event event) {
    String excerpt = event.excerpt();
    if (excerpt == null || excerpt.isBlank()) {
      return null;
    }
    String collapsed = excerpt.strip().replaceAll("\\s+", " ");
    return collapsed.length() <= EXCERPT_LIMIT
        ? collapsed
        : collapsed.substring(0, EXCERPT_LIMIT - 1).stripTrailing() + "\u2026";
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

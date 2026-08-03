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

import io.qnop.entity.NotificationDigest;
import io.qnop.entity.User;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.NotificationDigestRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.scheduler.SchedulerJobCatalog;
import io.qnop.service.scheduler.SchedulerService;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily review digest (issue #680): one summary per recipient per morning, instead of a mail
 * per event.
 *
 * <p>A scheduled reader rather than a third {@link io.qnop.service.review.ReviewNotificationSink},
 * which was the open question in the issue. A sink is per-event and a digest is per-window, so as a
 * sink it would only ever queue — and the queue already exists: the in-app sink writes a row for
 * every event regardless of mail preferences, which is exactly the digest's input. The side effect
 * of that choice is that a digest can later carry notifications that never came from a review sink
 * at all.
 *
 * <p>Runs hourly and sends to whoever is due, rather than running once at 08:00 — see {@link
 * DigestSchedule} for why that distinction matters for half-hour timezones.
 *
 * <p>Per recipient, each in its own transaction: a run interrupted halfway keeps the digests it has
 * already sent and the rest follow on the next hour's run, rather than the whole batch rolling back
 * and re-sending.
 */
@Service
public class NotificationDigestService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDigestService.class);

  /**
   * Recipients handled per run. A backlog larger than this finishes on the following runs, which
   * for an hourly job means minutes later.
   */
  private static final int MAX_RECIPIENTS_PER_RUN = 500;

  private final NotificationRepository notifications;
  private final NotificationDigestRepository watermarks;
  private final UserRepository users;
  private final UserSettingRepository userSettings;
  private final DocumentRepository documents;
  private final ApplicationSettingsService settings;
  private final MailService mail;
  private final SchedulerService scheduler;
  private final TransactionTemplate tx;

  public NotificationDigestService(
      NotificationRepository notifications,
      NotificationDigestRepository watermarks,
      UserRepository users,
      UserSettingRepository userSettings,
      DocumentRepository documents,
      ApplicationSettingsService settings,
      MailService mail,
      SchedulerService scheduler,
      PlatformTransactionManager transactionManager) {
    this.notifications = notifications;
    this.watermarks = watermarks;
    this.users = users;
    this.userSettings = userSettings;
    this.documents = documents;
    this.settings = settings;
    this.mail = mail;
    this.scheduler = scheduler;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Hourly gate; the scheduler decides whether the job is enabled and in dry-run. */
  @Scheduled(cron = "${qnop.notifications.digest-cron:0 5 * * * *}")
  @SchedulerLock(name = SchedulerJobCatalog.NOTIFICATION_DIGEST, lockAtMostFor = "PT30M")
  public void digest() {
    scheduler.runScheduled(SchedulerJobCatalog.NOTIFICATION_DIGEST);
  }

  /**
   * One pass. Self-transactional: each recipient commits on their own, so an interrupted run keeps
   * what it sent. In {@code dryRun} it reports who would receive one and writes nothing — which
   * also means a dry run does not consume the watermark.
   */
  public String digestOnce(boolean dryRun) {
    Instant now = Instant.now();
    List<UUID> candidates = tx.execute(status -> notifications.findRecipientsWithUnread());
    if (candidates == null || candidates.isEmpty()) {
      return "Nothing unread anywhere";
    }

    int sent = 0;
    int considered = 0;
    for (UUID recipientId : candidates) {
      if (considered >= MAX_RECIPIENTS_PER_RUN) {
        log.info(
            "Digest run hit its per-run cap of {} recipient(s); the rest follow next hour.",
            MAX_RECIPIENTS_PER_RUN);
        break;
      }
      considered++;
      Boolean delivered = tx.execute(status -> digestFor(recipientId, now, dryRun));
      if (Boolean.TRUE.equals(delivered)) {
        sent++;
      }
    }

    if (dryRun) {
      return "Would send "
          + sent
          + " digest(s) to "
          + considered
          + " candidate(s); nothing changed";
    }
    return sent == 0
        ? "No digests were due out of " + considered + " candidate(s)"
        : "Sent " + sent + " digest(s)";
  }

  /**
   * @return whether a digest was (or would have been) sent to this recipient
   */
  private boolean digestFor(UUID recipientId, Instant now, boolean dryRun) {
    Optional<User> maybeUser = users.findById(recipientId);
    if (maybeUser.isEmpty() || !maybeUser.get().isEnabled()) {
      return false;
    }
    User recipient = maybeUser.get();
    if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
      return false;
    }
    if (cadenceFor(recipientId) != ReviewMailCadence.DAILY) {
      return false;
    }

    ZoneId zone = zoneFor(recipientId);
    Optional<NotificationDigest> watermark = watermarks.findById(recipientId);
    if (!DigestSchedule.isDue(now, zone, watermark.map(NotificationDigest::getLastSentAt))) {
      return false;
    }

    Instant from =
        DigestSchedule.collectFrom(now, watermark.map(NotificationDigest::getCoveredThrough));
    DigestContent content = DigestContent.of(notifications.findUnreadForDigest(recipientId, from));
    if (content.isEmpty()) {
      // No mail, and no watermark either: an empty digest is worse than silence,
      // and moving the watermark would silently drop whatever arrives next.
      return false;
    }

    if (dryRun) {
      return true;
    }

    mail.sendMailFromTemplate(
        MailTemplateKey.REVIEW_DAILY_DIGEST,
        recipient.getEmail(),
        varsFor(recipient, content),
        localeFor(recipientId));
    // The newest createdAt actually included — never now(), or a notification
    // written while this was being assembled would fall in the gap.
    Instant coveredThrough = content.coveredThrough() == null ? from : content.coveredThrough();
    watermarks.save(
        watermark
            .map(
                existing -> {
                  existing.advance(now, coveredThrough);
                  return existing;
                })
            .orElseGet(() -> new NotificationDigest(recipientId, now, coveredThrough)));
    return true;
  }

  private ReviewMailCadence cadenceFor(UUID userId) {
    return userSettings
        .findByUserIdAndSettingKey(userId, UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getKey())
        .flatMap(setting -> ReviewMailCadence.parse(setting.getSettingValue()))
        .orElseGet(ReviewMailCadence::registryDefault);
  }

  /**
   * The recipient's timezone, falling back to the instance default and then UTC.
   *
   * <p>The preference is empty by default (ADR-0041), so without the fallbacks a large share of
   * recipients would never be due at their own 08:00 — or worse, never at all.
   */
  private ZoneId zoneFor(UUID userId) {
    Optional<String> preferred =
        userSettings
            .findByUserIdAndSettingKey(userId, UserSettingKey.TIMEZONE.getKey())
            .map(setting -> setting.getSettingValue());
    return parseZone(preferred.orElse(null))
        .or(() -> parseZone(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_TIMEZONE)))
        .orElse(ZoneOffset.UTC);
  }

  private Optional<ZoneId> parseZone(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(ZoneId.of(raw.trim()));
    } catch (DateTimeException e) {
      log.debug("Ignoring unusable timezone '{}' for a digest recipient", raw);
      return Optional.empty();
    }
  }

  private String localeFor(UUID userId) {
    return userSettings
        .findByUserIdAndSettingKey(userId, UserSettingKey.PREFERRED_LANGUAGE.getKey())
        .map(setting -> setting.getSettingValue())
        .filter(value -> !value.isBlank())
        .orElse(null);
  }

  private Map<String, Object> varsFor(User recipient, DigestContent content) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("siteName", settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME));
    vars.put("recipientName", recipient.getDisplayName());
    vars.put("totalCount", content.total());
    vars.put("digestBody", DigestRenderer.plain(content, titlesFor(content)));
    vars.put("digestBodyHtml", DigestRenderer.html(content, titlesFor(content)));
    vars.put("actionUrl", reviewsUrl());
    return vars;
  }

  /** Document titles for the summary, in one query rather than one per group. */
  private Map<UUID, String> titlesFor(DigestContent content) {
    List<UUID> ids =
        content.documents().stream()
            .map(DigestContent.DocumentSummary::documentId)
            .filter(java.util.Objects::nonNull)
            .toList();
    Map<UUID, String> titles = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return titles;
    }
    documents
        .findAllById(ids)
        .forEach(document -> titles.put(document.getId(), document.getTitle()));
    return titles;
  }

  private String reviewsUrl() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    if (base == null || base.isBlank()) {
      log.warn("general.base_url is not configured — digest links will be relative/broken");
      base = "";
    }
    return base.replaceAll("/+$", "") + "/reviews";
  }
}

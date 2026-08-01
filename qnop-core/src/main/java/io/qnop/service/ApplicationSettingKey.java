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
package io.qnop.service;

import io.qnop.entity.SettingValueType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The authoritative registry of known application settings (issue #16): each key carries its {@link
 * SettingValueType}, default value, human description, and (for {@code ENUM}) the allowed options.
 *
 * <p>This registry is the single source of truth for the global settings; the {@code
 * application_setting} table (issue #13) is its persisted projection. The key set here must stay in
 * sync with the Liquibase seed. Per-user settings have their own registry (issues #22/#24); this
 * enum covers only the global, admin-managed ones.
 */
public enum ApplicationSettingKey {
  GENERAL_APPLICATION_NAME(
      "general.application_name",
      SettingValueType.STRING,
      "qnop",
      "Display name of this qnop instance."),
  GENERAL_BASE_URL(
      "general.base_url",
      SettingValueType.STRING,
      "",
      "Public base URL, used in generated links and emails."),
  GENERAL_DEFAULT_LANGUAGE(
      "general.default_language",
      SettingValueType.STRING,
      "en",
      "Default UI language (ISO 639-1)."),
  GENERAL_DEFAULT_TIMEZONE(
      "general.default_timezone",
      SettingValueType.STRING,
      "UTC",
      "Default display timezone (IANA id, e.g. UTC, Europe/Berlin), used as the fallback for users"
          + " without a personal preference."),
  UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB(
      "upload.document_max_file_size_mb",
      SettingValueType.INTEGER,
      "25",
      "Maximum document upload size in megabytes."),
  UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB(
      "upload.attachment_max_file_size_mb",
      SettingValueType.INTEGER,
      "10",
      "Maximum comment attachment size in megabytes."),
  TRACKING_ENABLED(
      "tracking.enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Whether anonymous usage tracking is enabled. Measurement runs through this server, never"
          + " past it: the browser loads the analytics script from qnop and sends its events to"
          + " qnop, which forwards them (issue #666)."),
  TRACKING_PROVIDER(
      "tracking.provider",
      SettingValueType.ENUM,
      "none",
      "Which analytics backend receives the forwarded measurements.",
      List.of("none", "matomo", "plausible", "umami", "posthog", "pirsch")),
  TRACKING_HOST(
      "tracking.host",
      SettingValueType.STRING,
      "",
      "Base URL of the analytics backend, e.g. https://matomo.internal. Empty uses the provider's"
          + " own cloud where it has one (Plausible, PostHog, Pirsch); Matomo and Umami are"
          + " self-hosted and always need this."),
  TRACKING_SITE_ID(
      "tracking.site_id",
      SettingValueType.STRING,
      "",
      "The backend's identifier for this site: Matomo idSite, Plausible domain, Umami website id,"
          + " PostHog project API key, Pirsch identification code."),
  TRACKING_RESPECT_DNT(
      "tracking.respect_dnt",
      SettingValueType.BOOLEAN,
      "true",
      "Load nothing at all for browsers that send Do-Not-Track or Global Privacy Control."),
  TRACKING_CONSENT_REQUIRED(
      "tracking.consent_required",
      SettingValueType.BOOLEAN,
      "true",
      "Ask before measuring anything. Off is defensible only where the backend sets no cookies and"
          + " your legal basis says so — that call is yours, not this server's."),
  TRACKING_PRIVILEGED_ROLES(
      "tracking.track_privileged_roles",
      SettingValueType.BOOLEAN,
      "false",
      "Also measure administrators and auditors. Off by default: a handful of privileged people is"
          + " barely a statistic and very much personal data."),
  TRACKING_FORWARD_CLIENT_IP(
      "tracking.forward_client_ip",
      SettingValueType.ENUM,
      "anonymized",
      "What reaches the backend as the visitor's address. 'anonymized' truncates it (IPv4 to /24,"
          + " IPv6 to /64) so visitors stay countable without being identifiable; 'none' sends"
          + " nothing, and the backend then sees every reviewer as one visitor.",
      List.of("anonymized", "none")),
  SMTP_ENABLED(
      "smtp.enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Master switch for outgoing mail; when off, message sends are skipped."),
  SMTP_HOST("smtp.host", SettingValueType.STRING, "", "SMTP server host."),
  SMTP_PORT("smtp.port", SettingValueType.INTEGER, "587", "SMTP server port."),
  SMTP_USERNAME("smtp.username", SettingValueType.STRING, "", "SMTP authentication username."),
  SMTP_PASSWORD(
      "smtp.password",
      SettingValueType.PASSWORD,
      "",
      "SMTP authentication password (stored encrypted, redacted in API)."),
  SMTP_ENCRYPTION(
      "smtp.encryption",
      SettingValueType.ENUM,
      "starttls",
      "SMTP transport encryption: none, starttls (port 587), or tls (implicit SSL, port 465).",
      List.of("none", "starttls", "tls")),
  SMTP_FROM("smtp.from", SettingValueType.STRING, "", "Default From address for outgoing mail."),
  SMTP_FROM_NAME(
      "smtp.from_name",
      SettingValueType.STRING,
      "qnop",
      "Display name used in the From header of outgoing mail."),
  AUTH_SELF_REGISTRATION_ENABLED(
      "auth.self_registration_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Whether visitors may register their own local accounts."),
  AUTH_SELF_REGISTRATION_DEFAULT_ROLE(
      "auth.self_registration_default_role",
      SettingValueType.ENUM,
      "MEMBER",
      "Global role assigned to self-registered users (ADMIN is intentionally not selectable).",
      List.of("MEMBER", "AUDITOR")),
  AUTH_PASSWORD_RESET_ENABLED(
      "auth.password_reset_enabled",
      SettingValueType.BOOLEAN,
      "true",
      "Whether local users may reset their password by email."),
  AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES(
      "auth.password_reset_token_ttl_minutes",
      SettingValueType.INTEGER,
      "30",
      "Validity window of a password-reset token, in minutes."),
  NOTIFICATIONS_REVIEW_EMAILS_ENABLED(
      "notifications.review_emails_enabled",
      SettingValueType.BOOLEAN,
      "true",
      "Send email notifications for review activity (reviewer added, annotations, replies, status changes)."),
  NOTIFICATIONS_RETAIN_DAYS(
      "notifications.retain_days",
      SettingValueType.INTEGER,
      "90",
      "Days an in-app notification is kept before the Notification sweep deletes it, read or not (0"
          + " disables pruning). Only the record of the announcement goes — the review, annotation"
          + " or comment it points at is untouched."),
  REVIEW_FREE_REATTACH_ENABLED(
      "review.free_reattach_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Let authors re-position their own annotations even when the placement is healthy (admins always may)."),
  REVIEW_FINALIZE_WITH_OPEN_ANNOTATIONS(
      "review.finalize_with_open_annotations",
      SettingValueType.BOOLEAN,
      "false",
      "Let the review be finalized while annotations are still open; they are closed automatically with a standard comment."),
  REVIEW_ARCHIVE_AFTER_DAYS(
      "review.archive_after_days",
      SettingValueType.INTEGER,
      "90",
      "Days a completed review stays before it is archived out of the active lists (0 disables auto-archiving)."),
  REVIEW_PURGE_ARCHIVED_AFTER_DAYS(
      "review.purge_archived_after_days",
      SettingValueType.INTEGER,
      "180",
      "Days an archived review is kept before it is deleted permanently, including storage objects no other document references (0 disables purging). Irreversible — the Review purge job must be enabled as well."),
  BANNER_LOGIN_ENABLED(
      "banner.login_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Show a banner on the sign-in and other authentication screens. Readable by anyone who can"
          + " reach this server, signed in or not — never put internal detail here."),
  BANNER_LOGIN_SEVERITY(
      "banner.login_severity",
      SettingValueType.ENUM,
      "info",
      "Tone of the sign-in banner.",
      List.of("info", "warning", "critical")),
  BANNER_LOGIN_TEXT(
      "banner.login_text",
      SettingValueType.STRING,
      "",
      "Text of the sign-in banner, e.g. \"Demo installation — sign in with demo@qnop.io /"
          + " demo\". Empty hides the banner even when it is enabled."),
  BANNER_LOGIN_LINK_LABEL(
      "banner.login_link_label",
      SettingValueType.STRING,
      "",
      "Optional link shown after the sign-in banner's text; empty means no link."),
  BANNER_LOGIN_LINK_URL(
      "banner.login_link_url",
      SettingValueType.STRING,
      "",
      "Where the sign-in banner's link points (http/https)."),
  BANNER_APP_ENABLED(
      "banner.app_enabled",
      SettingValueType.BOOLEAN,
      "false",
      "Show a banner to signed-in users — maintenance windows, a degraded integration, a global"
          + " problem. Visible only after sign-in, and each user may dismiss it until the text"
          + " changes."),
  BANNER_APP_SEVERITY(
      "banner.app_severity",
      SettingValueType.ENUM,
      "info",
      "Tone of the in-app banner.",
      List.of("info", "warning", "critical")),
  BANNER_APP_TEXT(
      "banner.app_text",
      SettingValueType.STRING,
      "",
      "Text of the in-app banner, e.g. \"Maintenance on Saturday 20:00–22:00 UTC; uploads are"
          + " paused.\" Empty hides the banner even when it is enabled."),
  BANNER_APP_LINK_LABEL(
      "banner.app_link_label",
      SettingValueType.STRING,
      "",
      "Optional link shown after the in-app banner's text; empty means no link."),
  BANNER_APP_LINK_URL(
      "banner.app_link_url",
      SettingValueType.STRING,
      "",
      "Where the in-app banner's link points (http/https).");

  private static final Map<String, ApplicationSettingKey> BY_KEY =
      Arrays.stream(values())
          .collect(
              Collectors.toUnmodifiableMap(ApplicationSettingKey::getKey, Function.identity()));

  /**
   * Beyond-type value constraints per key (admin validation). Kept here rather than threaded
   * through the constructor so the enum constants stay readable; {@link ValueValidator} enforces
   * them.
   */
  private static final Map<ApplicationSettingKey, SettingConstraints> CONSTRAINTS =
      Map.ofEntries(
          Map.entry(UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB, SettingConstraints.range(1, 1024)),
          // Capped below the container's multipart ceiling (QNOP_UPLOAD_MULTIPART_LIMIT_MB,
          // default 55) so the service's clean 413 always fires first.
          Map.entry(UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB, SettingConstraints.range(1, 50)),
          Map.entry(AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES, SettingConstraints.range(1, 1440)),
          // 0 disables auto-archiving; the upper bound (~10 years) is a sanity cap.
          Map.entry(REVIEW_ARCHIVE_AFTER_DAYS, SettingConstraints.range(0, 3650)),
          // 0 disables purging; same ~10-year sanity cap as the archive window.
          Map.entry(REVIEW_PURGE_ARCHIVED_AFTER_DAYS, SettingConstraints.range(0, 3650)),
          Map.entry(NOTIFICATIONS_RETAIN_DAYS, SettingConstraints.range(0, 3650)),
          Map.entry(SMTP_PORT, SettingConstraints.range(1, 65535)),
          Map.entry(SMTP_FROM, SettingConstraints.format(SettingConstraints.ValueFormat.EMAIL)),
          Map.entry(
              GENERAL_BASE_URL, SettingConstraints.format(SettingConstraints.ValueFormat.URL)),
          Map.entry(
              GENERAL_DEFAULT_TIMEZONE,
              SettingConstraints.format(SettingConstraints.ValueFormat.TIMEZONE)),
          // The banner texts land in a layout (issue #664), so they are bounded rather
          // than trusted: one line that wraps to two, not a paragraph that pushes the
          // sign-in form off the screen. The link is bounded and must be http(s), which
          // is also what keeps a `javascript:` URL out of an anchor the whole
          // deployment sees.
          Map.entry(BANNER_LOGIN_TEXT, SettingConstraints.maxLength(200)),
          Map.entry(BANNER_LOGIN_LINK_LABEL, SettingConstraints.maxLength(40)),
          Map.entry(
              BANNER_LOGIN_LINK_URL,
              SettingConstraints.format(SettingConstraints.ValueFormat.URL).withMaxLength(500)),
          Map.entry(BANNER_APP_TEXT, SettingConstraints.maxLength(300)),
          Map.entry(BANNER_APP_LINK_LABEL, SettingConstraints.maxLength(40)),
          Map.entry(
              BANNER_APP_LINK_URL,
              SettingConstraints.format(SettingConstraints.ValueFormat.URL).withMaxLength(500)),
          // The analytics host is a URL this server will call (issue #666), so it is
          // checked like every other outbound target: http(s) only, and the SSRF
          // policy decides at call time whether the address itself is allowed.
          Map.entry(
              TRACKING_HOST,
              SettingConstraints.format(SettingConstraints.ValueFormat.URL).withMaxLength(300)),
          Map.entry(TRACKING_SITE_ID, SettingConstraints.maxLength(200)));

  private final String key;
  private final SettingValueType type;
  private final String defaultValue;
  private final String description;
  private final List<String> enumOptions;

  ApplicationSettingKey(
      String key, SettingValueType type, String defaultValue, String description) {
    this(key, type, defaultValue, description, List.of());
  }

  ApplicationSettingKey(
      String key,
      SettingValueType type,
      String defaultValue,
      String description,
      List<String> enumOptions) {
    this.key = key;
    this.type = type;
    this.defaultValue = defaultValue;
    this.description = description;
    this.enumOptions = List.copyOf(enumOptions);
  }

  public static Optional<ApplicationSettingKey> fromKey(String key) {
    return Optional.ofNullable(BY_KEY.get(key));
  }

  public String getKey() {
    return key;
  }

  public SettingValueType getType() {
    return type;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  public String getDescription() {
    return description;
  }

  public List<String> getEnumOptions() {
    return enumOptions;
  }

  /**
   * Value constraints beyond the declared type (range/format); {@link SettingConstraints#NONE} if
   * none.
   */
  public SettingConstraints getConstraints() {
    return CONSTRAINTS.getOrDefault(this, SettingConstraints.NONE);
  }

  /** A sensitive value is encrypted at rest and redacted in API responses. */
  public boolean isSensitive() {
    return type == SettingValueType.PASSWORD;
  }
}

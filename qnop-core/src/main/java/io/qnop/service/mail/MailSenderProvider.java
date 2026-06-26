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
package io.qnop.service.mail;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.SettingsChangeListener;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link JavaMailSender} from the runtime {@code smtp.*} application settings (issue #16)
 * and caches it (issue #19). SMTP is considered <em>enabled</em> when the {@code smtp.enabled}
 * master switch is on <em>and</em> {@code smtp.host} is non-blank; otherwise {@link #current()}
 * returns {@code null} and the mail layer treats sending as a no-op. The cached sender is rebuilt
 * lazily after any transport-shaping SMTP setting changes — this bean registers as a {@link
 * SettingsChangeListener}, so an admin editing SMTP settings transparently re-provisions the
 * transport without a restart.
 */
@Component
public class MailSenderProvider implements SettingsChangeListener {

  private static final Logger log = LoggerFactory.getLogger(MailSenderProvider.class);

  // Only the keys that shape the JavaMailSender transport invalidate the cache.
  // smtp.from / smtp.from_name are read per-send from the snapshot (see MailService),
  // not baked into the sender, so changing them must not drop the cached transport.
  private static final Set<ApplicationSettingKey> SMTP_KEYS =
      EnumSet.of(
          ApplicationSettingKey.SMTP_ENABLED,
          ApplicationSettingKey.SMTP_HOST,
          ApplicationSettingKey.SMTP_PORT,
          ApplicationSettingKey.SMTP_USERNAME,
          ApplicationSettingKey.SMTP_PASSWORD,
          ApplicationSettingKey.SMTP_ENCRYPTION);

  private final ApplicationSettingsService settings;
  private final AtomicReference<JavaMailSender> cached = new AtomicReference<>();

  // @Lazy breaks the construction cycle: ApplicationSettingsService injects the
  // List<SettingsChangeListener> (which includes this bean), while this bean
  // needs the settings service. A lazy proxy defers resolution until first use.
  public MailSenderProvider(@Lazy ApplicationSettingsService settings) {
    this.settings = settings;
  }

  /**
   * Whether outgoing mail should be attempted: the master switch ({@code smtp.enabled}) is on
   * <em>and</em> a host is configured. When either is missing the mail layer treats sends as a
   * no-op ({@code Skipped}).
   */
  public boolean isEnabled() {
    return settings.getBoolean(ApplicationSettingKey.SMTP_ENABLED)
        && !settings.getString(ApplicationSettingKey.SMTP_HOST).isBlank();
  }

  /** The configured default From address (may be blank if not set). */
  public String from() {
    return settings.getString(ApplicationSettingKey.SMTP_FROM);
  }

  /** The configured From display name (may be blank if not set). */
  public String fromName() {
    return settings.getString(ApplicationSettingKey.SMTP_FROM_NAME);
  }

  /** The cached sender, building it on first use; {@code null} when SMTP is not configured. */
  public JavaMailSender current() {
    if (!isEnabled()) {
      return null;
    }
    JavaMailSender existing = cached.get();
    if (existing != null) {
      return existing;
    }
    JavaMailSender built = build();
    return cached.compareAndSet(null, built) ? built : cached.get();
  }

  /** Drops the cached sender; the next {@link #current()} rebuilds from the latest settings. */
  public void invalidate() {
    cached.set(null);
  }

  @Override
  public void onSettingsChanged(Set<ApplicationSettingKey> changedKeys) {
    if (changedKeys.stream().anyMatch(SMTP_KEYS::contains)) {
      invalidate();
    }
  }

  private JavaMailSender build() {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(settings.getString(ApplicationSettingKey.SMTP_HOST));
    sender.setPort(settings.getInteger(ApplicationSettingKey.SMTP_PORT));
    sender.setDefaultEncoding("UTF-8");

    String username = settings.getString(ApplicationSettingKey.SMTP_USERNAME);
    boolean authenticated = !username.isBlank();
    if (authenticated) {
      sender.setUsername(username);
      sender.setPassword(settings.getString(ApplicationSettingKey.SMTP_PASSWORD));
    }

    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", String.valueOf(authenticated));
    applyEncryption(props, settings.getString(ApplicationSettingKey.SMTP_ENCRYPTION));
    return sender;
  }

  /**
   * Translates the {@code smtp.encryption} enum into Jakarta Mail transport properties:
   *
   * <ul>
   *   <li>{@code starttls} — upgrade a plaintext connection via STARTTLS, <em>required</em> (no
   *       silent downgrade to plaintext if the server lacks STARTTLS), typically port 587.
   *   <li>{@code tls} — implicit TLS/SSL from the first byte, typically port 465.
   *   <li>{@code none} — plaintext SMTP, no transport encryption (dev/test or trusted relays only).
   * </ul>
   *
   * An unrecognized value falls back to the safe {@code starttls} behavior; {@link
   * io.qnop.service.ValueValidator} already rejects unknown values on write, so this only guards
   * against out-of-band DB edits.
   */
  private static void applyEncryption(Properties props, String encryption) {
    switch (encryption) {
      case "tls" -> props.put("mail.smtp.ssl.enable", "true");
      case "none" -> log.info("SMTP encryption=none — the connection will not be encrypted");
      case "starttls" -> requireStartTls(props);
      default -> {
        log.warn("Unknown smtp.encryption '{}' — defaulting to required STARTTLS", encryption);
        requireStartTls(props);
      }
    }
  }

  private static void requireStartTls(Properties props) {
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.starttls.required", "true");
  }
}

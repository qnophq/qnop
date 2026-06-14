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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link JavaMailSender} from the runtime {@code smtp.*} application settings (issue #16)
 * and caches it (issue #19). SMTP is considered <em>configured</em> when {@code smtp.host} is
 * non-blank; otherwise {@link #current()} returns {@code null} and the mail layer treats sending as
 * a no-op. The cached sender is rebuilt lazily after any SMTP setting changes — this bean registers
 * as a {@link SettingsChangeListener}, so an admin editing SMTP settings transparently
 * re-provisions the transport without a restart.
 */
@Component
public class MailSenderProvider implements SettingsChangeListener {

  private static final Set<ApplicationSettingKey> SMTP_KEYS =
      EnumSet.of(
          ApplicationSettingKey.SMTP_HOST,
          ApplicationSettingKey.SMTP_PORT,
          ApplicationSettingKey.SMTP_USERNAME,
          ApplicationSettingKey.SMTP_PASSWORD,
          ApplicationSettingKey.SMTP_FROM,
          ApplicationSettingKey.SMTP_TLS_ENABLED);

  private final ApplicationSettingsService settings;
  private final AtomicReference<JavaMailSender> cached = new AtomicReference<>();

  public MailSenderProvider(ApplicationSettingsService settings) {
    this.settings = settings;
  }

  /** Whether SMTP is configured (host is set). */
  public boolean isEnabled() {
    return !settings.getString(ApplicationSettingKey.SMTP_HOST).isBlank();
  }

  /** The configured default From address (may be blank if not set). */
  public String from() {
    return settings.getString(ApplicationSettingKey.SMTP_FROM);
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
    props.put(
        "mail.smtp.starttls.enable",
        String.valueOf(settings.getBoolean(ApplicationSettingKey.SMTP_TLS_ENABLED)));
    return sender;
  }
}

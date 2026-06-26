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

import com.samskivert.mustache.Mustache;
import io.qnop.entity.MailTemplate;
import io.qnop.repository.MailTemplateRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders and administers mail templates (issue #19). Effective content is resolved per request
 * locale through a fallback chain — requested locale → configured default language → {@code en} →
 * the built-in {@link MailTemplateKey} catalog defaults — and rendered with logic-less Mustache
 * (strict: a missing variable raises rather than silently producing a blank). Two compilers keep
 * plain-text bodies unescaped and HTML bodies HTML-escaped.
 *
 * <p>The admin operations ({@link #listAll}, {@link #getEffective}, {@link #update}, {@link
 * #reset}, {@link #preview}) back the {@code /api/v1/admin/email/templates} endpoints and never
 * expose the JPA entity to the web layer.
 */
@Service
public class MailTemplateService {

  private static final String FALLBACK_LOCALE = "en";

  private final MailTemplateRepository repository;
  private final ApplicationSettingsService settings;
  private final EmailLayoutBuilder layoutBuilder;
  private final Mustache.Compiler plainCompiler = Mustache.compiler().escapeHTML(false);
  private final Mustache.Compiler htmlCompiler = Mustache.compiler().escapeHTML(true);

  public MailTemplateService(
      MailTemplateRepository repository,
      ApplicationSettingsService settings,
      EmailLayoutBuilder layoutBuilder) {
    this.repository = repository;
    this.settings = settings;
    this.layoutBuilder = layoutBuilder;
  }

  /**
   * Renders the template for {@code locale} (with fallback) using {@code vars}. Throws {@link
   * com.samskivert.mustache.MustacheException} if a referenced variable is missing.
   *
   * <p>When a stored row carries an HTML override it is used verbatim; otherwise the catalog
   * default's content fragment is rendered and wrapped in the shared branded chrome ({@link
   * EmailLayoutBuilder}), so every email — default or reset-to-default — looks consistent.
   */
  public RenderedMail render(MailTemplateKey key, Map<String, Object> vars, String locale) {
    Optional<MailTemplate> row = resolve(key, locale);
    String subject = row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject);
    String plain = row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain);
    String storedHtml = row.map(MailTemplate::getBodyHtml).filter(h -> !h.isBlank()).orElse(null);

    String renderedSubject = plainCompiler.compile(subject).execute(vars);
    String renderedPlain = plainCompiler.compile(plain).execute(vars);
    String renderedHtml =
        storedHtml != null
            ? htmlCompiler.compile(storedHtml).execute(vars)
            : buildBrandedHtml(key, vars);
    return new RenderedMail(renderedSubject, renderedPlain, renderedHtml);
  }

  /** Renders the catalog default content fragment and wraps it in the branded chrome. */
  private String buildBrandedHtml(MailTemplateKey key, Map<String, Object> vars) {
    String content = htmlCompiler.compile(key.defaultBodyHtmlContent()).execute(vars);
    String preheader = plainCompiler.compile(key.preheader()).execute(vars);
    Object brand = vars.get("siteName");
    Object actionUrl = vars.get("actionUrl");
    return layoutBuilder.wrap(
        brand == null ? null : brand.toString(),
        preheader,
        content,
        actionUrl == null ? null : actionUrl.toString(),
        key.ctaLabel(),
        "This is an automated message — please don't reply.");
  }

  /** Renders {@code key} with the provided variables, or representative sample data if none. */
  public RenderedMail preview(MailTemplateKey key, String locale, Map<String, Object> vars) {
    return render(key, vars == null || vars.isEmpty() ? sampleVars() : vars, locale);
  }

  /** The effective view per known template at the configured default locale. */
  public List<MailTemplateView> listAll() {
    String locale = defaultLocale();
    return java.util.Arrays.stream(MailTemplateKey.values())
        .map(key -> getEffective(key, locale))
        .toList();
  }

  /** The effective view for one template at {@code locale} (exact row, else catalog default). */
  public MailTemplateView getEffective(MailTemplateKey key, String locale) {
    return findRow(key, locale)
        .map(row -> toView(row, MailTemplateView.Source.DATABASE))
        .orElseGet(
            () ->
                new MailTemplateView(
                    key.key(),
                    locale,
                    key.defaultSubject(),
                    key.defaultBodyPlain(),
                    null,
                    MailTemplateView.Source.DEFAULT,
                    null,
                    null));
  }

  /** Upserts the stored override for {@code key}/{@code locale}; returns the saved view. */
  @Transactional
  public MailTemplateView update(
      MailTemplateKey key,
      String locale,
      String subject,
      String bodyPlain,
      String bodyHtml,
      UUID actor) {
    MailTemplate row =
        findRow(key, locale)
            .orElseGet(() -> new MailTemplate(key.key(), locale, subject, bodyPlain));
    row.setSubject(subject);
    row.setBodyPlain(bodyPlain);
    row.setBodyHtml(bodyHtml);
    row.setUpdatedBy(actor == null ? null : actor.toString());
    return toView(repository.save(row), MailTemplateView.Source.DATABASE);
  }

  /**
   * Removes the stored override, reverting to the catalog default. Returns whether a row existed.
   */
  @Transactional
  public boolean reset(MailTemplateKey key, String locale) {
    return findRow(key, locale)
        .map(
            row -> {
              repository.delete(row);
              return true;
            })
        .orElse(false);
  }

  /** The effective view for one template at the configured default locale. */
  public MailTemplateView getEffective(MailTemplateKey key) {
    return getEffective(key, defaultLocale());
  }

  /** Removes the default-locale override for {@code key}; returns whether a row existed. */
  @Transactional
  public boolean reset(MailTemplateKey key) {
    return reset(key, defaultLocale());
  }

  private Optional<MailTemplate> resolve(MailTemplateKey key, String locale) {
    String requested = locale == null || locale.isBlank() ? defaultLocale() : locale;
    return findRow(key, requested)
        .or(() -> findRow(key, defaultLocale()))
        .or(() -> findRow(key, FALLBACK_LOCALE));
  }

  private Optional<MailTemplate> findRow(MailTemplateKey key, String locale) {
    return repository.findByTemplateKeyAndLocale(key.key(), locale);
  }

  private String defaultLocale() {
    String configured = settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE);
    return configured == null || configured.isBlank() ? FALLBACK_LOCALE : configured;
  }

  private Map<String, Object> sampleVars() {
    return Map.of(
        "siteName", "qnop",
        "recipientName", "Jane Doe",
        "actionUrl", "https://qnop.example/action?token=SAMPLE",
        "expiresAtHuman", "in 30 minutes");
  }

  private MailTemplateView toView(MailTemplate row, MailTemplateView.Source source) {
    return new MailTemplateView(
        row.getTemplateKey(),
        row.getLocale(),
        row.getSubject(),
        row.getBodyPlain(),
        row.getBodyHtml(),
        source,
        row.getUpdatedAt(),
        row.getUpdatedBy());
  }
}

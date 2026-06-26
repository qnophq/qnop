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
import io.qnop.entity.User;
import io.qnop.repository.MailTemplateRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
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
  private static final String AUTOMATED_FOOTER =
      "This is an automated message — please don't reply.";

  private final MailTemplateRepository repository;
  private final UserRepository userRepository;
  private final ApplicationSettingsService settings;
  private final EmailLayoutBuilder layoutBuilder;
  private final Mustache.Compiler plainCompiler = Mustache.compiler().escapeHTML(false);
  private final Mustache.Compiler htmlCompiler = Mustache.compiler().escapeHTML(true);

  public MailTemplateService(
      MailTemplateRepository repository,
      UserRepository userRepository,
      ApplicationSettingsService settings,
      EmailLayoutBuilder layoutBuilder) {
    this.repository = repository;
    this.userRepository = userRepository;
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
    return renderContent(key, subject, plain, storedHtml, vars);
  }

  /**
   * Renders explicit {@code subject}/{@code plain}/{@code html} content with {@code vars}. A blank
   * or absent HTML source falls back to the catalog default fragment wrapped in the branded chrome,
   * so a draft preview without an HTML alternative still shows a representative HTML email.
   */
  private RenderedMail renderContent(
      MailTemplateKey key, String subject, String plain, String html, Map<String, Object> vars) {
    String renderedSubject = plainCompiler.compile(subject).execute(vars);
    String renderedPlain = plainCompiler.compile(plain).execute(vars);
    String renderedHtml =
        html != null && !html.isBlank()
            ? htmlCompiler.compile(html).execute(vars)
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
        AUTOMATED_FOOTER);
  }

  /** The catalog default HTML body as a template (placeholders intact), for compare/reset. */
  private String defaultBodyHtml(MailTemplateKey key) {
    return layoutBuilder.wrap(
        "{{siteName}}",
        key.preheader(),
        key.defaultBodyHtmlContent(),
        "{{actionUrl}}",
        key.ctaLabel(),
        AUTOMATED_FOOTER);
  }

  /** Previews the stored or catalog-default version of {@code key} (issue #141). */
  public MailPreview preview(MailTemplateKey key, String locale, Map<String, Object> overrides) {
    return preview(key, locale, overrides, null);
  }

  /**
   * Renders {@code key} for the editor preview (issue #141/#145). Per-key demo values are overlaid
   * with any caller-supplied overrides (overrides for unknown placeholders are ignored); the
   * effective variable set is returned so the editor can prefill its sample-variable inputs. When
   * {@code draft} is supplied its unsaved content is rendered for a live preview; otherwise the
   * stored override or catalog default is used. Rejects content that references a placeholder
   * outside the key's closed set.
   */
  public MailPreview preview(
      MailTemplateKey key, String locale, Map<String, Object> overrides, MailTemplateDraft draft) {
    Map<String, String> sampleVars = effectiveSampleVars(key, overrides);
    RenderedMail rendered;
    if (draft == null) {
      validateEffectivePlaceholders(key, locale);
      rendered = render(key, new HashMap<>(sampleVars), locale);
    } else {
      validatePlaceholders(key, draft.subject(), draft.bodyPlain(), draft.bodyHtml());
      rendered =
          renderContent(
              key, draft.subject(), draft.bodyPlain(), draft.bodyHtml(), new HashMap<>(sampleVars));
    }
    return new MailPreview(rendered, sampleVars);
  }

  /** Unsaved editor content rendered in place of the stored version for a live preview (#145). */
  public record MailTemplateDraft(String subject, String bodyPlain, String bodyHtml) {}

  /** The per-key demo values overlaid with caller overrides scoped to the key's placeholder set. */
  private Map<String, String> effectiveSampleVars(
      MailTemplateKey key, Map<String, Object> overrides) {
    Map<String, String> vars = key.sampleVars();
    if (overrides != null) {
      for (String placeholder : key.placeholders()) {
        Object override = overrides.get(placeholder);
        if (override != null) {
          vars.put(placeholder, override.toString());
        }
      }
    }
    return vars;
  }

  /** A preview render plus the effective variables it was rendered with. */
  public record MailPreview(RenderedMail rendered, Map<String, String> sampleVars) {}

  /**
   * The effective view per known template at the configured default locale. One {@code findById}
   * resolves each customised template's editor name; the catalog is a closed three-key set, so a
   * batch lookup would only matter if it grew significantly. Runs in one read-only transaction so
   * the per-template lookups share a session.
   */
  @Transactional(readOnly = true)
  public List<MailTemplateView> listAll() {
    String locale = defaultLocale();
    return java.util.Arrays.stream(MailTemplateKey.values())
        .map(key -> getEffective(key, locale))
        .toList();
  }

  /** The effective view for one template at {@code locale} (exact row, else catalog default). */
  public MailTemplateView getEffective(MailTemplateKey key, String locale) {
    return findRow(key, locale)
        .map(
            row ->
                buildView(
                    key,
                    row.getLocale(),
                    row.getSubject(),
                    row.getBodyPlain(),
                    row.getBodyHtml(),
                    MailTemplateView.Source.DATABASE,
                    row.getUpdatedAt(),
                    row.getUpdatedBy()))
        .orElseGet(
            () ->
                buildView(
                    key,
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
    validatePlaceholders(key, subject, bodyPlain, bodyHtml);
    MailTemplate row =
        findRow(key, locale)
            .orElseGet(() -> new MailTemplate(key.key(), locale, subject, bodyPlain));
    row.setSubject(subject);
    row.setBodyPlain(bodyPlain);
    row.setBodyHtml(bodyHtml);
    row.setUpdatedBy(actor == null ? null : actor.toString());
    MailTemplate saved = repository.save(row);
    return buildView(
        key,
        saved.getLocale(),
        saved.getSubject(),
        saved.getBodyPlain(),
        saved.getBodyHtml(),
        MailTemplateView.Source.DATABASE,
        saved.getUpdatedAt(),
        saved.getUpdatedBy());
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
  @Transactional(readOnly = true)
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

  /**
   * Assembles a view, attaching the key's editor metadata (friendly name, placeholders, defaults).
   */
  private MailTemplateView buildView(
      MailTemplateKey key,
      String locale,
      String subject,
      String bodyPlain,
      String bodyHtml,
      MailTemplateView.Source source,
      Instant updatedAt,
      String updatedBy) {
    return new MailTemplateView(
        key.key(),
        key.friendlyName(),
        locale,
        subject,
        bodyPlain,
        bodyHtml,
        source,
        key.placeholders(),
        key.defaultSubject(),
        key.defaultBodyPlain(),
        defaultBodyHtml(key),
        updatedAt,
        updatedBy,
        resolveUserName(updatedBy));
  }

  /**
   * Resolves the editing admin's display name for the attribution line. Returns null for built-in
   * defaults ({@code updatedBy == null}), a non-UUID value, or a since-deleted user — the UI then
   * shows the relative time without a name.
   */
  private String resolveUserName(String updatedBy) {
    if (updatedBy == null) {
      return null;
    }
    try {
      return userRepository
          .findById(UUID.fromString(updatedBy))
          .map(User::getDisplayName)
          .orElse(null);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Rejects a submitted body that references a placeholder outside the key's closed set. */
  private void validatePlaceholders(
      MailTemplateKey key, String subject, String bodyPlain, String bodyHtml) {
    SortedSet<String> unknown =
        MailPlaceholderValidator.unknownPlaceholders(
            Set.copyOf(key.placeholders()), subject, bodyPlain, bodyHtml);
    if (!unknown.isEmpty()) {
      throw new MailTemplateValidationException(unknown, key.placeholders());
    }
  }

  /** Validates the effective (stored or default) template content before a preview render. */
  private void validateEffectivePlaceholders(MailTemplateKey key, String locale) {
    Optional<MailTemplate> row = resolve(key, locale);
    String subject = row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject);
    String plain = row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain);
    String htmlSource =
        row.map(MailTemplate::getBodyHtml)
            .filter(h -> !h.isBlank())
            .orElseGet(key::defaultBodyHtmlContent);
    SortedSet<String> unknown =
        MailPlaceholderValidator.unknownPlaceholders(
            Set.copyOf(key.placeholders()), subject, plain, htmlSource);
    if (!unknown.isEmpty()) {
      throw new MailTemplateValidationException(unknown, key.placeholders());
    }
  }
}

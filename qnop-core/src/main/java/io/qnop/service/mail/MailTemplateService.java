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
import io.qnop.service.limits.FeatureDisabledException;
import io.qnop.service.limits.FeatureToggleProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders and administers mail templates (issue #19). Effective content is resolved per request
 * locale and rendered with logic-less Mustache (strict: a missing variable raises rather than
 * silently producing a blank). Two compilers keep plain-text bodies unescaped and HTML bodies
 * HTML-escaped.
 *
 * <p>Resolution (issue #853) walks the languages requested locale → configured default language →
 * {@code en}, and in each one tries the admin-edited row before the shipped default ({@link
 * MailTemplateDefaults}; English is the {@link MailTemplateKey} catalog). So an admin row in the
 * recipient's language wins, but an admin row in another language never beats the shipped default
 * in the recipient's own language. English always has a shipped default, so the walk always ends.
 *
 * <p>The admin operations ({@link #listAll}, {@link #getEffective}, {@link #update}, {@link
 * #reset}, {@link #preview}) back the {@code /api/v1/admin/email/templates} endpoints and never
 * expose the JPA entity to the web layer.
 */
@Service
public class MailTemplateService {

  private final MailTemplateRepository repository;
  private final UserRepository userRepository;
  private final ApplicationSettingsService settings;
  private final EmailLayoutBuilder layoutBuilder;
  private final FeatureToggleProperties features;
  private final MailTemplateDefaults defaults;

  /** The demo expiry of the preview's {@code expiresAtHuman}, as in the English demo value. */
  private static final int SAMPLE_EXPIRY_MINUTES = 30;

  private final Mustache.Compiler plainCompiler = Mustache.compiler().escapeHTML(false);
  private final Mustache.Compiler htmlCompiler = Mustache.compiler().escapeHTML(true);

  public MailTemplateService(
      MailTemplateRepository repository,
      UserRepository userRepository,
      ApplicationSettingsService settings,
      EmailLayoutBuilder layoutBuilder,
      FeatureToggleProperties features,
      MailTemplateDefaults defaults) {
    this.repository = repository;
    this.userRepository = userRepository;
    this.settings = settings;
    this.layoutBuilder = layoutBuilder;
    this.features = features;
    this.defaults = defaults;
  }

  /** Whether this deployment lets the templates be edited at all (issue #679). */
  public boolean editable() {
    return features.emailTemplates();
  }

  /**
   * Refuses when the deployment withholds template editing.
   *
   * <p>Reading and previewing stay open: they only show what the deployment already sends, and a
   * plan that excludes editing has no reason to hide the wording of its own mail.
   */
  public void requireEditable() {
    if (!features.emailTemplates()) {
      throw new FeatureDisabledException(FeatureDisabledException.Feature.EMAIL_TEMPLATES);
    }
  }

  /**
   * Renders the template for {@code locale} (with fallback, see the class comment) using {@code
   * vars}. Throws {@link com.samskivert.mustache.MustacheException} if a referenced variable is
   * missing.
   *
   * <p>When a stored row carries an HTML override it is used verbatim; otherwise the shipped
   * default's content fragment in the resolved language is rendered and wrapped in the shared
   * branded chrome ({@link EmailLayoutBuilder}), so every email — default or reset-to-default —
   * looks consistent.
   */
  public RenderedMail render(MailTemplateKey key, Map<String, Object> vars, String locale) {
    Resolved resolved = resolve(key, locale);
    return renderContent(
        resolved.locale(),
        resolved.defaults(),
        resolved.subject(),
        resolved.bodyPlain(),
        resolved.storedHtml(),
        vars);
  }

  /**
   * Renders explicit {@code subject}/{@code plain}/{@code html} content with {@code vars}. A blank
   * or absent HTML source falls back to the default fragment of {@code content} wrapped in the
   * branded chrome, so a draft preview without an HTML alternative still shows a representative
   * HTML email. The chrome (footer, CTA fallback, {@code lang}) follows {@code locale}, the
   * language the mail is rendered in.
   */
  private RenderedMail renderContent(
      String locale,
      MailTemplateContent content,
      String subject,
      String plain,
      String html,
      Map<String, Object> vars) {
    String renderedSubject = plainCompiler.compile(subject).execute(vars);
    String renderedPlain = plainCompiler.compile(plain).execute(vars);
    String renderedHtml =
        html != null && !html.isBlank()
            ? htmlCompiler.compile(html).execute(vars)
            : buildBrandedHtml(locale, content, vars);
    return new RenderedMail(renderedSubject, renderedPlain, renderedHtml);
  }

  /** Renders a default content fragment and wraps it in the branded chrome of {@code locale}. */
  private String buildBrandedHtml(
      String locale, MailTemplateContent content, Map<String, Object> vars) {
    String fragment = htmlCompiler.compile(content.bodyHtmlContent()).execute(vars);
    String preheader = plainCompiler.compile(content.preheader()).execute(vars);
    Object brand = vars.get("siteName");
    Object actionUrl = vars.get("actionUrl");
    return layoutBuilder.wrap(
        MailMessages.forLocale(locale),
        brand == null ? null : brand.toString(),
        preheader,
        fragment,
        actionUrl == null ? null : actionUrl.toString(),
        content.ctaLabel());
  }

  /** A default HTML body as a template (placeholders intact), for compare/reset. */
  private String defaultBodyHtml(String locale, MailTemplateContent content) {
    return layoutBuilder.wrap(
        MailMessages.forLocale(locale),
        "{{siteName}}",
        content.preheader(),
        content.bodyHtmlContent(),
        "{{actionUrl}}",
        content.ctaLabel());
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
    Map<String, String> sampleVars = effectiveSampleVars(key, locale, overrides);
    RenderedMail rendered;
    if (draft == null) {
      validateEffectivePlaceholders(key, locale);
      rendered = render(key, new HashMap<>(sampleVars), locale);
    } else {
      validatePlaceholders(key, draft.subject(), draft.bodyPlain(), draft.bodyHtml());
      String draftLocale = requestedOrDefault(locale);
      rendered =
          renderContent(
              draftLocale,
              defaults.findOrEnglish(key, draftLocale),
              draft.subject(),
              draft.bodyPlain(),
              draft.bodyHtml(),
              new HashMap<>(sampleVars));
    }
    return new MailPreview(rendered, sampleVars);
  }

  /** Unsaved editor content rendered in place of the stored version for a live preview (#145). */
  public record MailTemplateDraft(String subject, String bodyPlain, String bodyHtml) {}

  /**
   * The per-key demo values overlaid with caller overrides scoped to the key's placeholder set. The
   * values a real mail gets from the server's phrase bundle — the expiry time, the decision word
   * and the workflow states — are phrased in the preview's language, as a sender would (issue
   * #853).
   */
  private Map<String, String> effectiveSampleVars(
      MailTemplateKey key, String locale, Map<String, Object> overrides) {
    Map<String, String> vars = key.sampleVars();
    MailMessages messages = MailMessages.forLocale(requestedOrDefault(locale));
    vars.computeIfPresent(
        "expiresAtHuman",
        (name, value) -> messages.count("time.in.minutes", SAMPLE_EXPIRY_MINUTES));
    vars.computeIfPresent("decision", (name, value) -> messages.decision("resolved"));
    vars.computeIfPresent("oldState", (name, value) -> messages.workflowState("IN_REVIEW"));
    vars.computeIfPresent("newState", (name, value) -> messages.workflowState("CHANGES_REQUESTED"));
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

  /**
   * The effective view for one template at {@code locale}: the exact row, else the shipped default
   * in that language, else the English catalog default. The view's defaults (for compare/reset) are
   * the shipped ones of {@code locale}, so resetting returns to that language's built-in text.
   */
  public MailTemplateView getEffective(MailTemplateKey key, String locale) {
    String language = requireEditorLocale(locale);
    MailTemplateContent content = defaults.findOrEnglish(key, language);
    return findRow(key, language)
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
                    row.getUpdatedBy(),
                    content))
        .orElseGet(
            () ->
                buildView(
                    key,
                    language,
                    content.subject(),
                    content.bodyPlain(),
                    null,
                    MailTemplateView.Source.DEFAULT,
                    null,
                    null,
                    content));
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
    requireEditable();
    String language = requireEditorLocale(locale);
    validatePlaceholders(key, subject, bodyPlain, bodyHtml);
    MailTemplate row =
        findRow(key, language)
            .orElseGet(() -> new MailTemplate(key.key(), language, subject, bodyPlain));
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
        saved.getUpdatedBy(),
        defaults.findOrEnglish(key, language));
  }

  /**
   * Removes the stored override, reverting to the catalog default. Returns whether a row existed.
   */
  @Transactional
  public boolean reset(MailTemplateKey key, String locale) {
    requireEditable();
    return findRow(key, requireEditorLocale(locale))
        .map(
            row -> {
              repository.delete(row);
              return true;
            })
        .orElse(false);
  }

  /**
   * The languages the editor offers (issue #853): every language qnop ships mail in, plus the
   * configured default language — which counts for mail even when nothing ships in it ({@link
   * MailLocaleResolver}). English first, then alphabetical.
   */
  public List<String> editorLocales() {
    SortedSet<String> others = new TreeSet<>(defaults.locales());
    others.add(defaultLocale());
    others.remove(MailLocaleResolver.FALLBACK_LOCALE);
    List<String> locales = new ArrayList<>();
    locales.add(MailLocaleResolver.FALLBACK_LOCALE);
    locales.addAll(others);
    return List.copyOf(locales);
  }

  /** The configured default language, as the editor marks it. */
  public String editorDefaultLocale() {
    return defaultLocale();
  }

  /**
   * {@code locale} normalized, or the default language when blank.
   *
   * @throws UnsupportedMailLocaleException when the editor does not offer that language
   */
  public String requireEditorLocale(String locale) {
    String language = requestedOrDefault(locale);
    List<String> offered = editorLocales();
    if (!offered.contains(language)) {
      throw new UnsupportedMailLocaleException(language, offered);
    }
    return language;
  }

  /**
   * The status of every template in every offered language, from one query of the stored rows and
   * one batch lookup of the editing admins' names.
   */
  @Transactional(readOnly = true)
  public Map<MailTemplateKey, List<MailTemplateVariant>> variants() {
    return variantsOf(repository.findAll());
  }

  /** The status of {@code key} in every offered language, in {@link #editorLocales()} order. */
  @Transactional(readOnly = true)
  public List<MailTemplateVariant> variants(MailTemplateKey key) {
    return variantsOf(repository.findByTemplateKey(key.key())).getOrDefault(key, List.of());
  }

  private Map<MailTemplateKey, List<MailTemplateVariant>> variantsOf(List<MailTemplate> rows) {
    Map<String, MailTemplate> rowsByKeyAndLocale =
        rows.stream()
            .collect(
                Collectors.toMap(
                    row -> row.getTemplateKey() + "|" + row.getLocale(),
                    Function.identity(),
                    (first, second) -> first));
    Map<UUID, String> names = userNames(rows);
    List<String> locales = editorLocales();
    String instanceDefault = defaultLocale();
    Map<MailTemplateKey, List<MailTemplateVariant>> variants = new EnumMap<>(MailTemplateKey.class);
    for (MailTemplateKey key : MailTemplateKey.values()) {
      List<MailTemplateVariant> perLocale = new ArrayList<>();
      for (String locale : locales) {
        Optional<MailTemplateContent> shipped = defaults.find(key, locale);
        MailTemplate row = rowsByKeyAndLocale.get(key.key() + "|" + locale);
        perLocale.add(
            row != null
                ? new MailTemplateVariant(
                    locale,
                    MailTemplateView.Source.DATABASE,
                    shipped.isPresent(),
                    locale.equals(instanceDefault),
                    row.getSubject(),
                    row.getUpdatedAt(),
                    names.get(parseUserId(row.getUpdatedBy())))
                : new MailTemplateVariant(
                    locale,
                    MailTemplateView.Source.DEFAULT,
                    shipped.isPresent(),
                    locale.equals(instanceDefault),
                    shipped.orElseGet(() -> MailTemplateContent.english(key)).subject(),
                    null,
                    null));
      }
      variants.put(key, List.copyOf(perLocale));
    }
    return variants;
  }

  /** Display names of the admins who saved {@code rows}, keyed by their id. */
  private Map<UUID, String> userNames(List<MailTemplate> rows) {
    List<UUID> ids =
        rows.stream().map(row -> parseUserId(row.getUpdatedBy())).filter(id -> id != null).toList();
    // A HashMap, not Map.of(): built-in defaults look up a null id.
    Map<UUID, String> names = new HashMap<>();
    if (ids.isEmpty()) {
      return names;
    }
    for (User user : userRepository.findAllById(ids)) {
      names.put(user.getId(), user.getDisplayName());
    }
    return names;
  }

  /** The editing admin's id, or {@code null} for a built-in default or a non-UUID value. */
  private static UUID parseUserId(String updatedBy) {
    if (updatedBy == null) {
      return null;
    }
    try {
      return UUID.fromString(updatedBy);
    } catch (IllegalArgumentException e) {
      return null;
    }
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

  /**
   * Walks requested → default → {@code en}; in each language the admin row first, then the shipped
   * default. Always resolves, because English always has a shipped default.
   */
  private Resolved resolve(MailTemplateKey key, String locale) {
    LinkedHashSet<String> chain = new LinkedHashSet<>();
    chain.add(requestedOrDefault(locale));
    chain.add(defaultLocale());
    chain.add(MailLocaleResolver.FALLBACK_LOCALE);
    for (String candidate : chain) {
      Optional<MailTemplate> row = findRow(key, candidate);
      if (row.isPresent()) {
        return new Resolved(candidate, row.get(), defaults.findOrEnglish(key, candidate));
      }
      Optional<MailTemplateContent> shipped = defaults.find(key, candidate);
      if (shipped.isPresent()) {
        return new Resolved(candidate, null, shipped.get());
      }
    }
    // Unreachable: English is always shipped. Kept total rather than throwing.
    return new Resolved(MailLocaleResolver.FALLBACK_LOCALE, null, MailTemplateContent.english(key));
  }

  /**
   * One resolved template: the language it was found in (which the layout chrome and {@code lang}
   * follow), the stored row if an admin customised it ({@code null} otherwise), and the shipped
   * default of that language — or English where that language ships none. The default is the
   * HTML/CTA/preheader source when the row carries no HTML of its own.
   */
  private record Resolved(String locale, MailTemplate row, MailTemplateContent defaults) {

    String subject() {
      return row != null ? row.getSubject() : defaults.subject();
    }

    String bodyPlain() {
      return row != null ? row.getBodyPlain() : defaults.bodyPlain();
    }

    /** The row's own HTML, or {@code null} when the branded default is to be built. */
    String storedHtml() {
      if (row == null || row.getBodyHtml() == null || row.getBodyHtml().isBlank()) {
        return null;
      }
      return row.getBodyHtml();
    }

    /** The HTML source the placeholders are validated against. */
    String htmlSource() {
      String stored = storedHtml();
      return stored != null ? stored : defaults.bodyHtmlContent();
    }
  }

  private Optional<MailTemplate> findRow(MailTemplateKey key, String locale) {
    return repository.findByTemplateKeyAndLocale(key.key(), locale);
  }

  /** {@code locale} normalized ({@link MailLocaleResolver#normalize}), or the default language. */
  private String requestedOrDefault(String locale) {
    String language = MailLocaleResolver.normalize(locale);
    return language != null ? language : defaultLocale();
  }

  private String defaultLocale() {
    return MailLocaleResolver.firstOf(
        settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE));
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
      String updatedBy,
      MailTemplateContent content) {
    return new MailTemplateView(
        key.key(),
        key.friendlyName(),
        locale,
        subject,
        bodyPlain,
        bodyHtml,
        source,
        key.placeholders(),
        content.subject(),
        content.bodyPlain(),
        defaultBodyHtml(locale, content),
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
    Resolved resolved = resolve(key, locale);
    SortedSet<String> unknown =
        MailPlaceholderValidator.unknownPlaceholders(
            Set.copyOf(key.placeholders()),
            resolved.subject(),
            resolved.bodyPlain(),
            resolved.htmlSource());
    if (!unknown.isEmpty()) {
      throw new MailTemplateValidationException(unknown, key.placeholders());
    }
  }
}

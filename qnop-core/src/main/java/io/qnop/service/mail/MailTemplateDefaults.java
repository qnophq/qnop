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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The shipped, per-language defaults of every mail template (issue #853).
 *
 * <p>English is authoritative and stays in {@link MailTemplateKey}. Every other language is one
 * classpath resource, {@code mail/templates/<language>.yaml}, mapping each template key to its
 * {@code subject}, {@code bodyPlain}, {@code bodyHtmlContent}, {@code ctaLabel} and {@code
 * preheader}. YAML rather than JSON so the multi-line bodies and the HTML fragments read as they
 * render — block scalars, no escaped quotes — which is what a translator reviews.
 *
 * <p>Loaded once at startup and validated strictly; a bad resource fails the application start
 * rather than a mail at send time. Each file must define every template key with every field, must
 * name no unknown key or field, and may only reference placeholders from the key's closed set
 * ({@link MailPlaceholderValidator}); every field must compile and render as Mustache with the
 * key's demo variables. A template without a call to action in English must not have one in a
 * translation either, and a language may be shipped only once across the classpath. Adding a mail
 * language is adding a file here (plus its {@code mail/messages_<language>.properties}); no code
 * changes. An add-on jar may ship one too — see {@code docs/EXTENSION-POINTS.md}.
 */
@Component
public class MailTemplateDefaults {

  static final String LOCATION_PATTERN = "classpath*:mail/templates/*.yaml";

  private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}$");

  /** The same strict compilers the template service renders with. */
  private static final Mustache.Compiler PLAIN = Mustache.compiler().escapeHTML(false);

  private static final Mustache.Compiler HTML = Mustache.compiler().escapeHTML(true);

  private static final Set<String> FIELDS =
      Set.of("subject", "bodyPlain", "bodyHtmlContent", "ctaLabel", "preheader");

  private final Map<String, Map<MailTemplateKey, MailTemplateContent>> byLocale;
  private final SortedSet<String> locales;

  /** Loads and validates every shipped language from the classpath. */
  public MailTemplateDefaults() {
    this(loadClasspath());
  }

  private MailTemplateDefaults(Map<String, Map<MailTemplateKey, MailTemplateContent>> byLocale) {
    this.byLocale = byLocale;
    SortedSet<String> all = new TreeSet<>(byLocale.keySet());
    all.add(MailLocaleResolver.FALLBACK_LOCALE);
    this.locales = Collections.unmodifiableSortedSet(all);
  }

  /**
   * Builds the defaults from raw YAML sources keyed by language — the same parsing and validation
   * as the classpath load, for tests.
   *
   * @throws IllegalStateException when a source is invalid
   */
  public static MailTemplateDefaults fromYaml(Map<String, String> yamlByLocale) {
    Map<String, Map<MailTemplateKey, MailTemplateContent>> parsed = new TreeMap<>();
    yamlByLocale.forEach((locale, yaml) -> parsed.put(locale, parse(locale, yaml)));
    return new MailTemplateDefaults(Collections.unmodifiableMap(parsed));
  }

  /** Every language with built-in content, English included. */
  public SortedSet<String> locales() {
    return locales;
  }

  /** The built-in content of {@code key} in {@code locale}, if that language ships one. */
  public Optional<MailTemplateContent> find(MailTemplateKey key, String locale) {
    if (locale == null || MailLocaleResolver.FALLBACK_LOCALE.equals(locale)) {
      return Optional.of(MailTemplateContent.english(key));
    }
    return Optional.ofNullable(byLocale.getOrDefault(locale, Map.of()).get(key));
  }

  /** The built-in content of {@code key} in {@code locale}, else the English catalog content. */
  public MailTemplateContent findOrEnglish(MailTemplateKey key, String locale) {
    return find(key, locale).orElseGet(() -> MailTemplateContent.english(key));
  }

  private static Map<String, Map<MailTemplateKey, MailTemplateContent>> loadClasspath() {
    Resource[] resources;
    try {
      resources = new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot list the shipped mail templates", e);
    }
    return load(resources);
  }

  /** Builds the defaults from explicit resources — the classpath load's logic, for tests. */
  static MailTemplateDefaults fromResources(Resource... resources) {
    return new MailTemplateDefaults(load(resources));
  }

  /**
   * Parses every resource. Two resources for the same language — say a second {@code de.yaml} in an
   * add-on jar — fail the start rather than merge: which text wins would otherwise depend on
   * classpath order. The error names both resources.
   */
  private static Map<String, Map<MailTemplateKey, MailTemplateContent>> load(
      Resource... resources) {
    Map<String, Map<MailTemplateKey, MailTemplateContent>> parsed = new TreeMap<>();
    Map<String, String> sources = new TreeMap<>();
    for (Resource resource : resources) {
      String filename = resource.getFilename();
      if (filename == null || !filename.endsWith(".yaml")) {
        continue;
      }
      String locale = filename.substring(0, filename.length() - ".yaml".length());
      String previous = sources.putIfAbsent(locale, resource.getDescription());
      if (previous != null) {
        throw new IllegalStateException(
            "Mail templates for '"
                + locale
                + "' are shipped twice: "
                + previous
                + " and "
                + resource.getDescription());
      }
      try (InputStream in = resource.getInputStream()) {
        parsed.put(locale, parse(locale, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot read the mail templates for '" + locale + "'", e);
      }
    }
    return Collections.unmodifiableMap(parsed);
  }

  /** Parses and validates one language's resource. */
  static Map<MailTemplateKey, MailTemplateContent> parse(String locale, String yaml) {
    if (locale == null || !LANGUAGE.matcher(locale).matches()) {
      throw invalid(locale, "the file name must be a two-letter language code");
    }
    if (MailLocaleResolver.FALLBACK_LOCALE.equals(locale)) {
      throw invalid(locale, "English is defined by MailTemplateKey and must not be shipped twice");
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object root;
    try {
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      throw invalid(locale, "not valid YAML: " + e.getMessage());
    }
    if (!(root instanceof Map<?, ?> entries)) {
      throw invalid(locale, "expected a mapping of template keys");
    }
    Map<MailTemplateKey, MailTemplateContent> templates = new EnumMap<>(MailTemplateKey.class);
    for (Map.Entry<?, ?> entry : entries.entrySet()) {
      String name = String.valueOf(entry.getKey());
      MailTemplateKey key =
          MailTemplateKey.fromKey(name)
              .orElseThrow(() -> invalid(locale, "unknown template key '" + name + "'"));
      templates.put(key, content(locale, key, entry.getValue()));
    }
    for (MailTemplateKey key : MailTemplateKey.values()) {
      if (!templates.containsKey(key)) {
        throw invalid(locale, "template '" + key.key() + "' is missing");
      }
    }
    return Collections.unmodifiableMap(templates);
  }

  private static MailTemplateContent content(String locale, MailTemplateKey key, Object raw) {
    if (!(raw instanceof Map<?, ?> fields)) {
      throw invalid(locale, key.key() + ": expected a mapping of fields");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    fields.forEach((name, value) -> values.put(String.valueOf(name), value));
    for (String name : values.keySet()) {
      if (!FIELDS.contains(name)) {
        throw invalid(locale, key.key() + ": unknown field '" + name + "'");
      }
    }
    String subject = required(locale, key, values, "subject");
    String bodyPlain = required(locale, key, values, "bodyPlain");
    String bodyHtmlContent = required(locale, key, values, "bodyHtmlContent");
    String preheader = required(locale, key, values, "preheader");
    String ctaLabel = ctaLabel(locale, key, values.get("ctaLabel"));
    SortedSet<String> unknown =
        MailPlaceholderValidator.unknownPlaceholders(
            Set.copyOf(key.placeholders()), subject, bodyPlain, bodyHtmlContent, preheader);
    if (!unknown.isEmpty()) {
      throw invalid(locale, key.key() + ": unknown placeholders " + unknown);
    }
    compiles(locale, key, "subject", subject, PLAIN);
    compiles(locale, key, "bodyPlain", bodyPlain, PLAIN);
    compiles(locale, key, "bodyHtmlContent", bodyHtmlContent, HTML);
    compiles(locale, key, "preheader", preheader, PLAIN);
    return new MailTemplateContent(
        locale, subject, bodyPlain, bodyHtmlContent, ctaLabel, preheader);
  }

  /**
   * Compiles and renders one field with the key's demo variables, as a send would: an unbalanced
   * section, a broken delimiter or a triple-stache outside the key's set fails here, at startup,
   * instead of silently dropping a mail later (the placeholder check skips those tags).
   */
  private static void compiles(
      String locale, MailTemplateKey key, String field, String template, Mustache.Compiler with) {
    try {
      with.compile(template).execute(new java.util.HashMap<String, Object>(key.sampleVars()));
    } catch (RuntimeException e) {
      throw invalid(locale, key.key() + ": '" + field + "' does not render: " + e.getMessage());
    }
  }

  private static String required(
      String locale, MailTemplateKey key, Map<String, Object> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw invalid(locale, key.key() + ": '" + field + "' is missing or blank");
    }
    return text;
  }

  /** Present exactly where English has one, and plain text: the layout escapes, never renders. */
  private static String ctaLabel(String locale, MailTemplateKey key, Object value) {
    if (key.ctaLabel() == null) {
      if (value != null) {
        throw invalid(locale, key.key() + ": has no call to action, so no 'ctaLabel'");
      }
      return null;
    }
    if (!(value instanceof String label) || label.isBlank()) {
      throw invalid(locale, key.key() + ": 'ctaLabel' is missing or blank");
    }
    if (label.contains("{{")) {
      throw invalid(locale, key.key() + ": 'ctaLabel' is plain text, not a template");
    }
    return label;
  }

  private static IllegalStateException invalid(String locale, String problem) {
    return new IllegalStateException(
        "Invalid shipped mail templates for '" + locale + "': " + problem);
  }
}

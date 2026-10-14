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

import java.text.MessageFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The server-rendered phrases of one mail language (issue #853): the layout's footer and CTA
 * fallback, relative times, decision words, workflow state names, actor fallbacks and the digest's
 * wording. Backed by the {@code mail/messages*.properties} bundles on the classpath.
 *
 * <p>The root bundle is English, and a language without a bundle of its own — or a key its bundle
 * lacks — falls back to it, never to the JVM's default locale (a server running with a German
 * default must still write English mail to an English recipient).
 *
 * <p>Formatting follows Spring's {@code MessageSource} convention: {@link MessageFormat} runs only
 * when arguments are passed, so a value without arguments is taken verbatim (an apostrophe in it
 * needs no escaping). Plain, DB-free and immutable, so every caller can hold one per mail.
 */
public final class MailMessages {

  private static final String BUNDLE = "mail.messages";

  /** Root-only fallback, never the JVM default locale; properties only. */
  private static final ResourceBundle.Control NO_DEFAULT_LOCALE_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private final String locale;
  private final Locale javaLocale;
  private final ResourceBundle bundle;

  private MailMessages(String locale) {
    this.locale = MailLocaleResolver.firstOf(locale);
    this.javaLocale = Locale.forLanguageTag(this.locale);
    this.bundle =
        ResourceBundle.getBundle(
            BUNDLE, javaLocale, MailMessages.class.getClassLoader(), NO_DEFAULT_LOCALE_FALLBACK);
  }

  /** The phrases for {@code locale} (a language code such as {@code de}); blank means English. */
  public static MailMessages forLocale(String locale) {
    // ResourceBundle caches the parsed bundles itself, so this stays cheap per mail.
    return new MailMessages(locale);
  }

  /** The language code these phrases were requested for (e.g. {@code de}). */
  public String locale() {
    return locale;
  }

  /** The {@link Locale} used for formatting (month names, plurals). */
  public Locale javaLocale() {
    return javaLocale;
  }

  /**
   * The message for {@code key}; formatted with {@code args} through {@link MessageFormat} when any
   * are given, otherwise returned verbatim.
   *
   * @throws MissingResourceException when the key is not defined even in the English root bundle
   */
  public String get(String key, Object... args) {
    String pattern = bundle.getString(key);
    if (args == null || args.length == 0) {
      return pattern;
    }
    return new MessageFormat(pattern, javaLocale).format(args);
  }

  /**
   * A counted phrase: {@code {0}} selects the plural form, {@code {1}} prints the count as plain
   * digits (never grouped). E.g. {@code {0,choice,1#{1} update|1<{1} updates}}.
   */
  public String count(String key, long count) {
    return get(key, count, String.valueOf(count));
  }

  /** Whether {@code key} is defined (in this language or the English root). */
  public boolean has(String key) {
    return bundle.containsKey(key);
  }

  /** The do-not-reply line at the foot of every branded mail. */
  public String footer() {
    return get("layout.footer");
  }

  /**
   * The {@code {{decision}}} word for a raw decision ({@code resolved}, {@code dismissed}, {@code
   * reopened}); an unknown one is passed through unchanged.
   */
  public String decision(String raw) {
    if (raw == null) {
      return "";
    }
    String key = "decision." + raw;
    return has(key) ? get(key) : raw;
  }

  /**
   * A workflow state as people read it ({@code CHANGES_REQUESTED} → {@code Changes requested}). A
   * state the bundle does not name is humanized from its constant instead of being dropped.
   */
  public String workflowState(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String key = "workflow." + raw;
    return has(key) ? get(key) : humanize(raw);
  }

  /** The digest's time stamp format, in this language. */
  public DateTimeFormatter digestTimeFormat() {
    return DateTimeFormatter.ofPattern(get("digest.time.pattern"), javaLocale);
  }

  /** {@code SOME_STATE} → {@code Some state}: the fallback for a state without a translation. */
  static String humanize(String raw) {
    String lower = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}

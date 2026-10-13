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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.MailTemplate;
import io.qnop.entity.User;
import io.qnop.repository.MailTemplateRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.limits.FeatureDisabledException;
import io.qnop.service.limits.FeatureToggleProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailTemplateServiceTest {

  @Mock private MailTemplateRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private ApplicationSettingsService settings;

  /** Exactly the variable set the live send flows supply (issues #140, #316). */
  private static final Map<String, Object> FLOW_VARS =
      Map.ofEntries(
          Map.entry("siteName", "qnop"),
          Map.entry("recipientName", "Jane"),
          Map.entry("actionUrl", "https://qnop.example/reset?token=abc"),
          Map.entry("expiresAtHuman", "in 30 minutes"),
          Map.entry("actorName", "Alex Reviewer"),
          Map.entry("documentTitle", "Q3 contract draft"),
          Map.entry("annotationExcerpt", "The liability clause needs a cap."),
          Map.entry("commentExcerpt", "Agreed — let's cap it at 12 months."),
          Map.entry("decision", "resolved"),
          Map.entry("versionNumber", "3"),
          Map.entry("oldState", "In review"),
          Map.entry("newState", "Changes requested"),
          // The digest renders its per-document lines in the service and passes
          // them in as one block (issue #680) — Mustache cannot count.
          Map.entry("totalCount", 11),
          Map.entry("totalPhrase", "11 updates"),
          Map.entry("digestBody", "* Q3 contract draft — 3 new annotations, 7 comments"),
          Map.entry(
              "digestBodyHtml",
              "<ul><li><strong>Q3 contract draft</strong> — 3 new annotations, 7 comments</li></ul>"));

  /** The shipped defaults, loaded from the classpath like the application does. */
  private static final MailTemplateDefaults DEFAULTS = new MailTemplateDefaults();

  private MailTemplateService service;

  @BeforeEach
  void setUp() {
    service =
        new MailTemplateService(
            repository,
            userRepository,
            settings,
            new EmailLayoutBuilder(),
            FeatureToggleProperties.all(),
            DEFAULTS);
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE))
        .thenReturn("en");
  }

  @Test
  @DisplayName("renders subject + plain + html from a stored row, substituting variables")
  void rendersFromDatabaseRow() {
    MailTemplate row =
        new MailTemplate("auth.password_reset", "en", "Reset {{siteName}}", "Hi {{recipientName}}");
    row.setBodyHtml("<p>Hi {{recipientName}}</p>");
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.of(row));

    RenderedMail mail =
        service.render(
            MailTemplateKey.PASSWORD_RESET,
            Map.of("siteName", "qnop", "recipientName", "Jane"),
            "en");

    assertThat(mail.subject()).isEqualTo("Reset qnop");
    assertThat(mail.bodyPlain()).isEqualTo("Hi Jane");
    assertThat(mail.bodyHtml()).isEqualTo("<p>Hi Jane</p>");
  }

  @Test
  @DisplayName("falls back to the catalog defaults and builds branded HTML when no row exists")
  void fallsBackToCatalogDefaults() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    RenderedMail mail = service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "en");

    assertThat(mail.subject()).isEqualTo("Reset your qnop password");
    assertThat(mail.bodyPlain()).contains("Jane").contains("in 30 minutes");
    // The catalog default has no stored HTML — the branded chrome is built from the fragment.
    assertThat(mail.bodyHtml())
        .isNotNull()
        .contains("<!DOCTYPE html>")
        .contains("Reset your password")
        .contains("Choose a new password") // the CTA label
        .contains("https://qnop.example/reset?token=abc"); // the action URL in the CTA
  }

  @Test
  @DisplayName("every catalog template renders against exactly the variables its flow supplies")
  void everyTemplateRendersAgainstFlowVars() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    for (MailTemplateKey key : MailTemplateKey.values()) {
      RenderedMail mail = service.render(key, FLOW_VARS, "en");
      assertThat(mail.subject()).as("subject of %s", key).isNotBlank();
      assertThat(mail.bodyPlain()).as("plain of %s", key).isNotBlank();
      assertThat(mail.bodyHtml()).as("html of %s", key).isNotNull().contains("qnop");
    }
  }

  @Test
  @DisplayName("HTML body escapes variables; plain body does not")
  void escapesHtmlVariables() {
    MailTemplate row = new MailTemplate("auth.password_reset", "en", "s", "plain {{x}}");
    row.setBodyHtml("<p>{{x}}</p>");
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.of(row));

    RenderedMail mail =
        service.render(MailTemplateKey.PASSWORD_RESET, Map.of("x", "<b>hi</b>"), "en");

    assertThat(mail.bodyPlain()).isEqualTo("plain <b>hi</b>");
    assertThat(mail.bodyHtml()).contains("&lt;b&gt;hi&lt;/b&gt;").doesNotContain("<b>hi</b>");
  }

  @Test
  @DisplayName("a missing variable raises (strict rendering)")
  void missingVariableThrows() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.render(MailTemplateKey.PASSWORD_RESET, Map.of(), "en"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("preview uses per-key demo data and returns the effective sampleVars")
  void previewUsesSampleVars() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    MailTemplateService.MailPreview preview =
        service.preview(MailTemplateKey.REGISTRATION_VERIFICATION, "en", Map.of());

    assertThat(preview.rendered().subject()).isEqualTo("Verify your qnop account");
    assertThat(preview.rendered().bodyPlain()).contains("Jane Doe");
    assertThat(preview.sampleVars())
        .containsEntry("recipientName", "Jane Doe")
        .containsEntry("expiresAtHuman", "in 30 minutes")
        .containsOnlyKeys("siteName", "recipientName", "actionUrl", "expiresAtHuman");
  }

  @Test
  @DisplayName("preview overlays caller overrides and ignores unknown override keys")
  void previewOverlaysOverrides() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    MailTemplateService.MailPreview preview =
        service.preview(
            MailTemplateKey.PASSWORD_RESET,
            "en",
            Map.of("recipientName", "Alice", "bogus", "ignored"));

    assertThat(preview.rendered().bodyPlain()).contains("Alice");
    assertThat(preview.sampleVars())
        .containsEntry("recipientName", "Alice")
        .doesNotContainKey("bogus");
  }

  @Test
  @DisplayName("preview renders the editor draft content instead of the stored version")
  void previewRendersDraft() {
    MailTemplateService.MailPreview preview =
        service.preview(
            MailTemplateKey.PASSWORD_RESET,
            "en",
            Map.of(),
            new MailTemplateService.MailTemplateDraft(
                "Draft subject for {{siteName}}", "Draft body, hi {{recipientName}}", null));

    assertThat(preview.rendered().subject()).isEqualTo("Draft subject for qnop");
    assertThat(preview.rendered().bodyPlain()).isEqualTo("Draft body, hi Jane Doe");
  }

  @Test
  @DisplayName("preview renders a draft HTML alternative, escaping variables")
  void previewRendersDraftHtml() {
    MailTemplateService.MailPreview preview =
        service.preview(
            MailTemplateKey.PASSWORD_RESET,
            "en",
            Map.of(),
            new MailTemplateService.MailTemplateDraft(
                "Subject", "Plain", "<p>Hello {{recipientName}}</p>"));

    assertThat(preview.rendered().bodyHtml()).contains("<p>Hello Jane Doe</p>");
  }

  @Test
  @DisplayName("preview rejects a draft that references a placeholder outside the closed set")
  void previewRejectsUnknownDraftPlaceholder() {
    MailTemplateService.MailTemplateDraft draft =
        new MailTemplateService.MailTemplateDraft("Subject", "Body {{unknownVar}}", null);

    assertThatThrownBy(() -> service.preview(MailTemplateKey.PASSWORD_RESET, "en", Map.of(), draft))
        .isInstanceOf(MailTemplateValidationException.class)
        .hasMessageContaining("unknownVar");
  }

  @Test
  @DisplayName("getEffective carries friendlyName, sorted placeholders and the catalog defaults")
  void getEffectivePopulatesEditorMetadata() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "en");

    assertThat(view.friendlyName()).isEqualTo("Password reset");
    assertThat(view.placeholders())
        .containsExactly("actionUrl", "expiresAtHuman", "recipientName", "siteName");
    assertThat(view.defaultSubject()).isEqualTo("Reset your {{siteName}} password");
    assertThat(view.defaultBodyPlain()).contains("{{recipientName}}", "{{actionUrl}}");
    assertThat(view.defaultBodyHtml()).contains("<!DOCTYPE html>", "{{actionUrl}}");
  }

  @Test
  @DisplayName("updating a body with an unknown placeholder is rejected")
  void updateRejectsUnknownPlaceholder() {
    assertThatThrownBy(
            () ->
                service.update(
                    MailTemplateKey.PASSWORD_RESET,
                    "en",
                    "Hi {{recipientName}}",
                    "Reset via {{actionUrl}} for {{unknownThing}}",
                    null,
                    null))
        .isInstanceOf(MailTemplateValidationException.class)
        .hasMessageContaining("{{unknownThing}}");
  }

  @Test
  @DisplayName("updating a body that uses only known placeholders is accepted")
  void updateAcceptsKnownPlaceholders() {
    MailTemplate row = new MailTemplate("auth.password_reset", "en", "s", "p");
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.of(row));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    MailTemplateView saved =
        service.update(
            MailTemplateKey.PASSWORD_RESET,
            "en",
            "Reset for {{recipientName}}",
            "Open {{actionUrl}} — expires {{expiresAtHuman}}",
            "<p>{{siteName}}</p>",
            null);

    assertThat(saved.source()).isEqualTo(MailTemplateView.Source.DATABASE);
  }

  @Test
  @DisplayName("getEffective reports DEFAULT source when no row is stored")
  void getEffectiveReportsDefaultSource() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "en");

    assertThat(view.source()).isEqualTo(MailTemplateView.Source.DEFAULT);
    assertThat(view.subject()).isEqualTo(MailTemplateKey.PASSWORD_RESET.defaultSubject());
  }

  @Test
  @DisplayName("resolves the editing admin's display name for attribution")
  void resolvesUpdatedByName() {
    UUID actor = UUID.randomUUID();
    User editor = User.internal("Ada Admin", "ada@qnop.example", "ada", "hash");
    when(userRepository.findById(actor)).thenReturn(Optional.of(editor));
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    MailTemplateView saved =
        service.update(
            MailTemplateKey.PASSWORD_RESET,
            "en",
            "Reset for {{recipientName}}",
            "Open {{actionUrl}}",
            null,
            actor);

    assertThat(saved.updatedBy()).isEqualTo(actor.toString());
    assertThat(saved.updatedByName()).isEqualTo("Ada Admin");
  }

  @Test
  @DisplayName("a built-in default has no updatedBy name")
  void defaultHasNoUpdatedByName() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "en");

    assertThat(view.updatedBy()).isNull();
    assertThat(view.updatedByName()).isNull();
  }

  @Test
  @DisplayName("a since-deleted editor degrades to no name")
  void deletedEditorHasNoName() {
    MailTemplate row = new MailTemplate("auth.password_reset", "en", "s", "p");
    String actor = UUID.randomUUID().toString();
    row.setUpdatedBy(actor);
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.of(row));
    when(userRepository.findById(UUID.fromString(actor))).thenReturn(Optional.empty());

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "en");

    assertThat(view.updatedBy()).isEqualTo(actor);
    assertThat(view.updatedByName()).isNull();
  }

  @Test
  @DisplayName("a malformed stored updatedBy degrades to no name instead of throwing")
  void malformedUpdatedByDegradesToNull() {
    MailTemplate row = new MailTemplate("auth.password_reset", "en", "s", "p");
    row.setUpdatedBy("not-a-uuid");
    when(repository.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .thenReturn(Optional.of(row));

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "en");

    assertThat(view.updatedByName()).isNull();
  }

  @Test
  @DisplayName("a deployment may withhold template editing, and still render what it sends")
  void editingCanBeWithheld() {
    MailTemplateService withoutEditing =
        new MailTemplateService(
            repository,
            userRepository,
            settings,
            new EmailLayoutBuilder(),
            FeatureToggleProperties.allExcept(FeatureDisabledException.Feature.EMAIL_TEMPLATES),
            DEFAULTS);

    assertThatThrownBy(
            () ->
                withoutEditing.update(
                    MailTemplateKey.PASSWORD_RESET, "en", "s", "plain", "<p>html</p>", null))
        .isInstanceOf(FeatureDisabledException.class);
    // Reset is the same capability by another route: it changes what goes out.
    assertThatThrownBy(() -> withoutEditing.reset(MailTemplateKey.PASSWORD_RESET, "en"))
        .isInstanceOf(FeatureDisabledException.class);

    assertThat(withoutEditing.editable()).isFalse();
    verify(repository, never()).save(any());
  }

  // --- per-language resolution (issue #853) ------------------------------------

  private void noRows() {
    when(repository.findByTemplateKeyAndLocale(any(), any())).thenReturn(Optional.empty());
  }

  private void row(MailTemplateKey key, String locale, String subject) {
    when(repository.findByTemplateKeyAndLocale(key.key(), locale))
        .thenReturn(Optional.of(new MailTemplate(key.key(), locale, subject, "plain " + locale)));
  }

  @Test
  @DisplayName("a German recipient gets the shipped German default, CTA, footer and lang")
  void germanRecipientGetsTheShippedGermanDefault() {
    noRows();

    RenderedMail mail = service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "de");

    assertThat(mail.subject()).isEqualTo("Setze dein qnop-Passwort zurück");
    assertThat(mail.bodyPlain()).startsWith("Hallo Jane,");
    assertThat(mail.bodyHtml())
        .contains("<html lang=\"de\">")
        .contains("Setze dein Passwort zurück")
        .contains("Neues Passwort wählen")
        .contains("Dies ist eine automatische Nachricht")
        .contains("Der Button funktioniert nicht?")
        .doesNotContain("This is an automated message");
  }

  @Test
  @DisplayName("an admin row in the recipient's language beats the shipped default")
  void adminRowInTheRecipientsLanguageWins() {
    noRows();
    row(MailTemplateKey.PASSWORD_RESET, "de", "Eigener Betreff {{siteName}}");

    RenderedMail mail = service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "de");

    assertThat(mail.subject()).isEqualTo("Eigener Betreff qnop");
    // No HTML of its own: the German branded default is built around it.
    assertThat(mail.bodyHtml()).contains("Neues Passwort wählen");
  }

  @Test
  @DisplayName("an admin row in the default language never beats the recipient's shipped default")
  void defaultLanguageRowDoesNotBeatTheRecipientsLanguage() {
    noRows();
    row(MailTemplateKey.PASSWORD_RESET, "en", "Custom English {{siteName}}");

    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "de").subject())
        .isEqualTo("Setze dein qnop-Passwort zurück");
    // …while an English recipient still gets the customised English row.
    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "en").subject())
        .isEqualTo("Custom English qnop");
  }

  @Test
  @DisplayName("an unshipped language falls to the default language: its row, then its default")
  void unshippedLanguageFallsToTheDefaultLanguage() {
    noRows();
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("de");

    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "it").subject())
        .isEqualTo("Setze dein qnop-Passwort zurück");

    row(MailTemplateKey.PASSWORD_RESET, "de", "Standardsprache {{siteName}}");
    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "it").subject())
        .isEqualTo("Standardsprache qnop");
    // No language at all means the default language.
    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, null).subject())
        .isEqualTo("Standardsprache qnop");
  }

  @Test
  @DisplayName("with an English default an unshipped language reaches the English row, then enum")
  void unshippedLanguageWithEnglishDefault() {
    noRows();
    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "it").subject())
        .isEqualTo("Reset your qnop password");

    row(MailTemplateKey.PASSWORD_RESET, "en", "Custom English {{siteName}}");
    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, "it").subject())
        .isEqualTo("Custom English qnop");
  }

  @Test
  @DisplayName("every template renders completely in German, with German chrome")
  void everyTemplateRendersInGerman() {
    noRows();
    MailTemplateDefaults defaults = new MailTemplateDefaults();

    for (MailTemplateKey key : MailTemplateKey.values()) {
      RenderedMail mail = service.render(key, FLOW_VARS, "de");
      MailTemplateContent german = defaults.find(key, "de").orElseThrow();

      assertThat(mail.subject())
          .as(key.key())
          .doesNotContain("{{")
          .isNotEqualTo(key.defaultSubject());
      assertThat(mail.bodyPlain()).as(key.key()).doesNotContain("{{").startsWith("Hallo Jane,");
      assertThat(mail.bodyHtml())
          .as(key.key())
          .doesNotContain("{{")
          .contains("<html lang=\"de\">")
          .contains("Dies ist eine automatische Nachricht");
      if (german.ctaLabel() != null) {
        assertThat(mail.bodyHtml()).as(key.key()).contains(german.ctaLabel());
      }
    }
  }

  @Test
  @DisplayName("the editor view of a German default shows German defaults to compare and reset to")
  void germanEditorView() {
    noRows();

    MailTemplateView view = service.getEffective(MailTemplateKey.PASSWORD_RESET, "de");

    assertThat(view.source()).isEqualTo(MailTemplateView.Source.DEFAULT);
    assertThat(view.locale()).isEqualTo("de");
    assertThat(view.subject()).isEqualTo("Setze dein {{siteName}}-Passwort zurück");
    assertThat(view.defaultSubject()).isEqualTo("Setze dein {{siteName}}-Passwort zurück");
    assertThat(view.defaultBodyHtml()).contains("<html lang=\"de\">").contains("{{siteName}}");
  }

  @Test
  @DisplayName("a German preview renders the German default with the demo variables")
  void germanPreview() {
    noRows();

    MailTemplateService.MailPreview preview =
        service.preview(MailTemplateKey.REVIEW_MENTION, "de", Map.of());

    assertThat(preview.rendered().subject())
        .isEqualTo("Alex Reviewer hat dich in „Q3 contract draft“ erwähnt");
  }

  @Test
  @DisplayName(
      "every entry point normalizes the language: de-AT, de_DE and DE are de (#853 review)")
  void entryPointsNormalizeTheLanguage() {
    noRows();

    for (String tag : new String[] {"de-AT", "de_DE", "DE", " de "}) {
      assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, tag).subject())
          .as(tag)
          .isEqualTo("Setze dein qnop-Passwort zurück");
      assertThat(service.getEffective(MailTemplateKey.PASSWORD_RESET, tag).locale())
          .as(tag)
          .isEqualTo("de");
      assertThat(
              service.preview(MailTemplateKey.PASSWORD_RESET, tag, Map.of()).rendered().subject())
          .as(tag)
          .isEqualTo("Setze dein qnop-Passwort zurück");
    }

    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    MailTemplateView saved =
        service.update(MailTemplateKey.PASSWORD_RESET, "DE-at", "Betreff", "Text", null, null);
    assertThat(saved.locale()).isEqualTo("de");

    service.reset(MailTemplateKey.PASSWORD_RESET, "de_DE");
    verify(repository, org.mockito.Mockito.atLeastOnce())
        .findByTemplateKeyAndLocale("auth.password_reset", "de");
  }

  @Test
  @DisplayName("a regional default-language setting reaches the shipped language")
  void regionalDefaultLanguage() {
    noRows();
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("de-DE");

    assertThat(service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, null).subject())
        .isEqualTo("Setze dein qnop-Passwort zurück");
  }

  @Test
  @DisplayName("a row-only language gets its own lang attribute; English chrome text by fallback")
  void rowOnlyLanguageKeepsItsLang() {
    noRows();
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("it");
    row(MailTemplateKey.PASSWORD_RESET, "it", "Reimposta {{siteName}}");

    RenderedMail mail = service.render(MailTemplateKey.PASSWORD_RESET, FLOW_VARS, null);

    assertThat(mail.subject()).isEqualTo("Reimposta qnop");
    assertThat(mail.bodyHtml()).contains("<html lang=\"it\">");
  }

  @Test
  @DisplayName("the editor offers every shipped language plus the default, English first (#853)")
  void editorLocalesAreShippedPlusDefault() {
    assertThat(service.editorLocales()).containsExactly("en", "de", "es", "fr", "pt");

    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("it-IT");
    assertThat(service.editorLocales()).containsExactly("en", "de", "es", "fr", "it", "pt");
    assertThat(service.editorDefaultLocale()).isEqualTo("it");
  }

  @Test
  @DisplayName("reading, saving and resetting refuse a language the editor does not offer")
  void unofferedLanguageIsRefused() {
    assertThatThrownBy(() -> service.getEffective(MailTemplateKey.PASSWORD_RESET, "it"))
        .isInstanceOf(UnsupportedMailLocaleException.class)
        .hasMessageContaining("'it'");
    assertThatThrownBy(
            () -> service.update(MailTemplateKey.PASSWORD_RESET, "it", "S", "B", null, null))
        .isInstanceOf(UnsupportedMailLocaleException.class);
    assertThatThrownBy(() -> service.reset(MailTemplateKey.PASSWORD_RESET, "it"))
        .isInstanceOf(UnsupportedMailLocaleException.class);
    verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  @DisplayName("variants report each language's source, shipping and editor in one query")
  void variantsReportEveryLanguage() {
    when(settings.getString(ApplicationSettingKey.GENERAL_DEFAULT_LANGUAGE)).thenReturn("it");
    UUID adminId = UUID.randomUUID();
    MailTemplate german =
        new MailTemplate("auth.password_reset", "de", "Eigener Betreff", "Eigener Text");
    german.setUpdatedBy(adminId.toString());
    when(repository.findAll()).thenReturn(List.of(german));
    io.qnop.entity.User admin = org.mockito.Mockito.mock(io.qnop.entity.User.class);
    when(admin.getId()).thenReturn(adminId);
    when(admin.getDisplayName()).thenReturn("Ada Admin");
    when(userRepository.findAllById(List.of(adminId))).thenReturn(List.of(admin));

    Map<MailTemplateKey, List<MailTemplateVariant>> all = service.variants();

    assertThat(all).hasSize(MailTemplateKey.values().length);
    List<MailTemplateVariant> reset = all.get(MailTemplateKey.PASSWORD_RESET);
    assertThat(reset)
        .extracting(MailTemplateVariant::locale)
        .containsExactly("en", "de", "es", "fr", "it", "pt");
    MailTemplateVariant de = reset.get(1);
    assertThat(de.source()).isEqualTo(MailTemplateView.Source.DATABASE);
    assertThat(de.subject()).isEqualTo("Eigener Betreff");
    assertThat(de.updatedByName()).isEqualTo("Ada Admin");
    MailTemplateVariant es = reset.get(2);
    assertThat(es.source()).isEqualTo(MailTemplateView.Source.DEFAULT);
    assertThat(es.shipped()).isTrue();
    assertThat(es.subject())
        .isEqualTo(DEFAULTS.find(MailTemplateKey.PASSWORD_RESET, "es").orElseThrow().subject());
    // The default language ships nothing: marked unshipped, with the English subject in effect.
    MailTemplateVariant it = reset.get(4);
    assertThat(it.instanceDefault()).isTrue();
    assertThat(it.shipped()).isFalse();
    assertThat(it.subject()).isEqualTo("Reset your {{siteName}} password");
    assertThat(reset.get(0).instanceDefault()).isFalse();
  }
}

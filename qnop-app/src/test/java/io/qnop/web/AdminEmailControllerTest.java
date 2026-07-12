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
package io.qnop.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.MailTemplateService;
import io.qnop.service.mail.MailTemplateService.MailPreview;
import io.qnop.service.mail.MailTemplateValidationException;
import io.qnop.service.mail.MailTemplateView;
import io.qnop.service.mail.RenderedMail;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link AdminEmailController} (issue #141): the mail-template metadata fields
 * on the response and the placeholder-validation 400 arms. Security filters are disabled — the
 * security chain is exercised elsewhere; here we test the handler + DTO mapping. No DB.
 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {AdminEmailController.class, ApiPathConfig.class})
class AdminEmailControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MailService mailService;
  @MockitoBean private MailTemplateService templates;

  private static MailTemplateView view() {
    return new MailTemplateView(
        "auth.password_reset",
        "Password reset",
        "en",
        "Reset {{siteName}}",
        "Hi {{recipientName}}",
        null,
        MailTemplateView.Source.DEFAULT,
        List.of("actionUrl", "expiresAtHuman", "recipientName", "siteName"),
        "Reset your {{siteName}} password",
        "Hi {{recipientName}}, {{actionUrl}}",
        "<!DOCTYPE html>…{{actionUrl}}…",
        null,
        null,
        null);
  }

  @Test
  void getTemplateExposesEditorMetadata() throws Exception {
    when(templates.getEffective(MailTemplateKey.PASSWORD_RESET)).thenReturn(view());

    mockMvc
        .perform(get("/api/v1/admin/email/templates/auth.password_reset"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.friendlyName").value("Password reset"))
        .andExpect(jsonPath("$.placeholders[0]").value("actionUrl"))
        .andExpect(jsonPath("$.placeholders.length()").value(4))
        .andExpect(jsonPath("$.defaultSubject").value("Reset your {{siteName}} password"))
        .andExpect(jsonPath("$.defaultBodyPlain").value("Hi {{recipientName}}, {{actionUrl}}"))
        .andExpect(jsonPath("$.defaultBodyHtml").exists());
  }

  @Test
  void templateTestSendRendersAndSends() throws Exception {
    MailPreview preview =
        new MailPreview(
            new RenderedMail("Reset qnop", "Hi Jane Doe", "<p>Hi</p>"), Map.of("siteName", "qnop"));
    when(templates.preview(eq(MailTemplateKey.PASSWORD_RESET), any(), any(), any()))
        .thenReturn(preview);
    when(mailService.sendMail(any(), any(), any(), any()))
        .thenReturn(new MailService.SendResult.Sent("admin@qnop.test"));

    mockMvc
        .perform(
            post("/api/v1/admin/email/templates/auth.password_reset/test")
                .contentType("application/json")
                .content("{\"recipient\":\"admin@qnop.test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SENT"))
        .andExpect(jsonPath("$.detail").value("Sent to admin@qnop.test"));

    // The RENDERED template travels, HTML alternative included.
    verify(mailService).sendMail("admin@qnop.test", "Reset qnop", "Hi Jane Doe", "<p>Hi</p>");
  }

  @Test
  void templateTestSendAnswers404ForUnknownKey() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/email/templates/no.such_template/test")
                .contentType("application/json")
                .content("{\"recipient\":\"admin@qnop.test\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void previewReturnsEffectiveSampleVars() throws Exception {
    MailPreview preview =
        new MailPreview(
            new RenderedMail("Reset qnop", "Hi Jane Doe", "<p>Hi</p>"),
            Map.of("siteName", "qnop", "recipientName", "Jane Doe"));
    when(templates.preview(eq(MailTemplateKey.PASSWORD_RESET), any(), any(), any()))
        .thenReturn(preview);

    mockMvc
        .perform(
            post("/api/v1/admin/email/templates/auth.password_reset/preview")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("Reset qnop"))
        .andExpect(jsonPath("$.sampleVars.recipientName").value("Jane Doe"));

    // No draft fields → preview the stored/default version.
    verify(templates).preview(eq(MailTemplateKey.PASSWORD_RESET), any(), any(), eq(null));
  }

  @Test
  void previewRendersSubmittedDraft() throws Exception {
    MailPreview preview =
        new MailPreview(
            new RenderedMail("Draft subject", "Draft body", "<p>Draft</p>"),
            Map.of("siteName", "qnop"));
    when(templates.preview(eq(MailTemplateKey.PASSWORD_RESET), any(), any(), any()))
        .thenReturn(preview);

    mockMvc
        .perform(
            post("/api/v1/admin/email/templates/auth.password_reset/preview")
                .contentType("application/json")
                .content(
                    "{\"subject\":\"New {{siteName}}\",\"bodyPlain\":\"Body\",\"bodyHtml\":\"<p>x</p>\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("Draft subject"));

    // The unsaved editor content is forwarded as a draft to render in place of the stored version.
    verify(templates)
        .preview(
            eq(MailTemplateKey.PASSWORD_RESET),
            any(),
            any(),
            eq(new MailTemplateService.MailTemplateDraft("New {{siteName}}", "Body", "<p>x</p>")));
  }

  @Test
  void updateWithUnknownPlaceholderReturns400() throws Exception {
    when(templates.update(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new MailTemplateValidationException(
                List.of("unknownThing"), List.of("siteName", "actionUrl")));

    mockMvc
        .perform(
            put("/api/v1/admin/email/templates/auth.password_reset")
                .contentType("application/json")
                .content(
                    "{\"locale\":\"en\",\"subject\":\"s\",\"bodyPlain\":\"{{unknownThing}}\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownTemplateKeyReturns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/email/templates/does.not.exist"))
        .andExpect(status().isNotFound());
  }
}

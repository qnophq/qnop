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

import io.qnop.api.v1.endpoint.AdminEmailApi;
import io.qnop.api.v1.model.MailTemplateListResponse;
import io.qnop.api.v1.model.MailTemplatePreviewResponse;
import io.qnop.api.v1.model.MailTemplateResponse;
import io.qnop.api.v1.model.MailTemplateSource;
import io.qnop.api.v1.model.PreviewMailTemplateRequest;
import io.qnop.api.v1.model.SendStatus;
import io.qnop.api.v1.model.SendTestEmailRequest;
import io.qnop.api.v1.model.SendTestEmailResponse;
import io.qnop.api.v1.model.UpdateMailTemplateRequest;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailService.SendResult;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.MailTemplateService;
import io.qnop.service.mail.MailTemplateService.MailPreview;
import io.qnop.service.mail.MailTemplateValidationException;
import io.qnop.service.mail.MailTemplateView;
import io.qnop.service.mail.RenderedMail;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin mail administration endpoints ({@code /api/v1/admin/email/**}), implementing the generated
 * {@link AdminEmailApi} contract (issue #19). Authorization is enforced by the security chain
 * ({@code /api/v1/admin/**} requires {@code ADMIN}, issue #98); these handlers assume an
 * authenticated admin. Template render failures map to 400 and unknown keys to 404; a failed SMTP
 * delivery is reported in the body (never a 5xx) because {@link MailService} never throws.
 */
@RestController
public class AdminEmailController implements AdminEmailApi {

  private static final String TEST_SUBJECT = "qnop test email";
  private static final String TEST_BODY =
      "This is a test email from qnop. If you received it, your SMTP settings are working.";

  private final MailService mailService;
  private final MailTemplateService templates;

  public AdminEmailController(MailService mailService, MailTemplateService templates) {
    this.mailService = mailService;
    this.templates = templates;
  }

  @Override
  public ResponseEntity<SendTestEmailResponse> sendTestEmail(SendTestEmailRequest request) {
    SendResult result = mailService.sendMail(request.getRecipient(), TEST_SUBJECT, TEST_BODY, null);
    SendTestEmailResponse body =
        switch (result) {
          case SendResult.Sent sent -> response(SendStatus.SENT, "Sent to " + sent.recipient());
          case SendResult.Skipped skipped -> response(SendStatus.SKIPPED, skipped.reason());
          case SendResult.Failed failed -> response(SendStatus.FAILED, failed.reason());
        };
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<MailTemplateListResponse> listMailTemplates() {
    List<MailTemplateResponse> all = templates.listAll().stream().map(this::toResponse).toList();
    return ResponseEntity.ok(new MailTemplateListResponse().templates(all));
  }

  @Override
  public ResponseEntity<MailTemplateResponse> getMailTemplate(String key) {
    return ResponseEntity.ok(toResponse(templates.getEffective(resolveKey(key))));
  }

  @Override
  public ResponseEntity<MailTemplateResponse> updateMailTemplate(
      String key, UpdateMailTemplateRequest request) {
    MailTemplateKey templateKey = resolveKey(key);
    try {
      MailTemplateView saved =
          templates.update(
              templateKey,
              request.getLocale(),
              request.getSubject(),
              request.getBodyPlain(),
              request.getBodyHtml(),
              currentActor());
      return ResponseEntity.ok(toResponse(saved));
    } catch (MailTemplateValidationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @Override
  public ResponseEntity<Void> resetMailTemplate(String key) {
    templates.reset(resolveKey(key));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<MailTemplatePreviewResponse> previewMailTemplate(
      String key, PreviewMailTemplateRequest request) {
    MailTemplateKey templateKey = resolveKey(key);
    MailPreview preview;
    try {
      preview = templates.preview(templateKey, request.getLocale(), toVars(request.getVariables()));
    } catch (MailTemplateValidationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Template render failed: " + e.getMessage());
    }
    RenderedMail rendered = preview.rendered();
    return ResponseEntity.ok(
        new MailTemplatePreviewResponse()
            .subject(rendered.subject())
            .bodyPlain(rendered.bodyPlain())
            .bodyHtml(rendered.bodyHtml())
            .sampleVars(preview.sampleVars()));
  }

  private MailTemplateKey resolveKey(String key) {
    return MailTemplateKey.fromKey(key)
        .orElseThrow(
            () ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown mail template: " + key));
  }

  private SendTestEmailResponse response(SendStatus status, String detail) {
    return new SendTestEmailResponse().status(status).detail(detail);
  }

  private Map<String, Object> toVars(Map<String, String> variables) {
    return variables == null ? Map.of() : new HashMap<>(variables);
  }

  /** The authenticated admin's id (the JWT subject), or null if not parseable. */
  private UUID currentActor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      return null;
    }
    try {
      return UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private MailTemplateResponse toResponse(MailTemplateView view) {
    return new MailTemplateResponse()
        .key(view.key())
        .friendlyName(view.friendlyName())
        .locale(view.locale())
        .subject(view.subject())
        .bodyPlain(view.bodyPlain())
        .bodyHtml(view.bodyHtml())
        .source(MailTemplateSource.valueOf(view.source().name()))
        .placeholders(view.placeholders())
        .defaultSubject(view.defaultSubject())
        .defaultBodyPlain(view.defaultBodyPlain())
        .defaultBodyHtml(view.defaultBodyHtml())
        .updatedAt(view.updatedAt() == null ? null : view.updatedAt().atOffset(ZoneOffset.UTC))
        .updatedBy(view.updatedBy());
  }
}

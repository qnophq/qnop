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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One per-locale email template variant (issue #14). {@code templateKey} is a dotted identifier
 * (e.g. {@code auth.password_reset}); {@code locale} is a BCP-47 tag, so {@code (templateKey,
 * locale)} is unique and new languages are added purely as rows. {@code bodyPlain} is always
 * present; {@code bodyHtml} is the optional alternative the mail layer sends as
 * multipart/alternative. Subject and bodies carry Mustache placeholders rendered at send time
 * (issue #19). There is intentionally no {@code created_at}.
 */
@Entity
@Table(name = "mail_template")
public class MailTemplate {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "template_key", nullable = false, length = 128, updatable = false)
  private String templateKey;

  @Column(name = "locale", nullable = false, length = 16, updatable = false)
  private String locale;

  @Column(name = "subject", nullable = false, columnDefinition = "text")
  private String subject;

  @Column(name = "body_plain", nullable = false, columnDefinition = "text")
  private String bodyPlain;

  @Column(name = "body_html", columnDefinition = "text")
  private String bodyHtml;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 255)
  private String updatedBy;

  protected MailTemplate() {
    // for JPA
  }

  public MailTemplate(String templateKey, String locale, String subject, String bodyPlain) {
    this.templateKey = templateKey;
    this.locale = locale;
    this.subject = subject;
    this.bodyPlain = bodyPlain;
  }

  public UUID getId() {
    return id;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getLocale() {
    return locale;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getBodyPlain() {
    return bodyPlain;
  }

  public void setBodyPlain(String bodyPlain) {
    this.bodyPlain = bodyPlain;
  }

  public String getBodyHtml() {
    return bodyHtml;
  }

  public void setBodyHtml(String bodyHtml) {
    this.bodyHtml = bodyHtml;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MailTemplate other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}

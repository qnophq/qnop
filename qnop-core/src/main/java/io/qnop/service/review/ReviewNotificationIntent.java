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
package io.qnop.service.review;

import io.qnop.entity.NotificationType;
import io.qnop.entity.User;
import io.qnop.service.mail.MailTemplateKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One thing worth telling one person (issue #538, ADR-0051) — the unit {@link
 * ReviewNotificationService} resolves a committed event into, and every {@link
 * ReviewNotificationSink} consumes.
 *
 * <p>Resolution may offer <em>several</em> intents for the same recipient, ordered by {@link
 * NotificationType}'s declaration rank: being mentioned in a reply outranks the reply itself. Each
 * sink then delivers the first candidate it accepts, which is what lets the mail sink fall through
 * to the reply mail for someone who muted mentions while the inbox still records the mention.
 *
 * <p>It carries both channels' needs side by side: {@code mailVars} is the already-rendered
 * template context (including the actor's name as <em>this</em> recipient may see it), while the
 * bare ids and the non-identity snapshot feed the persisted row, which resolves names on read.
 */
public record ReviewNotificationIntent(
    User recipient,
    NotificationType type,
    MailTemplateKey template,
    Map<String, Object> mailVars,
    UUID documentId,
    UUID actorId,
    UUID annotationId,
    UUID commentId,
    String excerpt,
    String decision,
    Integer versionNumber,
    String fromState,
    String toState) {

  public static Builder to(User recipient, NotificationType type, MailTemplateKey template) {
    return new Builder(recipient, type, template);
  }

  /** Assembles an intent; only the recipient, type and template are mandatory. */
  public static final class Builder {
    private final User recipient;
    private final NotificationType type;
    private final MailTemplateKey template;
    private final Map<String, Object> mailVars = new LinkedHashMap<>();
    private UUID documentId;
    private UUID actorId;
    private UUID annotationId;
    private UUID commentId;
    private String excerpt;
    private String decision;
    private Integer versionNumber;
    private String fromState;
    private String toState;

    private Builder(User recipient, NotificationType type, MailTemplateKey template) {
      this.recipient = recipient;
      this.type = type;
      this.template = template;
    }

    public Builder vars(Map<String, Object> vars) {
      this.mailVars.putAll(vars);
      return this;
    }

    public Builder var(String name, Object value) {
      this.mailVars.put(name, value);
      return this;
    }

    public Builder document(UUID documentId) {
      this.documentId = documentId;
      return this;
    }

    public Builder actor(UUID actorId) {
      this.actorId = actorId;
      return this;
    }

    public Builder annotation(UUID annotationId) {
      this.annotationId = annotationId;
      return this;
    }

    public Builder comment(UUID commentId) {
      this.commentId = commentId;
      return this;
    }

    public Builder excerpt(String excerpt) {
      this.excerpt = excerpt;
      return this;
    }

    public Builder decision(String decision) {
      this.decision = decision;
      return this;
    }

    public Builder versionNumber(Integer versionNumber) {
      this.versionNumber = versionNumber;
      return this;
    }

    public Builder transition(String fromState, String toState) {
      this.fromState = fromState;
      this.toState = toState;
      return this;
    }

    public ReviewNotificationIntent build() {
      return new ReviewNotificationIntent(
          recipient,
          type,
          template,
          // Not Map.copyOf: it rejects null values, and an unconfigured setting
          // (a blank site name, say) must degrade to an empty placeholder in one
          // mail — never take down the whole fan-out for that event.
          Collections.unmodifiableMap(new LinkedHashMap<>(mailVars)),
          documentId,
          actorId,
          annotationId,
          commentId,
          excerpt,
          decision,
          versionNumber,
          fromState,
          toState);
    }
  }
}

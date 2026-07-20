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
package io.qnop.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.AuditEvent;
import io.qnop.entity.Document;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.UserDisplayName;
import io.qnop.repository.UserRepository;
import io.qnop.service.audit.AuditLogService.AuditEventView;
import io.qnop.service.audit.AuditLogService.AuditPage;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * The audit-list mapping and paging rules (issue #466, ADR-0042): real actor/document resolution,
 * the system-actor label, unresolved-entity nulls, and the defensive page/size clamping — all in
 * isolation, with the three repositories mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

  @Mock private AuditEventRepository auditEvents;
  @Mock private UserRepository users;
  @Mock private DocumentRepository documents;

  private AuditLogService service;

  private final UUID actorId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new AuditLogService(auditEvents, users, documents);
  }

  @Test
  @DisplayName("resolves the real actor name, slug and document title/slug, and passes the detail")
  void mapsActorAndDocument() {
    // Exercises the pure mapping directly: a Document's id is DB-generated (null off-persistence),
    // so the id→document map is built explicitly rather than through findAllById here.
    AuditEvent event =
        new AuditEvent(documentId, "workflow.transition", actorId, "{\"to\":\"IN_REVIEW\"}");
    Document doc = new Document(UUID.randomUUID(), "Master services agreement");
    doc.setSlug("msa");

    List<AuditEventView> views =
        AuditLogService.toViews(
            List.of(event),
            Map.of(actorId, "Avery Auditor"),
            Map.of(actorId, "avery-auditor"),
            Map.of(documentId, doc));

    assertThat(views).hasSize(1);
    AuditEventView view = views.get(0);
    assertThat(view.eventType()).isEqualTo("workflow.transition");
    assertThat(view.documentId()).isEqualTo(documentId);
    assertThat(view.documentTitle()).isEqualTo("Master services agreement");
    assertThat(view.documentSlug()).isEqualTo("msa");
    assertThat(view.actorId()).isEqualTo(actorId);
    assertThat(view.actorDisplayName()).isEqualTo("Avery Auditor");
    assertThat(view.actorSlug()).isEqualTo("avery-auditor");
    assertThat(view.detail()).isEqualTo("{\"to\":\"IN_REVIEW\"}");
    assertThat(view.scope()).isEqualTo("DOCUMENT");
  }

  @Test
  @DisplayName("a SYSTEM-scoped event carries no document and is never looked up as one")
  void systemScopedEventHasNoDocument() {
    // A scheduler toggle: SYSTEM scope, an acting admin, no document (issue #524, ADR-0043).
    AuditEvent systemEvent =
        AuditEvent.system("scheduler.job.enabled", actorId, "{\"job\":\"refreshTokenSweep\"}");
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(systemEvent), PageRequest.of(0, 20), 1));
    when(users.findDisplayNamesByIdIn(List.of(actorId)))
        .thenReturn(List.of(new UserDisplayName(actorId, "Adele Admin")));
    when(users.findSlugsByIdIn(List.of(actorId))).thenReturn(List.of());

    AuditEventView view =
        service.list(null, null, null, null, null, null, null, null).items().get(0);

    assertThat(view.scope()).isEqualTo("SYSTEM");
    assertThat(view.documentId()).isNull();
    assertThat(view.documentTitle()).isNull();
    assertThat(view.documentSlug()).isNull();
    assertThat(view.actorDisplayName()).isEqualTo("Adele Admin");
    // A null document id must never reach the document lookup.
    verify(documents, never()).findAllById(any());
  }

  @Test
  @DisplayName("a null actor renders as System and is not looked up")
  void systemActorRendersAsSystem() {
    AuditEvent systemEvent = new AuditEvent(documentId, "document.extraction.failed", null, null);
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(systemEvent), PageRequest.of(0, 20), 1));
    when(documents.findAllById(List.of(documentId))).thenReturn(List.of());

    AuditEventView view =
        service.list(null, null, null, null, null, null, null, null).items().get(0);

    assertThat(view.actorId()).isNull();
    assertThat(view.actorDisplayName()).isEqualTo("System");
    // No non-null actor ids → the user lookup is skipped entirely.
    verify(users, never()).findDisplayNamesByIdIn(any());
  }

  @Test
  @DisplayName("an unresolved actor or document yields null names, never a raw id")
  void unresolvedEntitiesYieldNulls() {
    AuditEvent event = new AuditEvent(documentId, "annotation.created", actorId, null);
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));
    when(users.findDisplayNamesByIdIn(List.of(actorId))).thenReturn(List.of());
    when(users.findSlugsByIdIn(List.of(actorId))).thenReturn(List.of());
    when(documents.findAllById(List.of(documentId))).thenReturn(List.of());

    AuditEventView view =
        service.list(null, null, null, null, null, null, null, null).items().get(0);

    assertThat(view.actorId()).isEqualTo(actorId);
    assertThat(view.actorDisplayName()).isNull();
    assertThat(view.actorSlug()).isNull();
    assertThat(view.documentTitle()).isNull();
    assertThat(view.documentSlug()).isNull();
  }

  @Test
  @DisplayName("clamps size to [1, MAX] and page to >= 0, sorted by createdAt DESC")
  void clampsPagingAndSorts() {
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

    AuditPage page = service.list(null, null, null, null, null, null, -5, 500);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(auditEvents).findAll(any(Specification.class), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isZero();
    assertThat(pageable.getValue().getPageSize()).isEqualTo(AuditLogService.MAX_PAGE_SIZE);
    assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
        .isEqualTo(Sort.Direction.DESC);
    // The echoed page/size reflect the clamped values, not the raw request.
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(AuditLogService.MAX_PAGE_SIZE);
  }

  @Test
  @DisplayName("a null size falls back to the default page size")
  void nullSizeUsesDefault() {
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    AuditPage page = service.list(null, null, null, null, null, null, 0, null);

    assertThat(page.size()).isEqualTo(AuditLogService.DEFAULT_PAGE_SIZE);
  }

  @Test
  @DisplayName("the filter spec adds one predicate per non-null argument")
  void filterAddsOnePredicatePerArgument() {
    @SuppressWarnings("unchecked")
    Root<AuditEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);

    AuditLogService.filter(
            "annotation.resolved",
            actorId,
            false,
            documentId,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-12-31T23:59:59Z"))
        .toPredicate(root, query, cb);

    // any(Object.class) pins the equal(Expression, Object) overload the builder actually calls.
    verify(cb, times(3)).equal(any(), any(Object.class)); // eventType, actorId, documentId
    verify(cb).greaterThanOrEqualTo(any(), any(Instant.class)); // from
    verify(cb).lessThanOrEqualTo(any(), any(Instant.class)); // to
    verify(cb).and(any(Predicate[].class));
  }

  @Test
  @DisplayName("actorSystem restricts to events with no actor and ignores actorId")
  void actorSystemFiltersToNullActor() {
    @SuppressWarnings("unchecked")
    Root<AuditEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);

    AuditLogService.filter(null, actorId, true, null, null, null).toPredicate(root, query, cb);

    verify(cb).isNull(any());
    // actorSystem takes precedence — the actorId equality is never added.
    verify(cb, never()).equal(any(), any(Object.class));
    verify(cb).and(any(Predicate[].class));
  }

  @Test
  @DisplayName("an all-empty filter restricts nothing (unrestricted conjunction)")
  void emptyFilterRestrictsNothing() {
    @SuppressWarnings("unchecked")
    Root<AuditEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);

    AuditLogService.filter(null, null, false, null, null, null).toPredicate(root, query, cb);

    verify(cb, never()).equal(any(), any(Object.class));
    verify(cb).and(any(Predicate[].class));
  }

  @Test
  @DisplayName("an empty page does no enrichment lookups")
  void emptyPageSkipsLookups() {
    when(auditEvents.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    AuditPage page = service.list(null, null, null, null, null, null, 0, 20);

    assertThat(page.items()).isEmpty();
    verifyNoInteractions(users, documents);
  }
}

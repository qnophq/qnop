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

import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.WorkflowState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Community review-workflow state machine (issue #246, ADR-0011): an explicit transition table
 * plus guard functions, applied through the single {@link #transition} choke-point. Deliberately
 * plain, DB-free code (the ADR-0004 guardrail) — callers supply the counts a guard needs via {@link
 * TransitionContext}; persistence, authorization and audit live in {@code ReviewWorkflowService}.
 *
 * <pre>
 * DRAFT → IN_REVIEW ⇄ CHANGES_REQUESTED → FINALIZED
 *   ↘         ↘            ↙
 *              CANCELLED           (FINALIZED and CANCELLED are terminal)
 * </pre>
 *
 * <p><strong>Unknown states.</strong> {@code document.workflow_state} is persisted as an open
 * string so an enterprise edition can add states (ADR-0011 amendment). This Community machine
 * manages only the five states it knows: from an unknown current state it denies every transition
 * and offers none — an enterprise state machine owns those documents.
 */
public class ReviewWorkflowMachine {

  /** The structural transition table: which target states each known state may move to. */
  private static final Map<WorkflowState, Set<WorkflowState>> EDGES = buildEdges();

  /**
   * The transitions the owner may request explicitly. The {@code IN_REVIEW ⇄ CHANGES_REQUESTED}
   * pair is missing on purpose: it is derived from the open-annotation count (issue #405, {@link
   * #annotationDrivenTarget}) and never crossed by hand.
   */
  private static final Map<WorkflowState, Set<WorkflowState>> MANUAL_EDGES = buildManualEdges();

  private static Map<WorkflowState, Set<WorkflowState>> buildEdges() {
    Map<WorkflowState, Set<WorkflowState>> edges = new EnumMap<>(WorkflowState.class);
    edges.put(WorkflowState.DRAFT, EnumSet.of(WorkflowState.IN_REVIEW, WorkflowState.CANCELLED));
    edges.put(
        WorkflowState.IN_REVIEW,
        EnumSet.of(
            WorkflowState.CHANGES_REQUESTED, WorkflowState.FINALIZED, WorkflowState.CANCELLED));
    edges.put(
        WorkflowState.CHANGES_REQUESTED,
        EnumSet.of(WorkflowState.IN_REVIEW, WorkflowState.CANCELLED));
    edges.put(WorkflowState.FINALIZED, EnumSet.noneOf(WorkflowState.class));
    edges.put(WorkflowState.CANCELLED, EnumSet.noneOf(WorkflowState.class));
    return edges;
  }

  private static Map<WorkflowState, Set<WorkflowState>> buildManualEdges() {
    Map<WorkflowState, Set<WorkflowState>> edges = new EnumMap<>(WorkflowState.class);
    edges.put(WorkflowState.DRAFT, EnumSet.of(WorkflowState.IN_REVIEW, WorkflowState.CANCELLED));
    edges.put(
        WorkflowState.IN_REVIEW, EnumSet.of(WorkflowState.FINALIZED, WorkflowState.CANCELLED));
    edges.put(WorkflowState.CHANGES_REQUESTED, EnumSet.of(WorkflowState.CANCELLED));
    edges.put(WorkflowState.FINALIZED, EnumSet.noneOf(WorkflowState.class));
    edges.put(WorkflowState.CANCELLED, EnumSet.noneOf(WorkflowState.class));
    return edges;
  }

  /**
   * The document-derived facts a guard may need. Callers (the service) load these; the machine
   * itself never touches the database.
   *
   * @param openAnnotations annotations on the document still in {@code OPEN}
   * @param pendingPlacements annotation placements on any version still {@code PENDING}
   *     (re-anchoring not yet complete, ADR-0009/0033)
   * @param hasReadyVersion whether at least one version has a completed (READY) extraction — a
   *     review with no reviewable representation cannot be finalized (issue #323)
   */
  public record TransitionContext(
      long openAnnotations, long pendingPlacements, boolean hasReadyVersion) {}

  /** Outcome of a {@link #transition} request. */
  public sealed interface TransitionResult {

    /** The transition is legal and all guards passed; the caller may persist {@code target}. */
    record Allowed(WorkflowState target) implements TransitionResult {}

    /** The transition is refused; {@code reason} is safe to surface to the API caller. */
    record Denied(String reason) implements TransitionResult {}
  }

  /** A guard function vetoing a structurally legal transition; empty means "no veto". */
  @FunctionalInterface
  interface TransitionGuard {
    Optional<String> veto(TransitionContext context);
  }

  /** Guards keyed by target state (ADR-0011: the FINALIZED invariant is a domain guard). */
  private static final Map<WorkflowState, TransitionGuard> GUARDS =
      Map.of(WorkflowState.FINALIZED, ReviewWorkflowMachine::finalizeGuard);

  private static Optional<String> finalizeGuard(TransitionContext context) {
    if (context.openAnnotations() > 0) {
      return Optional.of(
          "cannot finalize: "
              + context.openAnnotations()
              + " open annotation(s) must be resolved first");
    }
    if (context.pendingPlacements() > 0) {
      return Optional.of(
          "cannot finalize: "
              + context.pendingPlacements()
              + " pending placement(s) — re-anchoring has not completed");
    }
    if (!context.hasReadyVersion()) {
      // A review whose only version(s) failed extraction has nothing reviewable to finalize
      // (issue #323); PENDING versions are already covered by the pending-placement guard.
      return Optional.of("cannot finalize: no version has a completed (READY) extraction");
    }
    return Optional.empty();
  }

  /**
   * The single choke-point: decides whether {@code currentRaw → target} is permitted.
   *
   * @param currentRaw the persisted state string (may be an enterprise value this edition does not
   *     know)
   * @param target the requested Community target state
   * @param context document-derived facts for the guards
   */
  public TransitionResult transition(
      String currentRaw, WorkflowState target, TransitionContext context) {
    Optional<WorkflowState> current = WorkflowState.fromString(currentRaw);
    if (current.isEmpty()) {
      return new TransitionResult.Denied(
          "state '"
              + currentRaw
              + "' is not managed by the Community workflow (enterprise-extended state machine)");
    }
    if (!EDGES.get(current.get()).contains(target)) {
      return new TransitionResult.Denied("no transition from " + current.get() + " to " + target);
    }
    Optional<String> veto = GUARDS.getOrDefault(target, ignored -> Optional.empty()).veto(context);
    return veto.<TransitionResult>map(TransitionResult.Denied::new)
        .orElseGet(() -> new TransitionResult.Allowed(target));
  }

  /**
   * Like {@link #transition}, but for an owner-requested move (issue #405): a structurally legal
   * edge that is not in the manual table — the derived {@code IN_REVIEW ⇄ CHANGES_REQUESTED} pair —
   * is refused before guards run.
   */
  public TransitionResult manualTransition(
      String currentRaw, WorkflowState target, TransitionContext context) {
    boolean derivedEdge =
        WorkflowState.fromString(currentRaw)
            .map(
                state ->
                    EDGES.get(state).contains(target) && !MANUAL_EDGES.get(state).contains(target))
            .orElse(false);
    if (derivedEdge) {
      return new TransitionResult.Denied(
          "the state between IN_REVIEW and CHANGES_REQUESTED is derived from the open-annotation"
              + " count and cannot be set by hand");
    }
    return transition(currentRaw, target, context);
  }

  /**
   * The target states the owner may request explicitly from {@code currentRaw} — the manual
   * transition table only, guards not applied (a guard's verdict can change at any moment; the
   * authoritative check is the {@link #transition} call itself). Empty for terminal or unknown
   * states.
   */
  public List<WorkflowState> allowedTransitions(String currentRaw) {
    return WorkflowState.fromString(currentRaw)
        .map(state -> List.copyOf(MANUAL_EDGES.get(state)))
        .orElse(List.of());
  }

  /** One manual target with its read-time guard verdict — empty reason means "available". */
  public record TransitionOption(WorkflowState target, Optional<String> blockedReason) {}

  /**
   * The manual targets from {@code currentRaw} with the guards pre-evaluated against {@code
   * context} (issue #568), so a client can explain a blocked option instead of silently omitting
   * it. Advisory only — the verdict can change at any moment; {@link #manualTransition} re-checks
   * authoritatively. Empty for terminal or unknown states.
   */
  public List<TransitionOption> transitionOptions(String currentRaw, TransitionContext context) {
    return WorkflowState.fromString(currentRaw)
        .map(
            state ->
                MANUAL_EDGES.get(state).stream()
                    .map(
                        target ->
                            new TransitionOption(
                                target,
                                Optional.ofNullable(GUARDS.get(target))
                                    .flatMap(guard -> guard.veto(context))))
                    .toList())
        .orElse(List.of());
  }

  // --- annotation sub-machine (ADR-0011, amended by #405) ----------------------

  /** Whether an annotation in {@code current} may still be resolved: only {@code OPEN} may. */
  public static boolean canResolve(AnnotationStatus current) {
    return current == AnnotationStatus.OPEN;
  }

  /**
   * The workflow transition the open-annotation count drives, if any (issue #405): the {@code
   * IN_REVIEW ⇄ CHANGES_REQUESTED} pair is derived — an open concern moves the review to {@code
   * CHANGES_REQUESTED}, settling the last one returns it to {@code IN_REVIEW}. No owner action
   * involved; every other state drives nothing.
   */
  public static Optional<WorkflowState> annotationDrivenTarget(
      String currentRaw, long openAnnotations) {
    return WorkflowState.fromString(currentRaw)
        .flatMap(
            state ->
                switch (state) {
                  case IN_REVIEW ->
                      openAnnotations > 0
                          ? Optional.of(WorkflowState.CHANGES_REQUESTED)
                          : Optional.empty();
                  case CHANGES_REQUESTED ->
                      openAnnotations == 0
                          ? Optional.of(WorkflowState.IN_REVIEW)
                          : Optional.empty();
                  default -> Optional.empty();
                });
  }
}

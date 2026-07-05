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

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.WorkflowState;
import io.qnop.service.review.ReviewWorkflowMachine.TransitionContext;
import io.qnop.service.review.ReviewWorkflowMachine.TransitionResult;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exhaustive, DB-free tests for the Community workflow state machine (issue #246, ADR-0011 as
 * amended by #405): the full transition matrix, the manual/derived edge split, the FINALIZED guard
 * invariant, unknown (enterprise) state handling, and the annotation sub-machine.
 */
class ReviewWorkflowMachineTest {

  private static final TransitionContext CLEAN = new TransitionContext(0, 0, true);

  private final ReviewWorkflowMachine machine = new ReviewWorkflowMachine();

  // --- transition matrix ------------------------------------------------------

  @ParameterizedTest(name = "{0} -> {1} is allowed")
  @CsvSource({
    "DRAFT,IN_REVIEW",
    "DRAFT,CANCELLED",
    "IN_REVIEW,CHANGES_REQUESTED",
    "IN_REVIEW,FINALIZED",
    "IN_REVIEW,CANCELLED",
    "CHANGES_REQUESTED,IN_REVIEW",
    "CHANGES_REQUESTED,CANCELLED",
  })
  void allowsLegalEdges(WorkflowState from, WorkflowState to) {
    assertThat(machine.transition(from.name(), to, CLEAN))
        .isInstanceOf(TransitionResult.Allowed.class);
  }

  @Test
  @DisplayName("every state pair outside the transition table is denied (exhaustive)")
  void deniesEveryEdgeOutsideTheTable() {
    Set<String> legal =
        Set.of(
            "DRAFT>IN_REVIEW",
            "DRAFT>CANCELLED",
            "IN_REVIEW>CHANGES_REQUESTED",
            "IN_REVIEW>FINALIZED",
            "IN_REVIEW>CANCELLED",
            "CHANGES_REQUESTED>IN_REVIEW",
            "CHANGES_REQUESTED>CANCELLED");
    for (WorkflowState from : WorkflowState.values()) {
      for (WorkflowState to : WorkflowState.values()) {
        boolean expected = legal.contains(from.name() + ">" + to.name());
        TransitionResult result = machine.transition(from.name(), to, CLEAN);
        assertThat(result instanceof TransitionResult.Allowed)
            .as("%s -> %s", from, to)
            .isEqualTo(expected);
      }
    }
  }

  // --- manual vs. derived edges (issue #405) -----------------------------------

  @ParameterizedTest(name = "manual {0} -> {1} is refused as derived")
  @CsvSource({
    "IN_REVIEW,CHANGES_REQUESTED",
    "CHANGES_REQUESTED,IN_REVIEW",
  })
  void refusesManualCrossingsOfTheDerivedPair(WorkflowState from, WorkflowState to) {
    TransitionResult result = machine.manualTransition(from.name(), to, CLEAN);

    assertThat(result).isInstanceOf(TransitionResult.Denied.class);
    assertThat(((TransitionResult.Denied) result).reason()).contains("derived");
  }

  @Test
  void manualTransitionStillAllowsTheOwnerEdges() {
    assertThat(machine.manualTransition("DRAFT", WorkflowState.IN_REVIEW, CLEAN))
        .isInstanceOf(TransitionResult.Allowed.class);
    assertThat(machine.manualTransition("IN_REVIEW", WorkflowState.FINALIZED, CLEAN))
        .isInstanceOf(TransitionResult.Allowed.class);
    assertThat(machine.manualTransition("CHANGES_REQUESTED", WorkflowState.CANCELLED, CLEAN))
        .isInstanceOf(TransitionResult.Allowed.class);
    // A non-edge falls through to the structural denial, not the derived one.
    TransitionResult result = machine.manualTransition("DRAFT", WorkflowState.FINALIZED, CLEAN);
    assertThat(result).isInstanceOf(TransitionResult.Denied.class);
    assertThat(((TransitionResult.Denied) result).reason()).contains("no transition");
  }

  @Test
  void finalizedAndCancelledAreTerminal() {
    for (WorkflowState terminal : EnumSet.of(WorkflowState.FINALIZED, WorkflowState.CANCELLED)) {
      assertThat(machine.allowedTransitions(terminal.name())).isEmpty();
    }
  }

  @Test
  void selfTransitionsAreDenied() {
    for (WorkflowState state : WorkflowState.values()) {
      assertThat(machine.transition(state.name(), state, CLEAN))
          .as("%s -> %s", state, state)
          .isNotInstanceOf(TransitionResult.Allowed.class);
    }
  }

  // --- FINALIZED guard invariant ----------------------------------------------

  @ParameterizedTest(name = "finalize with {0} open / {1} pending -> allowed={2}")
  @CsvSource({
    "0,0,true",
    "1,0,false",
    "0,1,false",
    "3,2,false",
  })
  void finalizeRequiresZeroOpenAnnotationsAndZeroPendingPlacements(
      long open, long pending, boolean allowed) {
    TransitionResult result =
        machine.transition(
            WorkflowState.IN_REVIEW.name(),
            WorkflowState.FINALIZED,
            new TransitionContext(open, pending, true));

    assertThat(result instanceof TransitionResult.Allowed).isEqualTo(allowed);
    if (!allowed) {
      assertThat(((TransitionResult.Denied) result).reason())
          .containsAnyOf("open annotation", "pending placement");
    }
  }

  @Test
  @DisplayName("finalize is denied when no version has a completed extraction (issue #323)")
  void finalizeRequiresAReadyVersion() {
    TransitionResult result =
        machine.transition(
            WorkflowState.IN_REVIEW.name(),
            WorkflowState.FINALIZED,
            new TransitionContext(0, 0, false));

    assertThat(result).isInstanceOf(TransitionResult.Denied.class);
    assertThat(((TransitionResult.Denied) result).reason()).contains("READY");
  }

  @Test
  void guardDoesNotBlockNonFinalizeEdges() {
    // Open annotations must not prevent e.g. cancelling or requesting changes.
    TransitionContext dirty = new TransitionContext(5, 5, true);
    assertThat(machine.transition("IN_REVIEW", WorkflowState.CHANGES_REQUESTED, dirty))
        .isInstanceOf(TransitionResult.Allowed.class);
    assertThat(machine.transition("IN_REVIEW", WorkflowState.CANCELLED, dirty))
        .isInstanceOf(TransitionResult.Allowed.class);
  }

  // --- unknown (enterprise) states ----------------------------------------------

  @Test
  void deniesTransitionsFromAStateUnknownToThisEdition() {
    TransitionResult result = machine.transition("SIGNING", WorkflowState.FINALIZED, CLEAN);

    assertThat(result).isInstanceOf(TransitionResult.Denied.class);
    assertThat(((TransitionResult.Denied) result).reason()).contains("SIGNING");
  }

  @Test
  void reportsNoAllowedTransitionsForAnUnknownState() {
    assertThat(machine.allowedTransitions("SIGNING")).isEmpty();
  }

  // --- allowedTransitions -------------------------------------------------------

  @Test
  @DisplayName("allowedTransitions offers the manual table only — the derived pair is absent")
  void allowedTransitionsReflectTheManualTable() {
    assertThat(machine.allowedTransitions("DRAFT"))
        .containsExactlyInAnyOrder(WorkflowState.IN_REVIEW, WorkflowState.CANCELLED);
    assertThat(machine.allowedTransitions("IN_REVIEW"))
        .containsExactlyInAnyOrder(WorkflowState.FINALIZED, WorkflowState.CANCELLED);
    assertThat(machine.allowedTransitions("CHANGES_REQUESTED"))
        .containsExactlyInAnyOrder(WorkflowState.CANCELLED);
  }

  // --- annotation sub-machine -----------------------------------------------------

  @Test
  void onlyOpenAnnotationsCanBeResolved() {
    assertThat(ReviewWorkflowMachine.canResolve(AnnotationStatus.OPEN)).isTrue();
    assertThat(ReviewWorkflowMachine.canResolve(AnnotationStatus.RESOLVED)).isFalse();
  }

  @Test
  @DisplayName("the first open annotation in IN_REVIEW derives CHANGES_REQUESTED (issue #405)")
  void openAnnotationsDeriveChangesRequested() {
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("IN_REVIEW", 1))
        .contains(WorkflowState.CHANGES_REQUESTED);
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("IN_REVIEW", 3))
        .contains(WorkflowState.CHANGES_REQUESTED);
  }

  @Test
  @DisplayName("settling the last open annotation derives the return to IN_REVIEW (issue #405)")
  void zeroOpenAnnotationsDeriveTheReturnToInReview() {
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("CHANGES_REQUESTED", 0))
        .contains(WorkflowState.IN_REVIEW);
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("CHANGES_REQUESTED", 2)).isEmpty();
  }

  @Test
  void statesOutsideTheDerivedPairAreNeverDriven() {
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("IN_REVIEW", 0)).isEmpty();
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("DRAFT", 4)).isEmpty();
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("FINALIZED", 1)).isEmpty();
    assertThat(ReviewWorkflowMachine.annotationDrivenTarget("SIGNING", 1)).isEmpty();
  }
}

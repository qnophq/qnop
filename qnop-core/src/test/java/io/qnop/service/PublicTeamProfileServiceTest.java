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
package io.qnop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Team;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The public team profile's visibility matrix (issue #586). */
@ExtendWith(MockitoExtension.class)
class PublicTeamProfileServiceTest {

  @Mock private TeamRepository teams;
  @Mock private TeamMembershipRepository memberships;
  @Mock private DocumentRepository documents;
  @Mock private DocumentVersionRepository versions;

  private PublicTeamProfileService service;
  private final UUID teamId = UUID.randomUUID();
  private final UUID caller = UUID.randomUUID();
  private Team team;

  @BeforeEach
  void setUp() {
    service = new PublicTeamProfileService(teams, memberships, documents, versions);
    team = teamWithId(teamId, "Alpha", "Primary review team");
    when(teams.findById(teamId)).thenReturn(Optional.of(team));
  }

  /** A team carrying its (normally persistence-generated) id, as in {@code TeamServiceTest}. */
  private static Team teamWithId(UUID id, String name, String description) {
    Team team = Team.create(name, description);
    try {
      var field = Team.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(team, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return team;
  }

  @Test
  @DisplayName("a non-member with conservative defaults sees neither roster nor reviews")
  void hiddenSectionsAreNullForNonMembers() {
    when(memberships.existsByTeamIdAndUserId(teamId, caller)).thenReturn(false);

    var profile = service.getProfile(teamId, caller, false);

    assertThat(profile.name()).isEqualTo("Alpha");
    assertThat(profile.description()).isEqualTo("Primary review team");
    assertThat(profile.viewerIsMember()).isFalse();
    assertThat(profile.members()).isNull();
    assertThat(profile.reviews()).isNull();
    // Hidden sections are never even computed — no roster or participation query runs.
    verify(memberships, never()).findMembersByTeamId(any());
    verify(documents, never()).findTeamParticipationsVisibleTo(any(), any());
  }

  @Test
  @DisplayName("an enabled members toggle exposes the roster — without e-mail addresses")
  void membersToggleExposesRoster() {
    team.setProfileShowMembers(true);
    when(memberships.existsByTeamIdAndUserId(teamId, caller)).thenReturn(false);
    when(memberships.findMembersByTeamId(teamId)).thenReturn(List.of());

    var profile = service.getProfile(teamId, caller, false);

    assertThat(profile.members()).isNotNull();
    assertThat(profile.reviews()).isNull();
  }

  @Test
  @DisplayName("the reviews toggle exposes only the CALLER-visible intersection")
  void reviewsToggleUsesCallerVisibility() {
    team.setProfileShowReviews(true);
    when(memberships.existsByTeamIdAndUserId(teamId, caller)).thenReturn(false);
    when(documents.findTeamParticipationsVisibleTo(teamId, caller)).thenReturn(List.of());

    var profile = service.getProfile(teamId, caller, false);

    assertThat(profile.reviews()).isNotNull();
    // The intersection query IS the leak protection — the caller id must be part of it.
    verify(documents).findTeamParticipationsVisibleTo(teamId, caller);
  }

  @Test
  @DisplayName("a team member sees everything regardless of the toggles")
  void membersAlwaysSeeTheirOwnTeamInFull() {
    when(memberships.existsByTeamIdAndUserId(teamId, caller)).thenReturn(true);
    when(memberships.findMembersByTeamId(teamId)).thenReturn(List.of());
    when(documents.findTeamParticipationsVisibleTo(teamId, caller)).thenReturn(List.of());

    var profile = service.getProfile(teamId, caller, false);

    assertThat(profile.viewerIsMember()).isTrue();
    assertThat(profile.members()).isNotNull();
    assertThat(profile.reviews()).isNotNull();
  }

  @Test
  @DisplayName("an admin sees everything regardless of membership and toggles")
  void adminsSeeEverything() {
    when(memberships.existsByTeamIdAndUserId(teamId, caller)).thenReturn(false);
    when(memberships.findMembersByTeamId(teamId)).thenReturn(List.of());
    when(documents.findTeamParticipationsVisibleTo(teamId, caller)).thenReturn(List.of());

    var profile = service.getProfile(teamId, caller, true);

    assertThat(profile.viewerIsMember()).isFalse();
    assertThat(profile.members()).isNotNull();
    assertThat(profile.reviews()).isNotNull();
  }

  @Test
  @DisplayName("disabled and unknown teams answer the same 404 — by id and by slug")
  void disabledAndUnknownAnswerTheSame404() {
    team.setEnabled(false);
    assertThatThrownBy(() -> service.getProfile(teamId, caller, false))
        .isInstanceOf(TeamNotFoundException.class);

    when(teams.findBySlugIgnoreCase("ghost")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getProfileBySlug("ghost", caller, false))
        .isInstanceOf(TeamNotFoundException.class);
  }
}

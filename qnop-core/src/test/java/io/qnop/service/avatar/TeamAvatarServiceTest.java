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
package io.qnop.service.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.TeamAvatar;
import io.qnop.entity.TeamRole;
import io.qnop.repository.TeamAvatarRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.service.TeamAccessForbiddenException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamAvatarServiceTest {

  private static final UUID TEAM = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  @Mock private TeamAvatarRepository avatars;
  @Mock private TeamRepository teams;
  @Mock private TeamMembershipRepository memberships;

  private TeamAvatarService service() {
    // A real validator so the sniff/dimension/hash pipeline is exercised end to end.
    return new TeamAvatarService(new AvatarImageValidator(), avatars, teams, memberships);
  }

  private static byte[] png(int w, int h) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }

  @Test
  void storeValidatesAndReplacesTheAvatar() throws Exception {
    when(teams.existsById(TEAM)).thenReturn(true);
    when(avatars.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    byte[] bytes = png(64, 64);

    service().store(TEAM, bytes, ACTOR);

    verify(avatars).deleteByTeamId(TEAM); // delete-then-insert, no PK collision
    ArgumentCaptor<TeamAvatar> captor = ArgumentCaptor.forClass(TeamAvatar.class);
    verify(avatars).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getContentType()).isEqualTo("image/png");
    assertThat(captor.getValue().getWidth()).isEqualTo(64);
    assertThat(captor.getValue().getUpdatedBy()).isEqualTo(ACTOR);
  }

  @Test
  void storeRejectsAnUnknownTeam() {
    when(teams.existsById(TEAM)).thenReturn(false);

    assertThatThrownBy(() -> service().store(TEAM, new byte[] {1, 2, 3}, ACTOR))
        .isInstanceOf(AvatarValidationException.class)
        .extracting("code")
        .isEqualTo("TEAM_NOT_FOUND");
    verify(avatars, never()).saveAndFlush(any());
  }

  @Test
  void storeRejectsANonImage() {
    when(teams.existsById(TEAM)).thenReturn(true);

    assertThatThrownBy(() -> service().store(TEAM, "not an image".getBytes(), ACTOR))
        .isInstanceOf(AvatarValidationException.class)
        .extracting("code")
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    verify(avatars, never()).saveAndFlush(any());
  }

  @Test
  void adminMayManageAnyTeam() {
    service().requireLeadOrAdmin(TEAM, ACTOR, true);
    verifyNoInteractions(memberships);
  }

  @Test
  void aLeadMayManageTheirTeam() {
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(TEAM, ACTOR, TeamRole.LEAD))
        .thenReturn(true);
    service().requireLeadOrAdmin(TEAM, ACTOR, false);
  }

  @Test
  void aNonLeadIsForbidden() {
    when(memberships.existsByTeamIdAndUserIdAndTeamRole(TEAM, ACTOR, TeamRole.LEAD))
        .thenReturn(false);

    assertThatThrownBy(() -> service().requireLeadOrAdmin(TEAM, ACTOR, false))
        .isInstanceOf(TeamAccessForbiddenException.class);
  }
}

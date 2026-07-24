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

import io.qnop.entity.TeamAvatar;
import io.qnop.entity.TeamRole;
import io.qnop.repository.TeamAvatarRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.service.TeamAccessForbiddenException;
import io.qnop.service.avatar.AvatarStorage.AvatarContent;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and stores <em>team</em> profile avatars, and reads them back for serving (issue #509)
 * — the team counterpart of {@link AvatarService}. It shares the one-place image validation ({@link
 * AvatarImageValidator}) and persists to the {@code team_avatar} table directly (teams keep their
 * bytes in Postgres {@code bytea}, an explicit non-goal to move to object storage), rather than
 * through the user-only {@code AvatarStorage} port.
 *
 * <p>The CPU-bound validation runs outside any transaction; only the {@code team_avatar} write is
 * transactional. Uploads are gated to a team {@code LEAD} or an admin.
 */
@Service
public class TeamAvatarService {

  private final AvatarImageValidator validator;
  private final TeamAvatarRepository avatars;
  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;

  public TeamAvatarService(
      AvatarImageValidator validator,
      TeamAvatarRepository avatars,
      TeamRepository teams,
      TeamMembershipRepository memberships) {
    this.validator = validator;
    this.avatars = avatars;
    this.teams = teams;
    this.memberships = memberships;
  }

  /**
   * Validates the uploaded bytes and replaces {@code teamId}'s avatar.
   *
   * @param actor the user performing the upload (admin or team lead), recorded as {@code
   *     updated_by}
   * @return when the new avatar was stored (its {@code updated_at}), for building the avatar URL
   * @throws AvatarValidationException if the team is unknown, the type unsupported, the payload too
   *     large, or the image unreadable/oversized
   */
  @Transactional
  public Instant store(UUID teamId, byte[] bytes, UUID actor) {
    if (!teams.existsById(teamId)) {
      throw AvatarValidationException.teamNotFound("no such team: " + teamId);
    }
    AvatarImageValidator.ValidatedImage image = validator.validate(bytes);
    // Bulk-delete the current row first (immediate DML) so the re-insert never collides with the
    // (team_id) primary key, then insert the fresh row with a new creation timestamp.
    avatars.deleteByTeamId(teamId);
    TeamAvatar saved =
        avatars.saveAndFlush(
            TeamAvatar.create(
                teamId,
                image.contentType(),
                bytes,
                image.sha256(),
                image.sizeBytes(),
                image.width(),
                image.height(),
                actor));
    return saved.getUpdatedAt();
  }

  /** The team's avatar bytes for serving, or empty when none is set. */
  @Transactional(readOnly = true)
  public Optional<AvatarContent> get(UUID teamId) {
    return avatars
        .findById(teamId)
        .map(a -> new AvatarContent(a.getContentType(), a.getContent(), a.getSha256()));
  }

  /** Removes the team's avatar (idempotent). */
  @Transactional
  public void remove(UUID teamId) {
    avatars.deleteByTeamId(teamId);
  }

  /** When the team's avatar was last set, or empty when none is set. */
  @Transactional(readOnly = true)
  public Optional<Instant> updatedAt(UUID teamId) {
    return avatars.findUpdatedAtByTeamId(teamId);
  }

  /**
   * Batch {@link #updatedAt(UUID)} for a team list — an entry only for teams that have an avatar.
   */
  @Transactional(readOnly = true)
  public Map<UUID, Instant> updatedAt(Collection<UUID> teamIds) {
    if (teamIds.isEmpty()) {
      return Map.of();
    }
    return avatars.findUpdatedAtByTeamIdIn(teamIds).stream()
        .collect(
            Collectors.toMap(
                TeamAvatarRepository.AvatarUpdatedAtView::getTeamId,
                TeamAvatarRepository.AvatarUpdatedAtView::getUpdatedAt));
  }

  /**
   * Enforces that {@code actorId} may manage {@code teamId}'s avatar — an admin, or a {@code LEAD}
   * of the team (issue #470/#505 pattern). Mirrors {@code TeamService.requireLeadOrAdmin}.
   *
   * @throws TeamAccessForbiddenException when neither holds
   */
  public void requireLeadOrAdmin(UUID teamId, UUID actorId, boolean admin) {
    if (admin) {
      return;
    }
    if (!memberships.existsByTeamIdAndUserIdAndTeamRole(teamId, actorId, TeamRole.LEAD)) {
      throw new TeamAccessForbiddenException("Only a lead of this team may manage its avatar.");
    }
  }
}

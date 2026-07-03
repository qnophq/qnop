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

import io.qnop.api.v1.endpoint.PrincipalsApi;
import io.qnop.api.v1.model.ParticipantKind;
import io.qnop.api.v1.model.PrincipalListResponse;
import io.qnop.api.v1.model.PrincipalView;
import io.qnop.service.PrincipalDirectoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assignable-principal directory (issue #292): enabled users and teams for picking review
 * participants — display names only, available to every authenticated user.
 */
@RestController
public class PrincipalsController implements PrincipalsApi {

  private final PrincipalDirectoryService directory;

  public PrincipalsController(PrincipalDirectoryService directory) {
    this.directory = directory;
  }

  @Override
  public ResponseEntity<PrincipalListResponse> searchPrincipals(String q, Integer size) {
    CurrentUser.requireUserId();
    return ResponseEntity.ok(
        new PrincipalListResponse()
            .principals(
                directory.search(q, size == null ? 20 : size).stream()
                    .map(
                        view ->
                            new PrincipalView()
                                .id(view.id())
                                .kind(view.team() ? ParticipantKind.TEAM : ParticipantKind.USER)
                                .displayName(view.displayName()))
                    .toList()));
  }
}

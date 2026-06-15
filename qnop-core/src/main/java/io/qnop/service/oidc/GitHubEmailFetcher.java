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
package io.qnop.service.oidc;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches a GitHub user's primary, verified email via {@code GET /user/emails} (issue #21). GitHub
 * omits the email from userinfo unless it is public, so when the login principal carries no email
 * this is the fallback. The user's OAuth2 access token authorizes the call (needs {@code
 * user:email} scope).
 */
@Component
public class GitHubEmailFetcher {

  private static final String EMAILS_ENDPOINT = "https://api.github.com/user/emails";
  private static final ParameterizedTypeReference<List<Map<String, Object>>> EMAIL_LIST =
      new ParameterizedTypeReference<>() {};
  private static final Logger log = LoggerFactory.getLogger(GitHubEmailFetcher.class);

  private final RestClient restClient = RestClient.create();

  /** The primary verified email for the token's user, or {@code null} if none is available. */
  public String fetchPrimaryEmail(String accessToken) {
    try {
      List<Map<String, Object>> emails =
          restClient
              .get()
              .uri(EMAILS_ENDPOINT)
              .header("Authorization", "Bearer " + accessToken)
              .header("Accept", "application/vnd.github+json")
              .retrieve()
              .body(EMAIL_LIST);
      if (emails == null) {
        return null;
      }
      String firstVerified = null;
      for (Map<String, Object> entry : emails) {
        if (!Boolean.TRUE.equals(entry.get("verified"))) {
          continue;
        }
        Object email = entry.get("email");
        if (email == null) {
          continue;
        }
        if (Boolean.TRUE.equals(entry.get("primary"))) {
          return email.toString();
        }
        if (firstVerified == null) {
          firstVerified = email.toString();
        }
      }
      return firstVerified;
    } catch (RuntimeException e) {
      log.warn("Failed to fetch GitHub primary email: {}", e.getMessage());
      return null;
    }
  }
}

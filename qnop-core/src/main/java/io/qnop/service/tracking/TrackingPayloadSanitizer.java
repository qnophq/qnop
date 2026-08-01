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
package io.qnop.service.tracking;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rewrites the URL fields inside a measurement before it is forwarded (issue #666).
 *
 * <p>Each backend has its own body shape, but they agree on one thing: somewhere in it sits the
 * address of the page. That field is the one place a document id can escape, so it is found by name
 * — at any depth, because PostHog nests it under {@code properties} and Umami under {@code payload}
 * — and run through {@link TrackedUrlSanitizer}.
 *
 * <p>A body that will not parse as JSON is forwarded untouched. That is a deliberate limit and not
 * an oversight: the client already sends patterns, and refusing every payload this class does not
 * fully understand would break measurement for a class of requests without making anything safer.
 * The clients qnop configures are set to send plain JSON for exactly this reason.
 */
public final class TrackingPayloadSanitizer {

  private TrackingPayloadSanitizer() {}

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Field names that carry a page address in one backend or another. */
  private static final Set<String> URL_FIELDS =
      Set.of(
          "url",
          "u",
          "$current_url",
          "current_url",
          "page_url",
          "href",
          "referrer",
          "urlref",
          "$referrer");

  /** Query parameters that carry one, for the backends that measure over a query string. */
  private static final Set<String> URL_QUERY_PARAMS = Set.of("url", "u", "urlref");

  /**
   * Fields that carry the page <em>title</em>, which is dropped rather than rewritten.
   *
   * <p>Every one of these backends sends the document title along with the address — Umami as
   * {@code title}, Matomo as {@code action_name}, PostHog as {@code $title}. In qnop a page title
   * is not neutral: the moment somebody makes browser tabs say what they are showing, it becomes
   * the name of a customer's contract. Rewriting it is not possible (a title has no structure to
   * anonymise), so it does not travel at all and reports are keyed on the route pattern instead.
   */
  private static final Set<String> DROPPED_FIELDS =
      Set.of("title", "$title", "page_title", "action_name", "pageTitle");

  /** Returns the body with every recognised URL field sanitized; the input if it is not JSON. */
  public static byte[] sanitizeBody(byte[] body) {
    if (body == null || body.length == 0) {
      return body;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      sanitizeNode(root);
      return MAPPER.writeValueAsBytes(root);
    } catch (JacksonException e) {
      return body;
    }
  }

  /** Returns the query string with every recognised parameter sanitized. */
  public static String sanitizeQuery(String query) {
    if (query == null || query.isBlank()) {
      return query;
    }
    String[] pairs = query.split("&", -1);
    StringBuilder out = new StringBuilder(query.length());
    for (int i = 0; i < pairs.length; i++) {
      if (i > 0) {
        out.append('&');
      }
      int eq = pairs[i].indexOf('=');
      if (eq < 0) {
        out.append(pairs[i]);
        continue;
      }
      String name = pairs[i].substring(0, eq);
      String value = pairs[i].substring(eq + 1);
      if (DROPPED_FIELDS.contains(name)) {
        // Matomo carries the title as action_name; it is emptied rather than
        // removed so the parameter list stays the shape the backend expects.
        out.append(name).append('=');
        continue;
      }
      if (URL_QUERY_PARAMS.contains(name)) {
        String decoded = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        value =
            java.net.URLEncoder.encode(
                TrackedUrlSanitizer.sanitize(decoded), StandardCharsets.UTF_8);
      }
      out.append(name).append('=').append(value);
    }
    return out.toString();
  }

  private static void sanitizeNode(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String name : List.copyOf(object.propertyNames())) {
        JsonNode value = object.get(name);
        if (DROPPED_FIELDS.contains(name)) {
          object.remove(name);
        } else if (value != null && value.isString() && URL_FIELDS.contains(name)) {
          object.put(name, TrackedUrlSanitizer.sanitize(value.stringValue()));
        } else {
          sanitizeNode(value);
        }
      }
    } else if (node instanceof ArrayNode array) {
      for (JsonNode item : array) {
        sanitizeNode(item);
      }
    }
  }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips identifiers out of a measured URL before it leaves this server (issue #666).
 *
 * <p>The client already sends route patterns rather than paths — it knows which segments were
 * parameters, because the router told it. This is the second line: it runs on every forwarded
 * measurement, so a stale bundle, a hand-rolled client or a future page that forgot the rule cannot
 * put a document id into someone's analytics backend, where it would sit in a report next to a page
 * title forever.
 *
 * <p>Being second, it is deliberately blunt: the query string always goes, and any segment that
 * looks like an identifier becomes {@code :id}. It cannot recognise a slug ({@code
 * /users/mia-member}) as personal — that one relies on the client sending {@code :userId} — and it
 * says so here rather than pretending to a completeness it does not have.
 */
public final class TrackedUrlSanitizer {

  private TrackedUrlSanitizer() {}

  private static final String PLACEHOLDER = ":id";

  /** A segment longer than this is treated as an identifier whatever it looks like. */
  private static final int MAX_PLAIN_SEGMENT = 32;

  private static final Pattern UUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{12,}$");
  private static final Pattern NUMERIC = Pattern.compile("^[0-9]{3,}$");

  /**
   * Returns {@code url} with its query gone and every identifier-looking path segment replaced.
   *
   * <p>Absolute URLs keep scheme and host (an analytics backend needs the site to attribute to);
   * anything unparseable collapses to {@code "/"} rather than travelling as-is.
   */
  public static String sanitize(String url) {
    if (url == null || url.isBlank()) {
      return "/";
    }
    String value = url.trim();
    String prefix = "";
    String rest = value;

    int schemeEnd = value.indexOf("://");
    if (schemeEnd >= 0) {
      int hostEnd = value.indexOf('/', schemeEnd + 3);
      if (hostEnd < 0) {
        // Scheme and host with no path at all: nothing to strip.
        return stripQuery(value);
      }
      prefix = value.substring(0, hostEnd);
      rest = value.substring(hostEnd);
    }

    rest = stripQuery(rest);
    if (!rest.startsWith("/")) {
      rest = "/" + rest;
    }
    return prefix + sanitizePath(rest);
  }

  private static String stripQuery(String value) {
    int cut = value.length();
    int query = value.indexOf('?');
    if (query >= 0) {
      cut = query;
    }
    int fragment = value.indexOf('#');
    if (fragment >= 0 && fragment < cut) {
      cut = fragment;
    }
    return value.substring(0, cut);
  }

  private static String sanitizePath(String path) {
    String[] segments = path.split("/", -1);
    List<String> kept = new ArrayList<>(segments.length);
    for (String segment : segments) {
      kept.add(looksLikeIdentifier(segment) ? PLACEHOLDER : segment);
    }
    return String.join("/", kept);
  }

  private static boolean looksLikeIdentifier(String segment) {
    if (segment.isEmpty() || segment.startsWith(":")) {
      // Already a pattern from the client — the good case, left exactly as it is.
      return false;
    }
    return segment.length() > MAX_PLAIN_SEGMENT
        || UUID.matcher(segment).matches()
        || LONG_HEX.matcher(segment).matches()
        || NUMERIC.matcher(segment).matches();
  }
}

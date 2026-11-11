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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Accepts a measurement only in the exact shape qnop's own client builds, and rebuilds it from the
 * validated values (issue #910).
 *
 * <p>The collect endpoint is unauthenticated by necessity — the sign-in screen is measured too — so
 * anyone can send to it. Before #910 the content was whatever the caller sent, minus URL and title
 * rewriting. Now that no vendor script is involved, every legitimate request has a known shape (see
 * {@code qnop-ui/src/tracking/providers.ts}), and anything else is refused: unknown fields, person
 * or identity properties, custom data, another site id on the same analytics instance, an event
 * outside the closed list of four. What is forwarded is re-serialised from the checked values with
 * a pinned content type, never the caller's bytes.
 *
 * <p>Pure and DB-free, so every rule is asserted directly in a unit test.
 */
public final class TrackingMeasurementValidator {

  private TrackingMeasurementValidator() {}

  /** The closed list of events (ADR-0056); mirrors {@code TrackedEvent} in the client. */
  static final Set<String> EVENTS =
      Set.of("review_created", "annotation_created", "review_finalized", "export_generated");

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final int MAX_URL = 2048;
  private static final Pattern DIGITS = Pattern.compile("\\d{1,20}");
  private static final Pattern SCREEN = Pattern.compile("\\d{1,5}x\\d{1,5}");
  private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9-]{0,35}");
  private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");
  private static final Pattern VISIT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private static final String JSON = "application/json";
  private static final String TEXT = "text/plain;charset=UTF-8";

  /** A measurement in its forwardable form. */
  public record Measurement(String method, String query, byte[] body, String contentType) {}

  /** The forwardable form of a request, or empty when it is not one qnop's client would send. */
  public static Optional<Measurement> validate(
      TrackingRuntimeConfig config, String method, String path, String query, byte[] body) {
    if (config == null || method == null || path == null) {
      return Optional.empty();
    }
    String siteId = config.siteId();
    boolean get = "GET".equalsIgnoreCase(method);
    boolean post = "POST".equalsIgnoreCase(method);
    try {
      return switch (config.provider()) {
        case MATOMO ->
            get && "/matomo.php".equals(path) && isEmpty(body)
                ? matomo(siteId, query)
                : Optional.empty();
        case PLAUSIBLE ->
            post && "/api/event".equals(path) && isBlank(query)
                ? plausible(siteId, body)
                : Optional.empty();
        case UMAMI ->
            post && "/api/send".equals(path) && isBlank(query)
                ? umami(siteId, body)
                : Optional.empty();
        case POSTHOG ->
            post && "/i/v0/e/".equals(path) && isBlank(query)
                ? posthog(siteId, body)
                : Optional.empty();
        case PIRSCH -> pirsch(siteId, get, post, path, query, body);
      };
    } catch (Invalid | JacksonException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  // --- Matomo: GET /matomo.php, HTTP Tracking API ------------------------------------------

  private static Optional<Measurement> matomo(String siteId, String query) {
    Map<String, String> in =
        parseQuery(
            query,
            Set.of("idsite", "rec", "apiv", "send_image", "url", "res", "e_c", "e_a", "rand"));
    Map<String, String> out = new LinkedHashMap<>();
    out.put("idsite", require(in, "idsite", siteId::equals));
    out.put("rec", require(in, "rec", "1"::equals));
    optional(in, out, "apiv", "1"::equals);
    optional(in, out, "send_image", "0"::equals);
    out.put("url", require(in, "url", TrackingMeasurementValidator::isUrl));
    optional(in, out, "res", v -> SCREEN.matcher(v).matches());
    boolean hasCategory = in.containsKey("e_c");
    if (hasCategory != in.containsKey("e_a")) {
      throw new Invalid();
    }
    if (hasCategory) {
      out.put("e_c", require(in, "e_c", "qnop"::equals));
      out.put("e_a", require(in, "e_a", EVENTS::contains));
    }
    optional(in, out, "rand", v -> DIGITS.matcher(v).matches());
    return Optional.of(new Measurement("GET", encodeQuery(out), new byte[0], null));
  }

  // --- Plausible: POST /api/event, Events API -----------------------------------------------

  private static Optional<Measurement> plausible(String siteId, byte[] body) {
    ObjectNode in = object(body, Set.of("name", "url", "domain", "referrer"));
    String name = string(in, "name", true);
    if (!"pageview".equals(name) && !EVENTS.contains(name)) {
      throw new Invalid();
    }
    requireNullOrAbsent(in, "referrer");
    ObjectNode out = MAPPER.createObjectNode();
    out.put("name", name);
    out.put("url", url(in, "url"));
    out.put("domain", equalTo(string(in, "domain", true), siteId));
    out.putNull("referrer");
    return post(out, TEXT);
  }

  // --- Umami: POST /api/send -----------------------------------------------------------------

  private static Optional<Measurement> umami(String siteId, byte[] body) {
    ObjectNode in = object(body, Set.of("type", "payload"));
    equalTo(string(in, "type", true), "event");
    ObjectNode payload =
        child(
            in,
            "payload",
            Set.of("website", "hostname", "language", "screen", "url", "referrer", "name"));
    ObjectNode p = MAPPER.createObjectNode();
    p.put("website", equalTo(string(payload, "website", true), siteId));
    p.put("hostname", matching(string(payload, "hostname", true), HOSTNAME));
    p.put("language", matching(orEmpty(string(payload, "language", false)), LANGUAGE));
    p.put("screen", matching(string(payload, "screen", true), SCREEN));
    p.put("url", url(payload, "url"));
    p.put("referrer", equalTo(orEmpty(string(payload, "referrer", false)), ""));
    String name = string(payload, "name", false);
    if (name != null) {
      p.put("name", member(name, EVENTS));
    }
    ObjectNode out = MAPPER.createObjectNode();
    out.put("type", "event");
    out.set("payload", p);
    return post(out, JSON);
  }

  // --- PostHog: POST /i/v0/e/, capture API ---------------------------------------------------

  private static Optional<Measurement> posthog(String siteId, byte[] body) {
    ObjectNode in = object(body, Set.of("api_key", "event", "distinct_id", "properties"));
    String event = string(in, "event", true);
    if (!"$pageview".equals(event) && !EVENTS.contains(event)) {
      throw new Invalid();
    }
    ObjectNode props =
        child(in, "properties", Set.of("$current_url", "$process_person_profile", "$lib"));
    JsonNode profile = props.get("$process_person_profile");
    if (profile != null && !(profile.isBoolean() && !profile.booleanValue())) {
      throw new Invalid();
    }
    ObjectNode p = MAPPER.createObjectNode();
    p.put("$current_url", url(props, "$current_url"));
    // Forced, whatever the caller sent: an anonymous event never creates a person.
    p.put("$process_person_profile", false);
    p.put("$lib", "qnop");
    ObjectNode out = MAPPER.createObjectNode();
    out.put("api_key", equalTo(string(in, "api_key", true), siteId));
    out.put("event", event);
    out.put("distinct_id", matching(string(in, "distinct_id", true), VISIT_ID));
    out.set("properties", p);
    return post(out, JSON);
  }

  // --- Pirsch: GET /hit and POST /event, as pa.js sends them ---------------------------------

  private static Optional<Measurement> pirsch(
      String siteId, boolean get, boolean post, String path, String query, byte[] body) {
    if (get && "/hit".equals(path) && isEmpty(body)) {
      Map<String, String> in = parseQuery(query, Set.of("nc", "code", "url", "t", "ref", "w", "h"));
      Map<String, String> out = new LinkedHashMap<>();
      optional(in, out, "nc", v -> DIGITS.matcher(v).matches());
      out.put("code", require(in, "code", siteId::equals));
      out.put("url", require(in, "url", TrackingMeasurementValidator::isUrl));
      optional(in, out, "t", String::isEmpty);
      optional(in, out, "ref", String::isEmpty);
      optional(in, out, "w", v -> v.isEmpty() || DIGITS.matcher(v).matches());
      optional(in, out, "h", v -> v.isEmpty() || DIGITS.matcher(v).matches());
      return Optional.of(new Measurement("GET", encodeQuery(out), new byte[0], null));
    }
    if (post && "/event".equals(path) && isBlank(query)) {
      ObjectNode in =
          object(
              body,
              Set.of(
                  "identification_code",
                  "url",
                  "title",
                  "referrer",
                  "screen_width",
                  "screen_height",
                  "tags",
                  "event_name",
                  "event_duration",
                  "event_meta",
                  "non_interactive"));
      equalTo(orEmpty(string(in, "title", false)), "");
      equalTo(orEmpty(string(in, "referrer", false)), "");
      requireEmptyObjectOrAbsent(in, "tags");
      requireEmptyObjectOrAbsent(in, "event_meta");
      ObjectNode out = MAPPER.createObjectNode();
      out.put("identification_code", equalTo(string(in, "identification_code", true), siteId));
      out.put("url", url(in, "url"));
      out.put("title", "");
      out.put("referrer", "");
      out.put("screen_width", dimension(in, "screen_width"));
      out.put("screen_height", dimension(in, "screen_height"));
      out.set("tags", MAPPER.createObjectNode());
      out.put("event_name", member(string(in, "event_name", true), EVENTS));
      out.put("event_duration", 0);
      out.set("event_meta", MAPPER.createObjectNode());
      out.put("non_interactive", false);
      return post(out, TEXT);
    }
    return Optional.empty();
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** A refusal; never leaves this class. */
  private static final class Invalid extends RuntimeException {
    Invalid() {
      super(null, null, false, false);
    }
  }

  private static Optional<Measurement> post(ObjectNode out, String contentType) {
    return Optional.of(new Measurement("POST", null, MAPPER.writeValueAsBytes(out), contentType));
  }

  private static boolean isEmpty(byte[] body) {
    return body == null || body.length == 0;
  }

  private static boolean isBlank(String query) {
    return query == null || query.isBlank();
  }

  private static boolean isUrl(String value) {
    return !value.isBlank() && value.length() <= MAX_URL;
  }

  private static Map<String, String> parseQuery(String query, Set<String> allowed) {
    if (isBlank(query)) {
      throw new Invalid();
    }
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : query.split("&", -1)) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        throw new Invalid();
      }
      String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      if (!allowed.contains(name) || params.put(name, value) != null) {
        throw new Invalid();
      }
    }
    return params;
  }

  private static String encodeQuery(Map<String, String> params) {
    StringBuilder out = new StringBuilder();
    params.forEach(
        (name, value) -> {
          if (!out.isEmpty()) {
            out.append('&');
          }
          out.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
    return out.toString();
  }

  private static String require(
      Map<String, String> params, String name, java.util.function.Predicate<String> rule) {
    String value = params.get(name);
    if (value == null || !rule.test(value)) {
      throw new Invalid();
    }
    return value;
  }

  private static void optional(
      Map<String, String> in,
      Map<String, String> out,
      String name,
      java.util.function.Predicate<String> rule) {
    if (in.containsKey(name)) {
      out.put(name, require(in, name, rule));
    }
  }

  private static ObjectNode object(byte[] body, Set<String> allowed) {
    if (isEmpty(body)) {
      throw new Invalid();
    }
    return keysWithin(MAPPER.readTree(body), allowed);
  }

  private static ObjectNode child(ObjectNode parent, String name, Set<String> allowed) {
    JsonNode node = parent.get(name);
    if (node == null) {
      throw new Invalid();
    }
    return keysWithin(node, allowed);
  }

  private static ObjectNode keysWithin(JsonNode node, Set<String> allowed) {
    if (!(node instanceof ObjectNode object)) {
      throw new Invalid();
    }
    for (String key : object.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new Invalid();
      }
    }
    return object;
  }

  /** A string field; null when absent and not required. Any other JSON type is refused. */
  private static String string(ObjectNode node, String name, boolean required) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) {
      if (required) {
        throw new Invalid();
      }
      return null;
    }
    if (!value.isString()) {
      throw new Invalid();
    }
    return value.stringValue();
  }

  private static String url(ObjectNode node, String name) {
    String value = string(node, name, true);
    if (!isUrl(value)) {
      throw new Invalid();
    }
    return value;
  }

  private static int dimension(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null) {
      return 0;
    }
    if (!value.isIntegralNumber() || value.intValue() < 0 || value.intValue() > 100_000) {
      throw new Invalid();
    }
    return value.intValue();
  }

  private static void requireNullOrAbsent(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value != null && !value.isNull()) {
      throw new Invalid();
    }
  }

  private static void requireEmptyObjectOrAbsent(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value != null && !(value.isObject() && value.isEmpty())) {
      throw new Invalid();
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String equalTo(String value, String expected) {
    if (!expected.equals(value)) {
      throw new Invalid();
    }
    return value;
  }

  private static String matching(String value, Pattern pattern) {
    if (!pattern.matcher(value).matches()) {
      throw new Invalid();
    }
    return value;
  }

  private static String member(String value, Set<String> allowed) {
    if (!allowed.contains(value)) {
      throw new Invalid();
    }
    return value;
  }
}

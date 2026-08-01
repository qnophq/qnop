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

import io.qnop.service.tracking.TrackingProxyService;
import io.qnop.web.security.ratelimit.HttpClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The two endpoints that keep analytics inside the origin (issue #666).
 *
 * <p>Short, opaque paths on purpose. {@code /t/s.js} and {@code /t/c/…} carry no word an ad blocker
 * matches on — not to defeat a user's choice (Do-Not-Track is honoured, consent is asked for, and
 * the profile switch wins over everything) but because a blocked measurement from a consenting user
 * is just a hole in the numbers.
 *
 * <p>Deliberately outside {@code /api/v1}: this is not part of the published REST contract
 * (ADR-0015). It speaks whatever shape the configured analytics backend speaks, and that is the
 * backend's contract, not qnop's.
 */
// @Controller, not @RestController: ApiPathConfig mounts every @RestController under
// /api/v1, and these two are infrastructure rather than part of the published
// contract (ADR-0015) — the client's script tag and beacon URLs must stay where the
// security rules and the CSP expect them.
@Controller
public class TrackingProxyController {

  private final TrackingProxyService proxy;
  private final HttpClientIpResolver clientIp;

  public TrackingProxyController(TrackingProxyService proxy, HttpClientIpResolver clientIp) {
    this.proxy = proxy;
    this.clientIp = clientIp;
  }

  /** The vendor script, served from this origin so the CSP need not name a second one. */
  @GetMapping("/t/s.js")
  @ResponseBody
  public ResponseEntity<byte[]> script() {
    return proxy
        .script()
        .map(
            script ->
                ResponseEntity.ok()
                    // Short enough that switching backends takes effect within the hour,
                    // long enough that a reload is not a round-trip to the vendor.
                    .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                    .header("Content-Type", script.contentType())
                    .body(script.body()))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * One measurement, forwarded to the configured backend.
   *
   * <p>Answers 204 whatever the backend said. The browser can do nothing useful with an analytics
   * error, and a page that reports its own measurement failures to the user is a page that has its
   * priorities backwards — the server logs what went wrong instead.
   */
  @PostMapping("/t/c/**")
  @ResponseBody
  public ResponseEntity<Void> collect(HttpServletRequest request) throws IOException {
    String path = request.getRequestURI().substring("/t/c".length());
    byte[] body = request.getInputStream().readNBytes(TrackingProxyService.MAX_BODY_BYTES + 1);
    if (body.length > TrackingProxyService.MAX_BODY_BYTES) {
      // A measurement is a few hundred bytes. Something this size is not one.
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
    }
    // Resolved through the same trusted-proxy rules the rate limiters use
    // (ADR-0027): behind a load balancer getRemoteAddr() is the balancer, and
    // truncating that address would tell the backend nothing at all.
    proxy.forward(
        path,
        request.getQueryString(),
        body,
        request.getContentType(),
        clientIp.resolve(request),
        request.getHeader("User-Agent"));
    return ResponseEntity.noContent().build();
  }
}

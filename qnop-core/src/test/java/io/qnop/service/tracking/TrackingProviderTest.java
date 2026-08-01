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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The provider table, and above all what it refuses (issue #666). */
class TrackingProviderTest {

  @Test
  @DisplayName("session recording is not reachable through this server")
  void refusesSessionRecording() {
    // The promise "qnop does not do session replay" is kept here rather than in a
    // client configuration somebody could change: PostHog's recorder posts to
    // /s/, and this server will not forward it whatever the page asks.
    assertThat(TrackingProvider.POSTHOG.allowsCollectPath("/s/")).isFalse();
    assertThat(TrackingProvider.POSTHOG.allowsCollectPath("/s")).isFalse();
    assertThat(TrackingProvider.POSTHOG.allowsCollectPath("/static/recorder.js")).isFalse();
  }

  @Test
  @DisplayName("only declared paths are forwarded, matched whole")
  void allowsOnlyDeclaredPaths() {
    assertThat(TrackingProvider.PLAUSIBLE.allowsCollectPath("/api/event")).isTrue();
    assertThat(TrackingProvider.PLAUSIBLE.allowsCollectPath("/api/event/../../admin")).isFalse();
    // A prefix match would have let this through; the list is compared exactly.
    // Verified against api.pirsch.io: /hit, /event and /session answer; the
    // /pv, /e, /s this once assumed answer 404.
    assertThat(TrackingProvider.PIRSCH.allowsCollectPath("/hit")).isTrue();
    assertThat(TrackingProvider.PIRSCH.allowsCollectPath("/event")).isTrue();
    assertThat(TrackingProvider.PIRSCH.allowsCollectPath("/pv")).isFalse();
    assertThat(TrackingProvider.PIRSCH.allowsCollectPath("/export-everything")).isFalse();
    assertThat(TrackingProvider.UMAMI.allowsCollectPath("/api/send")).isTrue();
    assertThat(TrackingProvider.UMAMI.allowsCollectPath("/api/admin/websites")).isFalse();
  }

  @Test
  @DisplayName("the self-hosted backends say so")
  void knowsWhichNeedAHost() {
    assertThat(TrackingProvider.MATOMO.requiresHost()).isTrue();
    assertThat(TrackingProvider.UMAMI.requiresHost()).isTrue();
    assertThat(TrackingProvider.PLAUSIBLE.requiresHost()).isFalse();
    assertThat(TrackingProvider.POSTHOG.requiresHost()).isFalse();
    assertThat(TrackingProvider.PIRSCH.requiresHost()).isFalse();
  }

  @Test
  @DisplayName("PostHog's cloud serves its script from a different host than its ingestion")
  void separatesAssetsFromIngestion() {
    assertThat(TrackingProvider.POSTHOG.defaultScriptHost())
        .isEqualTo("https://us-assets.i.posthog.com");
    assertThat(TrackingProvider.POSTHOG.defaultHost()).isEqualTo("https://us.i.posthog.com");
    // Everyone else serves both from the same place.
    assertThat(TrackingProvider.PIRSCH.defaultScriptHost())
        .isEqualTo(TrackingProvider.PIRSCH.defaultHost());
  }

  @Test
  @DisplayName("ids map to providers, and nothing else does")
  void mapsIds() {
    assertThat(TrackingProvider.fromId("matomo")).contains(TrackingProvider.MATOMO);
    assertThat(TrackingProvider.fromId(" PostHog ")).contains(TrackingProvider.POSTHOG);
    assertThat(TrackingProvider.fromId("none")).isEmpty();
    assertThat(TrackingProvider.fromId(null)).isEmpty();
    assertThat(TrackingProvider.fromId("hotjar")).isEmpty();
  }
}

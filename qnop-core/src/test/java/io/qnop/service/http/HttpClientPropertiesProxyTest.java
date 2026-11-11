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
package io.qnop.service.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.service.http.HttpClientProperties.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The {@code qnop.http-client.proxy.*} opt-in: binding, defaults and validation (issue #940). */
class HttpClientPropertiesProxyTest {

  private static HttpClientProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("qnop.http-client", HttpClientProperties.class);
  }

  @Test
  @DisplayName("unset means no proxy: direct connections")
  void defaultIsDirect() {
    assertThat(bind(Map.of()).proxy().enabled()).isFalse();
    assertThat(new HttpClientProperties(null, null, null, null, null, null).proxy())
        .isEqualTo(Proxy.NONE);
    // application.yml maps every key to an env var with an empty default.
    assertThat(
            bind(Map.of(
                    "qnop.http-client.proxy.host", "",
                    "qnop.http-client.proxy.port", "",
                    "qnop.http-client.proxy.non-proxy-hosts", ""))
                .proxy())
        .isEqualTo(Proxy.NONE);
  }

  @Test
  @DisplayName("binds host, port and a comma-separated non-proxy-hosts list")
  void bindsTheProxyProperties() {
    Proxy proxy =
        bind(Map.of(
                "qnop.http-client.proxy.host", "proxy.corp.example",
                "qnop.http-client.proxy.port", "3128",
                "qnop.http-client.proxy.non-proxy-hosts", "IDP.corp.example, *.Internal.corp"))
            .proxy();

    assertThat(proxy.enabled()).isTrue();
    assertThat(proxy.host()).isEqualTo("proxy.corp.example");
    assertThat(proxy.port()).isEqualTo(3128);
    assertThat(proxy.nonProxyHosts()).containsExactly("idp.corp.example", "*.internal.corp");
  }

  @Test
  @DisplayName("a host without a port is refused")
  void hostRequiresPort() {
    assertThatThrownBy(() -> new Proxy("proxy.corp.example", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("port");
    assertThatThrownBy(() -> new Proxy("proxy.corp.example", 70000, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a port or non-proxy list without a host is refused rather than ignored")
  void partialConfigurationIsRefused() {
    assertThatThrownBy(() -> new Proxy(null, 3128, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Proxy(" ", null, List.of("a.example")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://proxy.corp",
        "proxy.corp:3128",
        "proxy.corp:3128/",
        "user:pw@proxy",
        "proxy corp",
        "[::1]"
      })
  @DisplayName("the host must be a bare name or IP literal")
  void hostMustBeBare(String host) {
    assertThatThrownBy(() -> new Proxy(host, 3128, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "corp.*", "a*.corp", "*corp.example", "http://x"})
  @DisplayName("non-proxy entries are exact host names or *.suffix")
  void nonProxyEntriesAreValidated(String entry) {
    assertThatThrownBy(() -> new Proxy("proxy", 3128, List.of(entry)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("non-proxy matching is exact or subdomain-suffix, case-insensitive")
  void bypassMatching() {
    Proxy proxy = new Proxy("proxy", 3128, List.of("idp.corp.example", "*.internal.corp"));

    assertThat(proxy.bypasses("IDP.corp.example")).isTrue();
    assertThat(proxy.bypasses("idp.corp.example.")).isTrue();
    assertThat(proxy.bypasses("a.internal.corp")).isTrue();
    assertThat(proxy.bypasses("a.b.internal.corp")).isTrue();
    assertThat(proxy.bypasses("internal.corp")).isFalse();
    assertThat(proxy.bypasses("evilinternal.corp")).isFalse();
    assertThat(proxy.bypasses("other.corp.example")).isFalse();
    assertThat(proxy.bypasses(null)).isFalse();
  }

  @Test
  @DisplayName("the startup notice names host and port only when proxy mode is active")
  void startupNotice() {
    assertThat(OutboundProxyStartupNotice.message(Proxy.NONE)).isEmpty();
    assertThat(
            OutboundProxyStartupNotice.message(
                new Proxy("proxy.corp.example", 3128, List.of("a.example"))))
        .hasValueSatisfying(
            line ->
                assertThat(line)
                    .contains("proxy.corp.example:3128")
                    .contains("1 non-proxy host entries"));
  }
}

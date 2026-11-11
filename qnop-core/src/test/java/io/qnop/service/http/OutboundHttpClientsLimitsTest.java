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

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Time and size bounds of the guarded clients (issue #904): a hung backend cannot hold callers
 * beyond the configured timeouts even once the pool is exhausted, response bodies are capped, and
 * compressed bodies are not inflated on the caller's behalf.
 */
class OutboundHttpClientsLimitsTest {

  private static final HttpClientProperties SHORT =
      new HttpClientProperties(
          Duration.ofMillis(500), Duration.ofMillis(500), null, null, null, null);

  private HttpServer server;
  private ExecutorService serverThreads;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    serverThreads = Executors.newCachedThreadPool();
    server.setExecutor(serverThreads);
    server.createContext(
        "/hang",
        exchange -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.createContext(
        "/big",
        exchange -> {
          byte[] chunk = new byte[64 * 1024];
          exchange.sendResponseHeaders(200, 0); // chunked: no Content-Length to reject up front
          try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 20; i++) {
              out.write(chunk);
            }
          } catch (IOException ignored) {
            // the client hung up once it had read enough — expected
          }
        });
    server.createContext(
        "/declared-big",
        exchange -> {
          exchange.sendResponseHeaders(200, OutboundHttpClients.MAX_RESPONSE_BYTES + 1L);
          exchange.close();
        });
    server.createContext(
        "/gzip",
        exchange -> {
          ByteArrayOutputStream compressed = new ByteArrayOutputStream();
          try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(new byte[2 * OutboundHttpClients.MAX_RESPONSE_BYTES]);
          }
          byte[] body = compressed.toByteArray();
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
    serverThreads.shutdownNow();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  @DisplayName("a hung backend with an exhausted pool fails every caller within the timeouts")
  void hungBackendDoesNotQueueCallers() throws Exception {
    int callers = OutboundHttpClients.MAX_CONNECTIONS_PER_ROUTE + 10;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    long started = System.nanoTime();
    try (CloseableHttpClient client = OutboundHttpClients.create(SHORT, true)) {
      List<Future<?>> calls = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        calls.add(
            pool.submit(
                () -> {
                  assertThatThrownBy(
                          () -> client.execute(new HttpGet(url("/hang")), r -> r.getCode()))
                      .isInstanceOf(IOException.class);
                  return null;
                }));
      }
      for (Future<?> call : calls) {
        call.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    // Read timeout (0.5s) for the leased connections, lease timeout (0.5s) for the rest — far below
    // the 3-minute pool wait an unconfigured client would impose.
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a response body beyond the cap fails instead of being read into memory")
  void oversizedBodyIsRefused() throws IOException {
    try (CloseableHttpClient client = OutboundHttpClients.create(SHORT, true)) {
      assertThatThrownBy(
              () ->
                  client.execute(
                      new HttpGet(url("/big")), r -> EntityUtils.toByteArray(r.getEntity())))
          .isInstanceOf(OutboundHttpClients.ResponseTooLargeException.class);
      assertThatThrownBy(
              () ->
                  client.execute(
                      new HttpGet(url("/declared-big")),
                      r -> EntityUtils.toByteArray(r.getEntity())))
          .isInstanceOf(OutboundHttpClients.ResponseTooLargeException.class);
    }
  }

  @Test
  @DisplayName("a compressed body is passed through as sent, never inflated")
  void compressedBodyIsNotInflated() throws IOException {
    try (CloseableHttpClient client = OutboundHttpClients.create(SHORT, true)) {
      byte[] body =
          client.execute(new HttpGet(url("/gzip")), r -> EntityUtils.toByteArray(r.getEntity()));
      assertThat(body.length).isLessThan(OutboundHttpClients.MAX_RESPONSE_BYTES);
    }
  }
}

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal forward-proxy stub for tests: it records each request line and refuses every tunnel
 * with a 403, so the {@code CONNECT} path of proxy mode (issue #940) can be observed without TLS.
 */
public final class ConnectRecordingProxy implements AutoCloseable {

  private final ServerSocket socket;
  private final List<String> lines = new CopyOnWriteArrayList<>();

  public ConnectRecordingProxy() throws IOException {
    socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor = new Thread(this::serve, "connect-recorder");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public int port() {
    return socket.getLocalPort();
  }

  /** The first line of every request received, in order. */
  public List<String> requestLines() {
    return lines;
  }

  private void serve() {
    while (!socket.isClosed()) {
      try (Socket connection = socket.accept()) {
        String head = readHead(connection.getInputStream());
        lines.add(head.lines().findFirst().orElse(""));
        OutputStream out = connection.getOutputStream();
        out.write(
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
      } catch (IOException closed) {
        return;
      }
    }
  }

  private static String readHead(InputStream in) throws IOException {
    StringBuilder head = new StringBuilder();
    int b;
    while ((b = in.read()) >= 0) {
      head.append((char) b);
      if (head.toString().endsWith("\r\n\r\n")) {
        break;
      }
    }
    return head.toString();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}

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
package io.qnop.observability;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * The diagnostic context every log line carries (issue #659, ADR-0054).
 *
 * <p>Logs answer "what happened to this user, on this review, in this request". Threading those ids
 * through every signature would be unbearable, so they live in the MDC and the pattern prints them.
 *
 * <p><strong>Only ids.</strong> Never a name, an address, a review title, a file name or annotation
 * text. A UUID is still personal data, but it is pseudonymous: resolving it takes database access
 * someone has to be entitled to, and a log file that leaks does not read as a list of people. Free
 * text cannot offer that, because whatever a user typed is whatever a user typed.
 *
 * <p><strong>Always scoped.</strong> Threads are pooled: a value left behind reappears under the
 * next request, which is a different person. That is not untidiness — it is a false attribution in
 * exactly the record someone later reads as evidence. Hence {@link #scope}: the value is removed by
 * closing, and try-with-resources makes forgetting hard rather than merely discouraged.
 */
public final class LogContext {

  /** One request, minted at the edge and echoed to the client so a user can quote it. */
  public static final String REQUEST_ID = "requestId";

  /** The authenticated caller, or absent for anonymous traffic. */
  public static final String USER_ID = "userId";

  /** The HTTP verb and the path — never the query string, which carries what a user typed. */
  public static final String METHOD = "method";

  public static final String PATH = "path";

  /** The review being acted on — the thread that ties a request to the jobs it triggered. */
  public static final String DOCUMENT_ID = "documentId";

  /** The queued job being run, and its type. */
  public static final String JOB_ID = "jobId";

  public static final String JOB_TYPE = "jobType";

  private LogContext() {}

  /**
   * Puts a value in the context until the returned scope is closed.
   *
   * <p>Restores whatever was there before rather than blindly clearing: nesting happens (a job
   * handler inside a request in tests, a document scope inside another), and clearing would leave
   * the outer scope's lines unattributed for the rest of its life.
   *
   * <p>A null value is not an error and not a blank entry — the key simply stays absent, so an
   * anonymous request prints no user rather than the word "null".
   */
  public static Scope scope(String key, Object value) {
    String previous = MDC.get(key);
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value.toString());
    }
    return () -> {
      if (previous == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, previous);
      }
    };
  }

  /** The review a piece of work concerns, for as long as the scope is open. */
  public static Scope document(UUID documentId) {
    return scope(DOCUMENT_ID, documentId);
  }

  /** What the context currently holds for a key, or null. Mainly for tests. */
  public static String get(String key) {
    return MDC.get(key);
  }

  /**
   * A context entry that ends when it is closed.
   *
   * <p>{@link AutoCloseable} without a checked exception, so it reads as {@code try (var ignored =
   * LogContext.document(id))} and never forces a catch nobody wants.
   */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}

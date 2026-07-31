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

import java.lang.reflect.Array;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verbose tracing of the service layer, on at DEBUG and silent otherwise (issue #659, ADR-0054).
 *
 * <p>One pointcut rather than a log line in every method: hand-written entry and exit lines are
 * hundreds of edits that drift from the first refactor onwards, and they cost their noise at every
 * level rather than only where it is wanted.
 *
 * <p>Loggers are named {@code io.qnop.trace.<SimpleName>}, so tracing has its own switch — {@code
 * logging.level.io.qnop.trace=DEBUG} for everything, or one class when a single flow is in question
 * — without turning the rest of {@code io.qnop} to DEBUG at the same time.
 *
 * <p><strong>Arguments are described, not printed.</strong> Ids, numbers, booleans and enums are
 * safe and are exactly what makes a trace useful. A {@code String} is not: at this layer it is
 * almost always a review title, an address or a comment, and no whitelist of parameter names
 * survives a rename. So a string is rendered as its length, and any other object as its type. That
 * keeps the DEBUG log as free of personal data as the INFO log — a file on disk does not become
 * less readable because a level was raised to produce it.
 */
@Aspect
@Component
public class MethodTraceAspect {

  private static final String TRACE_LOGGER_PREFIX = "io.qnop.trace.";

  /** One logger per traced class, resolved once rather than on every call. */
  private static final Map<Class<?>, Logger> LOGGERS = new ConcurrentHashMap<>();

  /**
   * Every public method of an {@code @Service} bean.
   *
   * <p>The service layer is where the decisions are; repositories are covered better by Hibernate's
   * own SQL logging, and the web layer by the request line.
   *
   * <p>Scoped to the stereotype rather than to the package, and not by preference:
   * {@code @ConfigurationProperties} records live in {@code io.qnop.service} too, a record is
   * final, and matching one makes Spring try to CGLIB-subclass it — which fails at startup rather
   * than degrading quietly. The stereotype names the beans whose behaviour is worth tracing anyway.
   */
  @Pointcut(
      "execution(public * io.qnop.service..*(..))"
          + " && @within(org.springframework.stereotype.Service)")
  void serviceMethods() {}

  @Around("serviceMethods()")
  public Object trace(ProceedingJoinPoint call) throws Throwable {
    Logger log = loggerFor(call.getTarget().getClass());
    if (!log.isDebugEnabled()) {
      // The common case in production: no formatting, no timing, nothing but the
      // proceed. Guarded rather than trusted to the logger, because building the
      // argument list is not free.
      return call.proceed();
    }

    String method = call.getSignature().getName();
    log.debug("→ {}({})", method, describeAll(call.getArgs()));
    long started = System.nanoTime();
    try {
      Object result = call.proceed();
      log.debug("← {} = {} [{} ms]", method, describe(result), millisSince(started));
      return result;
    } catch (Throwable failure) {
      // Only that it came out here, and how far it got. The failure itself is logged
      // where it is handled; repeating the trace at every frame is how a log becomes
      // unreadable.
      log.debug(
          "✗ {} threw {} [{} ms]",
          method,
          failure.getClass().getSimpleName(),
          millisSince(started));
      throw failure;
    }
  }

  private static Logger loggerFor(Class<?> target) {
    return LOGGERS.computeIfAbsent(
        target,
        type ->
            LoggerFactory.getLogger(
                TRACE_LOGGER_PREFIX + type.getSimpleName().replace("$$SpringCGLIB$$0", "")));
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static String describeAll(Object[] arguments) {
    StringBuilder rendered = new StringBuilder();
    for (int index = 0; index < arguments.length; index++) {
      if (index > 0) {
        rendered.append(", ");
      }
      rendered.append(describe(arguments[index]));
    }
    return rendered.toString();
  }

  /**
   * What a value may say about itself.
   *
   * <p>The list is deliberately short: everything not on it is described by type and size only.
   * Erring towards "type only" costs a little detail in a debug session; erring the other way puts
   * whatever a user typed into a file with no access control.
   */
  private static String describe(Object value) {
    return switch (value) {
      case null -> "null";
      case UUID id -> id.toString();
      case Number number -> number.toString();
      case Boolean flag -> flag.toString();
      case Enum<?> constant -> constant.name();
      case Temporal instant -> instant.toString();
      // Length, never content: at this layer a String is a title, an address or a
      // comment far more often than it is an identifier.
      case CharSequence text -> "String(len=" + text.length() + ")";
      case Optional<?> maybe ->
          maybe.map(inner -> "Optional[" + describe(inner) + "]").orElse("Optional.empty");
      case Collection<?> items -> items.getClass().getSimpleName() + "(" + items.size() + ")";
      case Map<?, ?> entries -> "Map(" + entries.size() + ")";
      default -> {
        if (value.getClass().isArray()) {
          yield value.getClass().getSimpleName() + "(" + Array.getLength(value) + ")";
        }
        yield value.getClass().getSimpleName();
      }
    };
  }
}

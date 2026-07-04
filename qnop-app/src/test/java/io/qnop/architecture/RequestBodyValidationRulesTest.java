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
package io.qnop.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Guards that every request body on the generated REST contract carries Bean Validation (issue
 * #346). The controllers implement the openapi-generator {@code spring} interfaces (ADR-0021) and
 * do not redeclare parameter annotations, so enforcement of the DTO constraints hinges entirely on
 * the generator emitting {@code @Valid} next to each {@code @RequestBody} — which it does while
 * {@code useBeanValidation=true} in {@code qnop-api-endpoint}'s generator config. Spring MVC
 * honours those interface-declared parameter annotations, so a missing {@code @Valid} would
 * silently stop validating request bodies (a {@code MethodArgumentNotValidException} would never
 * fire, and the {@code VALIDATION_ERROR} envelope never surface).
 *
 * <p>The runtime behaviour is exercised by {@code ErrorEnvelopeIT} (an empty {@code POST
 * /auth/login} body → 400 {@code VALIDATION_ERROR}); this test is the static counterpart that fails
 * fast — without a Spring context or a database — if a config or generator change ever drops the
 * annotation.
 */
class RequestBodyValidationRulesTest {

  /** The generated Spring MVC interfaces the controllers implement (ADR-0021). */
  private static final String ENDPOINT_PACKAGE = "io.qnop.api.v1.endpoint";

  private static final JavaClasses ENDPOINT_APIS =
      new ClassFileImporter().importPackages(ENDPOINT_PACKAGE);

  @Test
  void everyRequestBodyParameterIsValidated() {
    List<String> requestBodies = new ArrayList<>();
    List<String> unvalidated = new ArrayList<>();

    for (JavaClass api : ENDPOINT_APIS) {
      for (JavaMethod method : api.getMethods()) {
        for (JavaParameter parameter : method.getParameters()) {
          if (!parameter.isAnnotatedWith(RequestBody.class)) {
            continue;
          }
          String location = api.getSimpleName() + "#" + method.getName();
          requestBodies.add(location);
          if (!parameter.isAnnotatedWith(Valid.class)) {
            unvalidated.add(location);
          }
        }
      }
    }

    // Fail loudly if the scan found nothing — a wrong package or an empty generation
    // output would otherwise let this rule pass vacuously.
    assertFalse(
        requestBodies.isEmpty(),
        "No @RequestBody parameters found in "
            + ENDPOINT_PACKAGE
            + "; the generated endpoint contract is missing from the classpath.");

    assertTrue(
        unvalidated.isEmpty(),
        "Every @RequestBody must also be @Valid so request-DTO constraints are enforced (#346). "
            + "Missing @Valid on: "
            + unvalidated);
  }
}

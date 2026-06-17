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

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * Enforces the layered architecture (ADR-0004): web → service → repository → entity, with two
 * published, framework-free contracts (qnop-spi, qnop-api). Controllers never touch repositories
 * directly; entities never leak to the web layer (the service maps them to API DTOs).
 *
 * <p>In Phase 0 the modules contain only {@code package-info} placeholders, so these rules pass
 * trivially. They become meaningful as production code lands in Phase 1 — the harness is in place
 * from day one.
 */
class ArchitectureRulesTest {

  private static final JavaClasses QNOP_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.qnop");

  @Test
  void layeredArchitectureIsRespected() {
    ArchRule rule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Spi")
            .definedBy("io.qnop.spi..")
            .layer("Api")
            .definedBy("io.qnop.api..")
            .layer("Entity")
            .definedBy("io.qnop.entity..")
            .layer("Repository")
            .definedBy("io.qnop.repository..")
            .layer("Service")
            .definedBy("io.qnop.service..")
            .layer("Web")
            .definedBy("io.qnop.web..", "io.qnop.bootstrap..")
            .layer("Security")
            .definedBy("io.qnop.security..")
            // Spi is intentionally consumable by everyone (plugin contract).
            .whereLayer("Web")
            .mayNotBeAccessedByAnyLayer()
            // The security/crypto foundation (ADR-0022) is used by the service and web
            // layers; it never depends back on them (see securityFoundationStaysPure).
            .whereLayer("Security")
            .mayOnlyBeAccessedByLayers("Web", "Service")
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity")
            .mayOnlyBeAccessedByLayers("Repository", "Service")
            // The published REST contract is used by the service (mapping) and web layers.
            .whereLayer("Api")
            .mayOnlyBeAccessedByLayers("Service", "Web")
            // Some access checks here are legitimately empty (e.g. Web is accessed by no
            // layer), so keep the allowance on the layered rule; the purity rules below no
            // longer need it now that their packages are populated (issue #50, L-8).
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }

  @Test
  void pluginContractStaysPure() {
    // qnop-spi is the published plugin contract (ADR-0003): pure interfaces, free
    // of framework and internal dependencies so commercial add-ons can implement
    // it without linking the AGPL application.
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.qnop.spi..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "io.qnop.api..",
                "io.qnop.entity..",
                "io.qnop.repository..",
                "io.qnop.service..",
                "io.qnop.web..")
            // qnop-spi is still package-info only in Community; keep the empty-should
            // allowance here until the first published SPI interface lands.
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }

  @Test
  void restApiModelStaysPure() {
    // qnop-api-model is the published REST DTO surface (ADR-0015, ADR-0021):
    // generated POJOs only, free of Spring and internal-module dependencies so
    // external consumers (and a generated TS/SDK client) can depend on it without
    // pulling the server. The Spring MVC *interfaces* live in qnop-api-endpoint
    // (io.qnop.api.v1.endpoint, NOT ...model) and intentionally depend on Spring —
    // implemented by the web layer, so only the model package is held to purity.
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.qnop.api.v1.model..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "io.qnop.spi..",
                "io.qnop.entity..",
                "io.qnop.repository..",
                "io.qnop.service..",
                "io.qnop.web..");

    rule.check(QNOP_CLASSES);
  }

  @Test
  void securityFoundationStaysPure() {
    // io.qnop.security (qnop-core) is the framework-light crypto foundation (ADR-0022):
    // it may use Spring, but must never depend on the application's own web, service,
    // repository, entity or DTO layers — those depend on it, not the other way round.
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.qnop.security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "io.qnop.web..",
                "io.qnop.service..",
                "io.qnop.repository..",
                "io.qnop.entity..",
                "io.qnop.api..");

    rule.check(QNOP_CLASSES);
  }
}

// SPDX-License-Identifier: AGPL-3.0-only

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
            // Spi is intentionally consumable by everyone (plugin contract).
            .whereLayer("Web")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Entity")
            .mayOnlyBeAccessedByLayers("Repository", "Service")
            // The published REST contract is used by the service (mapping) and web layers.
            .whereLayer("Api")
            .mayOnlyBeAccessedByLayers("Service", "Web")
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
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }

  @Test
  void restApiContractStaysPure() {
    // qnop-api is the published REST contract (ADR-0015): DTOs + OpenAPI only,
    // free of framework and internal-module dependencies so external consumers
    // can depend on it without pulling the server.
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.qnop.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "io.qnop.spi..",
                "io.qnop.entity..",
                "io.qnop.repository..",
                "io.qnop.service..",
                "io.qnop.web..")
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }
}

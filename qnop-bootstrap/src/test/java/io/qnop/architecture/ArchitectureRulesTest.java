// SPDX-License-Identifier: AGPL-3.0-only

package io.qnop.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Enforces the hexagonal module boundaries (ADR-0004). The SPI is consumable by everyone; the
 * domain stays framework-free; adapters may only be reached from the composition root.
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
            .layer("SPI")
            .definedBy("io.qnop.spi..")
            .layer("Domain")
            .definedBy("io.qnop.domain..")
            .layer("Application")
            .definedBy("io.qnop.application..")
            .layer("Adapters")
            .definedBy(
                "io.qnop.persistence..",
                "io.qnop.storage..",
                "io.qnop.document..",
                "io.qnop.security..",
                "io.qnop.web..")
            .layer("Bootstrap")
            .definedBy("io.qnop.bootstrap..")
            // SPI is intentionally accessible from every layer.
            .whereLayer("Bootstrap")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapters")
            .mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapters", "Bootstrap")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapters", "Bootstrap")
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }

  @Test
  void domainStaysFrameworkFree() {
    ArchRule rule =
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("io.qnop.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "io.qnop.persistence..",
                "io.qnop.web..")
            .allowEmptyShould(true);

    rule.check(QNOP_CLASSES);
  }
}

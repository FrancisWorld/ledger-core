package io.github.francisworld.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/** The hexagonal boundaries are build-breaking rules, not conventions. */
@AnalyzeClasses(
    packages = "io.github.francisworld.ledger",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String DOMAIN = "io.github.francisworld.ledger.domain..";
  private static final String APPLICATION = "io.github.francisworld.ledger.application..";
  private static final String ADAPTER_IN = "io.github.francisworld.ledger.adapter.in..";
  private static final String ADAPTER_OUT = "io.github.francisworld.ledger.adapter.out..";
  private static final String BOOTSTRAP = "io.github.francisworld.ledger.bootstrap..";

  @ArchTest
  static final ArchRule LAYERS_ONLY_DEPEND_INWARD =
      Architectures.layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Inbound adapters")
          .definedBy(ADAPTER_IN)
          .layer("Outbound adapters")
          .definedBy(ADAPTER_OUT)
          .layer("Bootstrap")
          .definedBy(BOOTSTRAP)
          .whereLayer("Bootstrap")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Inbound adapters")
          .mayOnlyBeAccessedByLayers("Bootstrap")
          .whereLayer("Outbound adapters")
          .mayOnlyBeAccessedByLayers("Bootstrap")
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Inbound adapters", "Outbound adapters", "Bootstrap")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers(
              "Application", "Inbound adapters", "Outbound adapters", "Bootstrap");

  @ArchTest
  static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "tools.jackson..",
              "com.fasterxml..",
              "java.sql..",
              "javax.sql..",
              "org.slf4j..")
          .because("the domain is plain Java that can be tested without any infrastructure");

  @ArchTest
  static final ArchRule APPLICATION_IS_INFRASTRUCTURE_FREE =
      noClasses()
          .that()
          .resideInAPackage(APPLICATION)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "tools.jackson..",
              "com.fasterxml..",
              "java.sql..",
              "javax.sql..",
              "org.postgresql..",
              "software.amazon..",
              "io.micrometer..")
          .because("the application talks to the world only through ports");

  @ArchTest
  static final ArchRule WEB_LAYER_KNOWS_NO_SQL =
      noClasses()
          .that()
          .resideInAPackage(ADAPTER_IN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.postgresql..");

  @ArchTest
  static final ArchRule PERSISTENCE_STAYS_IN_ADAPTER =
      classes()
          .that()
          .haveSimpleNameStartingWith("Jdbc")
          .should()
          .resideInAPackage("io.github.francisworld.ledger.adapter.out.persistence")
          .andShould()
          .notBePublic();

  @ArchTest
  static final ArchRule WEB_LAYER_ONLY_USES_USE_CASES =
      noClasses()
          .that()
          .resideInAPackage(ADAPTER_IN)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("io.github.francisworld.ledger.application.port.out..");

  @ArchTest
  static final ArchRule EVERY_ENDPOINT_DECLARES_A_SCOPE =
      methods()
          .that()
          .areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
          .or()
          .areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
          .should()
          .beAnnotatedWith("io.github.francisworld.ledger.adapter.in.web.RequiresScope")
          .because("endpoints are closed by default and each names the API-key scope it needs");
}

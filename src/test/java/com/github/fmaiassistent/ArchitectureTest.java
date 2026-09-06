package com.github.fmaiassistent;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses APPLICATION_CLASSES = new ClassFileImporter()
            .importPackages("com.github.fmaiassistent");

    @Test
    void nonWebCodeDoesNotDependOnVaadinOrWebAdapters() {
        noClasses()
                .that().haveNameNotMatching(".*__.*")
                .and().resideOutsideOfPackage("..web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.github.fmaiassistent.web..", "com.vaadin..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void domainModelDoesNotDependOnInfrastructureOrDeliveryLayers() {
        noClasses()
                .that().haveNameNotMatching(".*__.*")
                .and().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository..", "..service..", "..mcp..", "..web..", "..linux..", "..windows..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void nativeExportersDoNotDependOnPersistenceOrDeliveryLayers() {
        noClasses()
                .that().haveNameNotMatching(".*__.*")
                .and().resideInAPackage("..exporter..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository..", "..service..", "..mcp..", "..web..")
                .check(APPLICATION_CLASSES);
    }
}

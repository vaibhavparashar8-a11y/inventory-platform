package com.inventoryplatform.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * platform-common polices itself too.
 *
 * <p>The rule that matters here is the one about business types: the moment a domain concept
 * appears in this module, every service is coupled through the back door, and that coupling is
 * invisible in review because it looks like sharing useful code (BUILD_PROMPT.md §4).
 */
@AnalyzeClasses(packages = "com.inventoryplatform.common")
class PlatformCommonArchitectureTest {

    /**
     * Money is the one deliberate exception — a value type, not a business rule — so the check is
     * expressed as a ban on the concrete domain concepts that would signal drift.
     */
    @ArchTest
    static final ArchRule infrastructureOnly =
            noClasses()
                    .that()
                    .resideInAPackage("com.inventoryplatform.common..")
                    .should()
                    .haveSimpleNameContaining("Variant")
                    .orShould()
                    .haveSimpleNameContaining("Sale")
                    .orShould()
                    .haveSimpleNameContaining("Purchase")
                    .orShould()
                    .haveSimpleNameContaining("StockBalance")
                    .orShould()
                    .haveSimpleNameContaining("Firm")
                    .because(
                            "platform-common is infrastructure only; a business type here couples every "
                                    + "service through the back door");

    /** Infrastructure must not depend on any service. Dependencies point inward, always. */
    @ArchTest
    static final ArchRule doesNotDependOnServices =
            noClasses()
                    .that()
                    .resideInAPackage("com.inventoryplatform.common..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.inventoryplatform.catalog..",
                            "com.inventoryplatform.stock..",
                            "com.inventoryplatform.purchase..",
                            "com.inventoryplatform.sales..",
                            "com.inventoryplatform.returns..",
                            "com.inventoryplatform.channel..",
                            "com.inventoryplatform.reporting..",
                            "com.inventoryplatform.gateway..")
                    .because("shared infrastructure must never know about the services that use it");
}

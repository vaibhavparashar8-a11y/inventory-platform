package com.inventoryplatform.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rules every service is held to, published in the test-jar so each service applies
 * the same ones rather than drifting into its own interpretation.
 *
 * <p>BUILD_PROMPT.md §4 requires domain purity to be enforced by a test in every service. These
 * rules are that enforcement: reviews miss a stray {@code @Entity} on a domain class, a build does
 * not.
 *
 * <p>Usage — one test class per service:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.inventoryplatform.stock")
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule domainIsPure = ArchitectureRules.domainIsFrameworkFree();
 * }
 * }</pre>
 */
public final class ArchitectureRules {

    private ArchitectureRules() {}

    /**
     * The domain layer must contain plain Java only.
     *
     * <p>Not stylistic. A domain that imports Spring or JPA cannot be unit-tested without a context,
     * drifts towards persistence-shaped rather than business-shaped models, and quietly couples
     * business rules to a framework upgrade cycle.
     */
    public static ArchRule domainIsFrameworkFree() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..",
                        "org.hibernate..")
                .because(
                        "the domain must be plain Java: testable without a context and independent of "
                                + "any framework's upgrade cycle");
    }

    /** Controllers hold no business logic, so they must not reach past the application layer. */
    public static ArchRule apiDoesNotReachIntoPersistence() {
        return noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter.persistence..", "jakarta.persistence..")
                .because(
                        "controllers must not touch persistence directly; that is the application "
                                + "layer's job, and DTOs exist so entities never cross an API boundary");
    }

    /**
     * Money is never a raw {@link java.math.BigDecimal} in the domain.
     *
     * <p>The scale and rounding rules only hold if every amount goes through {@code Money} or {@code
     * UnitCost}; a bare BigDecimal is how a 2-decimal price and a 4-decimal cost get silently mixed.
     */
    public static ArchRule domainUsesMoneyTypes() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.math.BigDecimal")
                .because(
                        "domain amounts must use Money or UnitCost so scale and rounding cannot "
                                + "disagree between services");
    }

    /**
     * Only {@code stock-service} may own stock quantity.
     *
     * <p>The single most important rule in the system (§3), and the easiest to erode by accident —
     * a helpfully-cached {@code onHand} column in another service looks harmless in review.
     *
     * @param servicePackage the service being checked, e.g. {@code com.inventoryplatform.sales}
     */
    public static ArchRule onlyStockServiceOwnsQuantity(String servicePackage) {
        return noClasses()
                .that()
                .resideInAPackage(servicePackage + "..")
                .should()
                .haveSimpleNameEndingWith("StockBalance")
                .orShould()
                .haveSimpleNameEndingWith("StockLedger")
                .because(
                        "stock-service is the sole writer of quantity; no other service may hold a "
                                + "stock table or a quantity column (BUILD_PROMPT.md §3)");
    }

    /** Convenience for services that want every shared rule applied at once. */
    public static void assertAllRules(JavaClasses classes) {
        domainIsFrameworkFree().check(classes);
        apiDoesNotReachIntoPersistence().check(classes);
        domainUsesMoneyTypes().check(classes);
    }
}

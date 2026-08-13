package com.inventoryplatform.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * Proves the shared rules actually fire.
 *
 * <p>An architecture rule that matches nothing passes for the wrong reason. Every service is about
 * to depend on these rules to keep its domain pure, so before that happens they are pointed at
 * deliberately-violating fixtures: a rule that cannot fail is worse than no rule, because it reads
 * as enforcement while enforcing nothing.
 *
 * @see com.inventoryplatform.common.archunit.fixture.domain.ImpureDomainClass
 */
class ArchitectureRulesSelfTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("com.inventoryplatform.common.archunit.fixture");

    @Test
    @DisplayName("domain purity rule rejects a domain class that imports Spring")
    void domainPurityRuleFires() {
        assertThatThrownBy(() -> ArchitectureRules.domainIsFrameworkFree().check(FIXTURES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ImpureDomainClass");
    }

    @Test
    @DisplayName("money rule rejects a raw BigDecimal in the domain")
    void moneyTypeRuleFires() {
        assertThatThrownBy(() -> ArchitectureRules.domainUsesMoneyTypes().check(FIXTURES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BigDecimal");
    }

    @Test
    @DisplayName("the same rules pass against a domain class that is genuinely pure")
    void rulesPassOnCleanCode() {
        JavaClasses cleanOnly =
                new ClassFileImporter()
                        .importPackages("com.inventoryplatform.common.archunit.fixture.clean");

        ArchitectureRules.domainIsFrameworkFree().check(cleanOnly);
        ArchitectureRules.domainUsesMoneyTypes().check(cleanOnly);

        assertThat(cleanOnly).isNotEmpty();
    }
}

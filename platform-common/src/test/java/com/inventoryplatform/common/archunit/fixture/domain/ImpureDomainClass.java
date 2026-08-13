package com.inventoryplatform.common.archunit.fixture.domain;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/**
 * Deliberately violates the domain rules, so ArchitectureRulesSelfTest can prove they fire.
 *
 * <p>Never imitate this. It exists to fail, and only inside a fixture package that production rules
 * do not scan.
 */
@Component
public class ImpureDomainClass {

    /** A raw amount: exactly the mistake domainUsesMoneyTypes() exists to catch. */
    private final BigDecimal price = BigDecimal.ONE;

    public BigDecimal price() {
        return price;
    }
}

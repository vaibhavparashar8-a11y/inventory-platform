package com.inventoryplatform.common.archunit.fixture.clean.domain;

import com.inventoryplatform.common.money.Money;

/** A domain class as it should be: plain Java, money through the value type. */
public final class PureDomainClass {

    private final Money price;

    public PureDomainClass(Money price) {
        this.price = price;
    }

    public Money price() {
        return price;
    }
}

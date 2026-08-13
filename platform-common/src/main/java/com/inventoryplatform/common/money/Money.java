package com.inventoryplatform.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A monetary amount: prices, fees, totals.
 *
 * <p>Canonical scale is {@value MoneyScales#MONEY_SCALE} with {@link RoundingMode#HALF_UP}, fixed
 * once here so no service can disagree (BUILD_PROMPT.md §9).
 *
 * <p><strong>Single currency.</strong> The product is INR-only and there is deliberately no currency
 * field; multi-currency is out of scope and would be a schema change, not a refactor.
 *
 * <p><strong>On rounding.</strong> Addition, subtraction and multiplication by a whole quantity are
 * exact and never lose precision. Multiplication by a fraction (a commission rate, say) cannot be
 * exact, so it rounds — which is correct at a boundary but wrong mid-calculation. When several
 * fractional steps compose, stay in {@link BigDecimal} via {@link #amount()} and convert back with
 * {@link #of(BigDecimal)} once at the end. For unit costs use {@link UnitCost}, which carries more
 * precision for exactly this reason.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(MoneyScales.MONEY_SCALE));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount.setScale(MoneyScales.MONEY_SCALE, MoneyScales.ROUNDING));
    }

    public static Money of(String amount) {
        return of(new BigDecimal(Objects.requireNonNull(amount, "amount")));
    }

    public static Money of(long amount) {
        return of(BigDecimal.valueOf(amount));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Exact: a whole-number multiplier cannot introduce precision loss. */
    public Money multiply(Quantity quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity.value())));
    }

    /** Rounds. Use only at a calculation boundary — see the class note on rounding. */
    public Money multiply(BigDecimal factor) {
        return of(amount.multiply(factor));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    /**
     * Value equality by amount. Safe because the scale is normalised on construction, so the {@code
     * BigDecimal.equals} scale trap (1.5 != 1.50) cannot arise.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && amount.equals(other.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}

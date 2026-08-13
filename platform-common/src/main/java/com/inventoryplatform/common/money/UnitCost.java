package com.inventoryplatform.common.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The cost of one unit of a variant, carried at {@value MoneyScales#COST_SCALE} decimals.
 *
 * <p>Separate from {@link Money} on purpose. A unit cost is a <em>derived</em> value — the weighted
 * average is recomputed on every receipt, each result feeding the next — so rounding it to 2
 * decimals compounds error into every margin the dashboard reports. Keeping it a distinct type also
 * makes it impossible to accidentally add a cost to a price.
 *
 * <p>Convert to {@link Money} only when extending a cost across a quantity for display or posting.
 */
public final class UnitCost implements Comparable<UnitCost> {

    public static final UnitCost ZERO = new UnitCost(BigDecimal.ZERO.setScale(MoneyScales.COST_SCALE));

    private final BigDecimal value;

    private UnitCost(BigDecimal value) {
        this.value = value;
    }

    public static UnitCost of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return new UnitCost(value.setScale(MoneyScales.COST_SCALE, MoneyScales.ROUNDING));
    }

    public static UnitCost of(String value) {
        return of(new BigDecimal(Objects.requireNonNull(value, "value")));
    }

    /**
     * Weighted average cost after receiving {@code incomingQty} units at {@code incomingCost}.
     *
     * <p>{@code (existingAvg * existingQty + incomingCost * incomingQty) / (existingQty +
     * incomingQty)}, evaluated at {@link MoneyScales#CALCULATION_SCALE} and rounded exactly once at
     * the end.
     *
     * @throws IllegalArgumentException if the resulting total quantity is not positive — an average
     *     over zero units is undefined, and returning zero there would silently report 100% margin
     */
    public static UnitCost weightedAverage(
            UnitCost existingAvg, Quantity existingQty, UnitCost incomingCost, Quantity incomingQty) {
        Objects.requireNonNull(existingAvg, "existingAvg");
        Objects.requireNonNull(incomingCost, "incomingCost");

        long totalQty = (long) existingQty.value() + incomingQty.value();
        if (totalQty <= 0) {
            throw new IllegalArgumentException(
                    "Weighted average cost is undefined for a total quantity of " + totalQty);
        }

        BigDecimal existingValue = existingAvg.value.multiply(BigDecimal.valueOf(existingQty.value()));
        BigDecimal incomingValue = incomingCost.value.multiply(BigDecimal.valueOf(incomingQty.value()));

        BigDecimal average =
                existingValue
                        .add(incomingValue)
                        .divide(
                                BigDecimal.valueOf(totalQty),
                                MoneyScales.CALCULATION_SCALE,
                                MoneyScales.ROUNDING);

        return of(average);
    }

    /** Extends this cost across a quantity, rounding once to money scale. */
    public Money extend(Quantity quantity) {
        return Money.of(value.multiply(BigDecimal.valueOf(quantity.value())));
    }

    public BigDecimal value() {
        return value;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public int compareTo(UnitCost other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnitCost other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}

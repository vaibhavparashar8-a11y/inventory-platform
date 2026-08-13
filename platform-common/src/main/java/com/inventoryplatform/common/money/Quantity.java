package com.inventoryplatform.common.money;

/**
 * A whole number of sellable units.
 *
 * <p>Stock is counted in indivisible units — half a shirt is not a thing — so this wraps an {@code
 * int} rather than a decimal. A fractional quantity is a modelling error, not a rounding problem.
 *
 * <p>Signed values are permitted because the ledger records signed deltas: a sale is negative, a
 * purchase positive. Use {@link #requirePositive()} where a caller must supply a real amount, such
 * as a reservation.
 *
 * @param value the signed unit count
 */
public record Quantity(int value) implements Comparable<Quantity> {

    public static final Quantity ZERO = new Quantity(0);

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public Quantity add(Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    public Quantity subtract(Quantity other) {
        return new Quantity(Math.subtractExact(value, other.value));
    }

    public Quantity negate() {
        return new Quantity(Math.negateExact(value));
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isNegative() {
        return value < 0;
    }

    public boolean isZero() {
        return value == 0;
    }

    /**
     * @throws IllegalArgumentException if this quantity is not strictly positive
     */
    public Quantity requirePositive() {
        if (value <= 0) {
            throw new IllegalArgumentException("Quantity must be positive but was " + value);
        }
        return this;
    }

    @Override
    public int compareTo(Quantity other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}

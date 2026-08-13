package com.inventoryplatform.common.money;

import java.math.RoundingMode;

/**
 * The scale and rounding rules for the whole platform, defined once (BUILD_PROMPT.md §9).
 *
 * <p>The two scales are not interchangeable. Prices settle in currency the customer actually pays,
 * so they are held at 2 decimals. Unit costs are derived — a weighted average recomputed on every
 * receipt — and rounding those to 2 decimals accumulates error across thousands of receipts, which
 * quietly corrupts every margin number downstream. Hence 4.
 */
public final class MoneyScales {

    /** Prices, fees, totals — anything the customer pays or receives. */
    public static final int MONEY_SCALE = 2;

    /** Unit costs and weighted averages — derived values that compound. */
    public static final int COST_SCALE = 4;

    /**
     * Working scale for intermediate arithmetic. Deliberately well above {@link #COST_SCALE} so an
     * intermediate step never decides the final rounded digit.
     */
    public static final int CALCULATION_SCALE = 10;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyScales() {}
}

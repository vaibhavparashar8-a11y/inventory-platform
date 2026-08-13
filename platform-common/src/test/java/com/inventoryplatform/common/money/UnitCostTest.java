package com.inventoryplatform.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Weighted average cost is one of the invariants BUILD_PROMPT.md §9 requires covering. The
 * precision tests below are the point of the class: they are what stops rounding error leaking into
 * every margin figure on the dashboard.
 */
class UnitCostTest {

    @Test
    void carriesFourDecimals() {
        assertThat(UnitCost.of("12.3").value()).isEqualTo(new BigDecimal("12.3000"));
        assertThat(UnitCost.of("12.34567").value()).isEqualTo(new BigDecimal("12.3457"));
    }

    @Test
    @DisplayName("first receipt into empty stock takes the incoming cost")
    void firstReceiptSetsTheAverage() {
        UnitCost result =
                UnitCost.weightedAverage(UnitCost.ZERO, Quantity.ZERO, UnitCost.of("100"), Quantity.of(10));

        assertThat(result).isEqualTo(UnitCost.of("100.0000"));
    }

    @Test
    void blendsTwoReceiptsByQuantity() {
        // 10 @ 100 then 10 @ 200 -> 150
        UnitCost result =
                UnitCost.weightedAverage(
                        UnitCost.of("100"), Quantity.of(10), UnitCost.of("200"), Quantity.of(10));

        assertThat(result).isEqualTo(UnitCost.of("150.0000"));
    }

    @Test
    void weightsTowardsTheLargerReceipt() {
        // 90 @ 10 then 10 @ 20 -> 11
        UnitCost result =
                UnitCost.weightedAverage(
                        UnitCost.of("10"), Quantity.of(90), UnitCost.of("20"), Quantity.of(10));

        assertThat(result).isEqualTo(UnitCost.of("11.0000"));
    }

    @Test
    @DisplayName("a non-terminating division does not blow up, it rounds once")
    void handlesRecurringDecimals() {
        // 1 @ 10 then 2 @ 20 -> 50/3 = 16.666...
        UnitCost result =
                UnitCost.weightedAverage(
                        UnitCost.of("10"), Quantity.of(1), UnitCost.of("20"), Quantity.of(2));

        assertThat(result).isEqualTo(UnitCost.of("16.6667"));
    }

    @Test
    @DisplayName("error does not accumulate across many small receipts")
    void staysAccurateOverManyReceipts() {
        UnitCost avg = UnitCost.of("10");
        Quantity held = Quantity.of(1);

        // A hundred single-unit receipts at a recurring-decimal price.
        for (int i = 0; i < 100; i++) {
            avg = UnitCost.weightedAverage(avg, held, UnitCost.of("10.3333"), Quantity.of(1));
            held = held.add(Quantity.of(1));
        }

        // True value converges towards 10.3333; at 4dp it must stay within a paisa.
        assertThat(avg.value())
                .isCloseTo(new BigDecimal("10.3300"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("an average over zero units is rejected, never silently zero")
    void rejectsUndefinedAverage() {
        assertThatThrownBy(
                        () ->
                                UnitCost.weightedAverage(
                                        UnitCost.ZERO, Quantity.ZERO, UnitCost.of("100"), Quantity.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undefined");
    }

    @Test
    void extendRoundsOnceToMoneyScale() {
        assertThat(UnitCost.of("16.6667").extend(Quantity.of(3))).isEqualTo(Money.of("50.00"));
    }
}

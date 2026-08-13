package com.inventoryplatform.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    @DisplayName("scale")
    class Scale {

        @Test
        void normalisesToTwoDecimalsOnConstruction() {
            assertThat(Money.of("10").amount()).isEqualTo(new BigDecimal("10.00"));
            assertThat(Money.of("10.5").amount()).isEqualTo(new BigDecimal("10.50"));
        }

        @Test
        void roundsHalfUp() {
            assertThat(Money.of("10.005").amount()).isEqualTo(new BigDecimal("10.01"));
            assertThat(Money.of("10.004").amount()).isEqualTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("equality ignores the BigDecimal scale trap: 1.5 equals 1.50")
        void equalityIsByValue() {
            assertThat(Money.of("1.5")).isEqualTo(Money.of("1.50"));
            assertThat(Money.of("1.5")).hasSameHashCodeAs(Money.of("1.50"));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void addAndSubtractAreExact() {
            assertThat(Money.of("10.10").add(Money.of("0.20"))).isEqualTo(Money.of("10.30"));
            assertThat(Money.of("10.10").subtract(Money.of("0.20"))).isEqualTo(Money.of("9.90"));
        }

        @Test
        @DisplayName("0.1 + 0.2 is exactly 0.30 — the reason double is banned")
        void avoidsBinaryFloatingPointError() {
            assertThat(Money.of("0.1").add(Money.of("0.2"))).isEqualTo(Money.of("0.30"));
            assertThat(0.1 + 0.2).isNotEqualTo(0.3);
        }

        @Test
        void multiplyByWholeQuantityIsExact() {
            assertThat(Money.of("19.99").multiply(Quantity.of(3))).isEqualTo(Money.of("59.97"));
        }

        @Test
        void negateFlipsSign() {
            assertThat(Money.of("5.00").negate()).isEqualTo(Money.of("-5.00"));
            assertThat(Money.of("5.00").negate().isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        void rejectsNullAmount() {
            assertThatThrownBy(() -> Money.of((BigDecimal) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void signChecksAreConsistent() {
            assertThat(Money.ZERO.isZero()).isTrue();
            assertThat(Money.ZERO.isPositive()).isFalse();
            assertThat(Money.ZERO.isNegative()).isFalse();
        }
    }
}

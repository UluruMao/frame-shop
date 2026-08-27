package com.iflytek.frame.common;

import com.iflytek.frame.entity.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyFlashPriceTest {

    @Test
    void nonFlashReturnsListPrice() {
        Product p = new Product();
        p.setPriceInCents(1999);
        p.setFlashSale(false);
        assertThat(Money.flashPriceInCents(p)).isEqualTo(1999);
    }

    @Test
    void flashAppliesDiscount() {
        Product p = new Product();
        p.setPriceInCents(10000);
        p.setFlashSale(true);
        p.setDiscountPercent(30); // 30% off → 7000
        assertThat(Money.flashPriceInCents(p)).isEqualTo(7000);
    }

    @Test
    void flashNullPercentTreatedAsNoDiscount() {
        Product p = new Product();
        p.setPriceInCents(10000);
        p.setFlashSale(true);
        p.setDiscountPercent(null);
        assertThat(Money.flashPriceInCents(p)).isEqualTo(10000);
    }
}

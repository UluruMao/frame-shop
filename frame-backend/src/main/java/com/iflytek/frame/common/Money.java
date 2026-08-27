package com.iflytek.frame.common;

import com.iflytek.frame.entity.Product;
import java.util.Map;

/**
 * Money / coin math. The single source of the cents↔coins↔yuan relationship:
 * 100 coins = ¥1 = 100 cents (1 coin : 1 cent). Promoted out of CoinServiceImpl
 * so CheckoutServiceImpl + StreamConsumerService share it instead of re-deriving.
 */
public final class Money {
    private Money() {}

    /** 100 coins == ¥1 == 100 cents. */
    public static final int COINS_PER_YUAN = 100;

    /** Sum price*quantity across a product map. Dedupes the loop in Checkout/Coin services. */
    public static int subtotalCents(Map<Long, Product> byId, Map<Long, Integer> qty) {
        int total = 0;
        for (Map.Entry<Long, Integer> e : qty.entrySet()) {
            Product p = byId.get(e.getKey());
            if (p != null) total += p.getPriceInCents() * e.getValue();
        }
        return total;
    }

    /** Effective price for a product: discounted when flash-sale is live, else list price.
     *  discountPercent null or 0 → no discount (returns list price even if flash). */
    public static int flashPriceInCents(Product p) {
        if (!p.isFlashSale() || p.getDiscountPercent() == null || p.getDiscountPercent() <= 0) {
            return p.getPriceInCents();
        }
        return p.getPriceInCents() * (100 - p.getDiscountPercent()) / 100;
    }

    /** Subtotal using the flash price for live-flash items, list price otherwise. */
    public static int subtotalCentsFlash(Map<Long, Product> byId, Map<Long, Integer> qty) {
        int total = 0;
        for (Map.Entry<Long, Integer> e : qty.entrySet()) {
            Product p = byId.get(e.getKey());
            if (p != null) total += flashPriceInCents(p) * e.getValue();
        }
        return total;
    }

    /** coins → cents (1:1). Named for symmetry, reads better than a bare cast. */
    public static int coinsToCents(int coins) {
        return coins;
    }

    /** cents → coins (1:1). */
    public static int centsToCoins(int cents) {
        return cents;
    }
}

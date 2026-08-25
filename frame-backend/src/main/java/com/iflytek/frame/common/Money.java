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

    /** coins → cents (1:1). Named for symmetry, reads better than a bare cast. */
    public static int coinsToCents(int coins) {
        return coins;
    }

    /** cents → coins (1:1). */
    public static int centsToCoins(int cents) {
        return cents;
    }
}

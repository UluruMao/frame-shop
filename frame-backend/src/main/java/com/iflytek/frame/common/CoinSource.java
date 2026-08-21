package com.iflytek.frame.common;

/** The reason a coin_ledger row was created (UC012). */
public enum CoinSource {
    DAILY_LOGIN,      // +100 daily-login reward
    REVIEW_REWARD,    // +order's paid amount, on review
    CHECKOUT_SPEND,   // -coins spent at checkout (one row per consumed credit)
    REFUND_RETURN,    // +coins returned on order refund (one row per spent credit)
    EXPIRED           // -balancing row for a credit past its 30-day clock
}

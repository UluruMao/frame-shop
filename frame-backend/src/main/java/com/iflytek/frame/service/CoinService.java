package com.iflytek.frame.service;

import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.common.PageResult;

import java.util.List;
import java.util.Map;

public interface CoinService {

    /** UC012 §1: award 100 coins on first login of the CST day. Idempotent. */
    void awardDailyLogin(Long userId);

    /** UC012 §2: credit the order's actual paid amount as coins. Idempotent (one per order). */
    void awardReviewReward(Long orderId, Long userId);

    /** UC005/UC012 §4: compute the max-allowed coin discount for a cart (validation only — no deduction). */
    CoinDiscountPreview previewCheckoutDiscount(Long userId, List<Product> products, Map<Long, Integer> qtyByProduct);

    /** UC005: deduct `coinAmount` coins FIFO for an order. Called inside the consumer's transaction. */
    void spendAtCheckout(Long userId, Long orderId, int coinAmount);

    /**
     * UC005: pre-check whether the user has enough FIFO-spendable coins to cover {@code coinAmount}
     * (a read — no transaction marking). Used by the checkout consumer BEFORE calling
     * {@link #spendAtCheckout}: if this returns false, the consumer sets the order FAILED + recredits
     * stock + returns WITHOUT calling {@code spendAtCheckout} (so no exception, no rollback-only
     * transaction mark on the shared consumer tx). {@code spendAtCheckout} still throws CONFLICT as
     * a last-line guard for the narrow race between {@code canSpend} and the spend, but the
     * pre-check eliminates the COMMON insufficient-balance case from the rollback-only path.
     */
    boolean canSpend(Long userId, int coinAmount);

    /** UC008/UC009: return the coins spent on an order (on refund). Idempotent (one per spent credit). */
    void refundCoins(Long orderId);

    /** UC012 §3: daily sweep of credits past their 30-day clock. Idempotent. */
    void sweepExpiredCredits();

    /** UC012 read: current balance + daily-claim status. */
    CoinBalanceVO getBalance(Long userId);

    /** UC012 read: paginated ledger. */
    PageResult<CoinHistoryVO> getHistory(Long userId, PageQuery query);
}

package com.iflytek.frame.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase-E seam for UC012 (优惠金币). UC012 says: "用户评价后可领取等同于本订单实际付款金额的优惠金币"
 * — after reviewing, the user may claim coins equal to the order's paid amount.
 *
 * <p>Phase D calls {@link #reviewSubmitted(Long, Long)} once per order when its last line item
 * receives its first review. This stub logs the intent; Phase E replaces the body with a coin
 * ledger credit. Wrapped in try/catch by the caller so a future Phase-E failure cannot break
 * the review write.
 */
@Service
@Slf4j
public class CoinServiceStub {

    public void reviewSubmitted(Long orderId, Long userId) {
        log.info("reviewSubmitted(orderId={}, userId={}) — coin credit is Phase E (UC012)", orderId, userId);
    }
}

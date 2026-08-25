package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.common.Money;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoinServiceImpl implements CoinService {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final int EXPIRY_DAYS = 30;

    private final CoinMapper coinMapper;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public void awardDailyLogin(Long userId) {
        LocalDate today = LocalDate.now(CST);
        if (coinMapper.selectDailyLogin(userId, today) != null) {
            return; // already claimed today
        }
        int balance = currentBalance(userId);
        CoinLedger row = new CoinLedger();
        row.setUserId(userId);
        row.setAmount(100);
        row.setBalanceAfter(balance + 100);
        row.setSource(CoinSource.DAILY_LOGIN);
        row.setLoginDate(today);
        row.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        try {
            coinMapper.insert(row);
        } catch (DataIntegrityViolationException e) {
            // two concurrent logins same instant — the other won; this is a no-op
            log.debug("awardDailyLogin({}) duplicate-key no-op: {}", userId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void awardReviewReward(Long orderId, Long userId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) {
            return; // ownership/missing — silently no-op (caller's try/catch isolates)
        }
        int rewardCoins = o.getPaidInCents() / Money.COINS_PER_YUAN; // yuan, floor
        if (rewardCoins <= 0) {
            log.debug("awardReviewReward({}) no-op: paidInCents={} → 0 coins", orderId, o.getPaidInCents());
            return;
        }
        int balance = currentBalance(userId);
        CoinLedger row = new CoinLedger();
        row.setUserId(userId);
        row.setAmount(rewardCoins);
        row.setBalanceAfter(balance + rewardCoins);
        row.setSource(CoinSource.REVIEW_REWARD);
        row.setRefOrderId(orderId);
        row.setIdemKey("review:" + orderId);
        row.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        try {
            coinMapper.insert(row);
        } catch (DataIntegrityViolationException e) {
            // concurrent double-fire (the Phase D flag) — idempotent no-op
            log.debug("awardReviewReward({}) duplicate-key no-op: {}", orderId, e.getMessage());
        }
    }

    @Override
    public CoinDiscountPreview previewCheckoutDiscount(Long userId, List<Product> products, Map<Long, Integer> qtyByProduct) {
        CoinDiscountPreview p = new CoinDiscountPreview();
        boolean hasFlash = products.stream().anyMatch(pr -> Boolean.TRUE.equals(pr.isFlashSale()));
        if (hasFlash) {
            p.setEligible(false);
            p.setMaxCoins(0);
            p.setBalance(currentBalance(userId));
            p.setUsable(0);
            p.setReason("coins cannot be used on flash-sale items");
            return p;
        }
        Map<Long, Product> byId = new java.util.HashMap<>();
        for (Product pr : products) {
            byId.put(pr.getId(), pr);
        }
        int subtotalCents = Money.subtotalCents(byId, qtyByProduct);
        int maxDiscountCents = subtotalCents / 2; // 50% cap
        int maxCoins = (maxDiscountCents / Money.COINS_PER_YUAN) * Money.COINS_PER_YUAN; // 100-coin granularity
        int balance = currentBalance(userId);
        // `usable` is what spendAtCheckout can actually deduct FIFO (sum of un-spent, un-expired
        // positive credits), NOT the balance SUM — the balance SUM counts refunded/nullified credits
        // (via their REFUND_RETURN row) that are not FIFO-spendable. Using the balance SUM here would
        // report more usable coins than a spend can consume, so the consumer's spendAtCheckout would
        // throw CONFLICT after partial writes, marking the consumer tx rollback-only (Task 6 e2e bug).
        int spendable = spendableBalance(userId);
        int usable = Math.min(maxCoins, (spendable / Money.COINS_PER_YUAN) * Money.COINS_PER_YUAN); // floored to 100
        p.setEligible(true);
        p.setMaxCoins(maxCoins);
        p.setBalance(balance);
        p.setUsable(usable);
        p.setReason(null);
        return p;
    }

    @Override
    @Transactional
    public void spendAtCheckout(Long userId, Long orderId, int coinAmount) {
        if (coinAmount <= 0) {
            return;
        }
        List<CoinLedger> credits = coinMapper.selectSpendableCreditsForUpdate(userId); // FOR UPDATE, FIFO
        int remaining = coinAmount;
        int balance = currentBalance(userId);
        for (CoinLedger credit : credits) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(credit.getAmount(), remaining);
            balance -= take;
            CoinLedger spend = new CoinLedger();
            spend.setUserId(userId);
            spend.setAmount(-take);
            spend.setBalanceAfter(balance);
            spend.setSource(CoinSource.CHECKOUT_SPEND);
            spend.setRefOrderId(orderId);
            spend.setRefCreditId(credit.getId());
            // Snapshot the credit's expiry BEFORE nullifying it, so refundCoins can restore the
            // original per-credit clock from the spend row (the credit's expires_at is nullified
            // below — without this snapshot, the refund would read null and the returned coins
            // would never be FIFO-spendable again).
            spend.setRefCreditExpiresAt(credit.getExpiresAt());
            spend.setExpiresAt(null);
            coinMapper.insert(spend);
            // nullify the consumed credit's expires_at (spent state)
            credit.setExpiresAt(null);
            coinMapper.updateById(credit);
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "insufficient coins");
        }
    }

    @Override
    public boolean canSpend(Long userId, int coinAmount) {
        // Read-only pre-check (no @Transactional → no rollback-only mark on the caller's tx).
        // Uses the FIFO-spendable sum (selectSpendableBalance), which matches what spendAtCheckout
        // can actually consume — NOT the balance SUM (which counts refunded/nullified credits via
        // their REFUND_RETURN row that are not FIFO-spendable). This eliminates the COMMON
        // insufficient-balance case from the consumer's rollback-only path; spendAtCheckout's
        // CONFLICT throw remains as the last-line guard for the narrow race.
        if (coinAmount <= 0) {
            return true;
        }
        return spendableBalance(userId) >= coinAmount;
    }

    @Override
    @Transactional
    public void refundCoins(Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) {
            return;
        }
        Long userId = o.getUserId();
        List<CoinLedger> spendRows = coinMapper.selectSpendRowsByOrder(userId, orderId);
        if (spendRows.isEmpty()) {
            return; // no coins were spent on this order
        }
        int balance = currentBalance(userId);
        for (CoinLedger spend : spendRows) {
            int returnAmount = -spend.getAmount(); // positive
            // Restore the original per-credit expiry from the snapshot on the CHECKOUT_SPEND row
            // (captured at spend time, BEFORE the credit's expires_at was nullified). Reading the
            // credit row now would yield null (it was nullified on spend) — the snapshot is the
            // source of truth for the original clock. A null snapshot (legacy pre-fix spend rows)
            // falls back to the credit row's current (also null) value → refunded coins stay
            // non-spendable, matching the pre-fix behavior for legacy rows.
            LocalDateTime restoredExpiry = spend.getRefCreditExpiresAt();
            if (restoredExpiry == null) {
                CoinLedger originalCredit = coinMapper.selectById(spend.getRefCreditId());
                restoredExpiry = originalCredit != null ? originalCredit.getExpiresAt() : null;
            }
            balance += returnAmount;
            CoinLedger refund = new CoinLedger();
            refund.setUserId(userId);
            refund.setAmount(returnAmount);
            refund.setBalanceAfter(balance);
            refund.setSource(CoinSource.REFUND_RETURN);
            refund.setRefOrderId(orderId);
            refund.setRefCreditId(spend.getRefCreditId());
            refund.setIdemKey("refund:" + orderId + ":" + spend.getId());
            refund.setExpiresAt(restoredExpiry); // restore original clock, not a fresh 30 days
            try {
                coinMapper.insert(refund);
            } catch (DataIntegrityViolationException e) {
                log.debug("refundCoins({}) duplicate-key no-op on spend row {}: {}", orderId, spend.getId(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void sweepExpiredCredits() {
        List<CoinLedger> expired = coinMapper.selectExpiredUnsweptCredits();
        for (CoinLedger credit : expired) {
            try {
                int balance = currentBalance(credit.getUserId());
                CoinLedger exp = new CoinLedger();
                exp.setUserId(credit.getUserId());
                exp.setAmount(-credit.getAmount());
                exp.setBalanceAfter(balance - credit.getAmount());
                exp.setSource(CoinSource.EXPIRED);
                exp.setRefCreditId(credit.getId());
                exp.setExpiresAt(null);
                coinMapper.insert(exp);
                credit.setExpiresAt(null); // nullify so it's not re-swept
                coinMapper.updateById(credit);
            } catch (Exception e) {
                log.error("sweepExpiredCredits failed for credit {}: {}", credit.getId(), e.getMessage());
            }
        }
    }

    @Override
    public CoinBalanceVO getBalance(Long userId) {
        CoinBalanceVO vo = new CoinBalanceVO();
        vo.setBalance(currentBalance(userId));
        vo.setClaimedToday(coinMapper.selectDailyLogin(userId, LocalDate.now(CST)) != null);
        vo.setExpiringSoon(0); // expiringSoon projection — see Step note below
        return vo;
    }

    @Override
    public PageResult<CoinHistoryVO> getHistory(Long userId, PageQuery query) {
        Page<CoinLedger> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<CoinLedger> result = coinMapper.selectHistory(page, userId);
        List<CoinHistoryVO> rows = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), rows);
    }

    private CoinHistoryVO toVO(CoinLedger c) {
        CoinHistoryVO vo = new CoinHistoryVO();
        vo.setId(c.getId());
        vo.setAmount(c.getAmount());
        vo.setSource(c.getSource().name());
        vo.setRefOrderId(c.getRefOrderId());
        vo.setExpiresAt(c.getExpiresAt());
        vo.setCreateTime(c.getCreateTime());
        vo.setBalanceAfter(c.getBalanceAfter());
        return vo;
    }

    private int currentBalance(Long userId) {
        Integer b = coinMapper.selectBalance(userId);
        return b != null ? b : 0;
    }

    private int spendableBalance(Long userId) {
        Integer b = coinMapper.selectSpendableBalance(userId);
        return b != null ? b : 0;
    }
}

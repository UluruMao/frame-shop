package com.iflytek.frame.service;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.impl.CoinServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoinServiceImplTest {

    @Mock CoinMapper coinMapper;
    @Mock OrderMapper orderMapper;
    CoinServiceImpl coinService;

    @BeforeEach
    void setUp() {
        coinService = new CoinServiceImpl(coinMapper, orderMapper);
    }

    private CoinLedger credit(int amount, LocalDateTime expiresAt) {
        CoinLedger c = new CoinLedger();
        c.setId((long) Math.abs(amount));
        c.setAmount(amount);
        c.setExpiresAt(expiresAt);
        return c;
    }

    @Test
    void awardDailyLogin_creditsOnce_whenNotClaimedToday() {
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(null);

        coinService.awardDailyLogin(1L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(100);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.DAILY_LOGIN);
        assertThat(cap.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void awardDailyLogin_noOp_whenAlreadyClaimedToday() {
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(new CoinLedger());
        coinService.awardDailyLogin(1L);
        verify(coinMapper, never()).insert(any(CoinLedger.class));
    }

    @Test
    void awardDailyLogin_noOp_onDuplicateKeyConcurrentLogin() {
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(null);
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any(CoinLedger.class));
        coinService.awardDailyLogin(1L); // must not throw
        verify(coinMapper).insert(any(CoinLedger.class));
    }

    @Test
    void awardReviewReward_creditsPaidAmountInYuan() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(2000); // ¥20.00 → 20 coins
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectBalance(1L)).thenReturn(0);

        coinService.awardReviewReward(7L, 1L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(20);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.REVIEW_REWARD);
        assertThat(cap.getValue().getIdemKey()).isEqualTo("review:7");
    }

    @Test
    void awardReviewReward_noOp_whenZeroPaidAmount() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(0);
        when(orderMapper.selectById(7L)).thenReturn(o);
        coinService.awardReviewReward(7L, 1L);
        verify(coinMapper, never()).insert(any(CoinLedger.class));
    }

    @Test
    void awardReviewReward_noOp_onDuplicateKeyConcurrentFire() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(1000);
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any(CoinLedger.class));
        coinService.awardReviewReward(7L, 1L); // must not throw (the Phase D flag)
        verify(coinMapper).insert(any(CoinLedger.class));
    }

    @Test
    void previewCheckoutDiscount_rejectsAllFlashCart() {
        Product flash = new Product();
        flash.setId(1L); flash.setPriceInCents(1000); flash.setFlashSale(true);
        CoinDiscountPreview p = coinService.previewCheckoutDiscount(1L, List.of(flash), Map.of(1L, 1));
        assertThat(p.getEligible()).isFalse();
        assertThat(p.getReason()).contains("flash");
    }

    @Test
    void previewCheckoutDiscount_mixedCartAppliesCoinsToNonFlashOnly() {
        // 1 flash (¥10) + 1 non-flash (¥20). Coins eligible; cap = min(balance, non-flash subtotal ¥20 = 2000c).
        Product flash = new Product();
        flash.setId(1L); flash.setPriceInCents(1000); flash.setFlashSale(true);
        Product regular = new Product();
        regular.setId(2L); regular.setPriceInCents(2000); regular.setFlashSale(false);
        when(coinMapper.selectSpendableBalance(1L)).thenReturn(5000); // balance exceeds non-flash subtotal
        CoinDiscountPreview r = coinService.previewCheckoutDiscount(1L, List.of(flash, regular), Map.of(1L, 1, 2L, 1));
        assertThat(r.getEligible()).isTrue();
        // non-flash subtotal = 2000c; spendable 5000 > 2000 → cap = 2000, floored to 100 → 2000.
        assertThat(r.getUsable()).isEqualTo(2000);
    }

    @Test
    void previewCheckoutDiscount_capsAtNonFlashSubtotalAndBalance() {
        Product p = new Product();
        p.setId(1L); p.setPriceInCents(2000); p.setFlashSale(false); // non-flash subtotal ¥20 = 2000c
        when(coinMapper.selectBalance(1L)).thenReturn(500); // balance 500 < 2000 cap
        when(coinMapper.selectSpendableBalance(1L)).thenReturn(500); // all 500 FIFO-spendable
        CoinDiscountPreview r = coinService.previewCheckoutDiscount(1L, List.of(p), Map.of(1L, 1));
        assertThat(r.getEligible()).isTrue();
        assertThat(r.getMaxCoins()).isEqualTo(500);
        assertThat(r.getUsable()).isEqualTo(500); // clamped to spendable, floored to 100
    }

    @Test
    void spendAtCheckout_fifoConsumesOldestFirstAndNullifiesExpiry() {
        CoinLedger soon = credit(40, LocalDateTime.now().plusDays(3));
        soon.setId(10L);
        CoinLedger later = credit(60, LocalDateTime.now().plusDays(25));
        later.setId(11L);
        when(coinMapper.selectSpendableCreditsForUpdate(1L)).thenReturn(List.of(soon, later));
        when(coinMapper.selectBalance(1L)).thenReturn(100, 60, 0); // before, after-credit-1, after-credit-2

        coinService.spendAtCheckout(1L, 77L, 50); // takes 40 from soon, 10 from later

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper, times(2)).insert(cap.capture());
        List<CoinLedger> spends = cap.getAllValues();
        assertThat(spends).hasSize(2);
        assertThat(spends.get(0).getAmount()).isEqualTo(-40);
        assertThat(spends.get(0).getRefCreditId()).isEqualTo(10L);
        assertThat(spends.get(1).getAmount()).isEqualTo(-10);
        assertThat(spends.get(1).getRefCreditId()).isEqualTo(11L);
        // both consumed credits nullified
        verify(coinMapper, times(2)).updateById(any(CoinLedger.class));
    }

    @Test
    void spendAtCheckout_throwsConflictOnInsufficient() {
        CoinLedger c = credit(30, LocalDateTime.now().plusDays(5));
        c.setId(10L);
        when(coinMapper.selectSpendableCreditsForUpdate(1L)).thenReturn(List.of(c));
        when(coinMapper.selectBalance(1L)).thenReturn(30);
        assertThatThrownBy(() -> coinService.spendAtCheckout(1L, 77L, 100))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.CONFLICT.getCode());
    }

    @Test
    void canSpend_returnsTrueWhenSpendableBalanceCoversAmount() {
        // canSpend uses the FIFO-spendable sum (selectSpendableBalance), not the balance SUM —
        // refunded/nullified credits count toward the balance SUM but are NOT FIFO-spendable, so
        // canSpend must reflect what spendAtCheckout can actually consume.
        when(coinMapper.selectSpendableBalance(1L)).thenReturn(150);
        assertThat(coinService.canSpend(1L, 100)).isTrue();
        assertThat(coinService.canSpend(1L, 150)).isTrue(); // exactly covers
    }

    @Test
    void canSpend_returnsFalseWhenSpendableBalanceInsufficient() {
        when(coinMapper.selectSpendableBalance(1L)).thenReturn(50);
        assertThat(coinService.canSpend(1L, 100)).isFalse();
    }

    @Test
    void canSpend_returnsTrueForZeroOrNegativeAmount() {
        // a no-op spend is always "affordable" — spendAtCheckout early-returns on coinAmount <= 0.
        assertThat(coinService.canSpend(1L, 0)).isTrue();
        assertThat(coinService.canSpend(1L, -5)).isTrue();
        // must not hit the mapper for the no-op case
        verifyNoInteractions(coinMapper);
    }

    @Test
    void refundCoins_mirrorsSpendRowsAndRestoresExpiry() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        // The original credit's expiry — snapshotted onto the spend row at spend time (BEFORE the
        // credit's expires_at was nullified). refundCoins reads this snapshot from the spend row,
        // NOT from the now-nullified credit row. This is the Finding-1 fix: previously the refund
        // re-fetched the credit via selectById(refCreditId) and read its (nullified) expires_at →
        // refunded coins got expires_at=null → inflated the balance but were never FIFO-spendable.
        LocalDateTime originalExpiry = LocalDateTime.now().plusDays(10);
        CoinLedger spend = new CoinLedger();
        spend.setId(20L); spend.setAmount(-40); spend.setRefCreditId(30L); spend.setSource(CoinSource.CHECKOUT_SPEND);
        spend.setRefCreditExpiresAt(originalExpiry); // the snapshot captured at spend time
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectBalance(1L)).thenReturn(0);

        coinService.refundCoins(7L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        CoinLedger refund = cap.getValue();
        assertThat(refund.getAmount()).isEqualTo(40);
        assertThat(refund.getSource()).isEqualTo(CoinSource.REFUND_RETURN);
        assertThat(refund.getRefCreditId()).isEqualTo(30L);
        assertThat(refund.getExpiresAt()).isEqualTo(originalExpiry); // restored from the spend-row snapshot
        assertThat(refund.getIdemKey()).isEqualTo("refund:7:20");
        // The refund must NOT re-fetch the (nullified) credit row — the snapshot on the spend row
        // is the source of truth.
        verify(coinMapper, never()).selectById(any());
    }

    @Test
    void refundCoins_noOp_whenNoSpendRows() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of());
        coinService.refundCoins(7L);
        verify(coinMapper, never()).insert(any(CoinLedger.class));
    }

    @Test
    void refundCoins_noOp_onDuplicateKey() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        CoinLedger spend = new CoinLedger();
        spend.setId(20L); spend.setAmount(-40); spend.setRefCreditId(30L); spend.setSource(CoinSource.CHECKOUT_SPEND);
        spend.setRefCreditExpiresAt(LocalDateTime.now().plusDays(10)); // snapshot on the spend row
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any(CoinLedger.class));
        coinService.refundCoins(7L); // must not throw
        verify(coinMapper).insert(any(CoinLedger.class));
    }

    @Test
    void sweepExpiredCredits_insertsExpiredRowAndNullifiesCredit() {
        CoinLedger expired = credit(100, LocalDateTime.now().minusDays(1));
        expired.setId(50L);
        when(coinMapper.selectExpiredUnsweptCredits()).thenReturn(List.of(expired));
        when(coinMapper.selectBalance(expired.getUserId())).thenReturn(0);

        coinService.sweepExpiredCredits();

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(-100);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.EXPIRED);
        assertThat(cap.getValue().getRefCreditId()).isEqualTo(50L);
        assertThat(cap.getValue().getExpiresAt()).isNull();
        verify(coinMapper).updateById(argThat((CoinLedger c) -> c.getId().equals(50L) && c.getExpiresAt() == null));
    }

    @Test
    void sweepExpiredCredits_idempotentOnReRun() {
        // second run: selectExpiredUnsweptCredits returns empty (credits nullified) → no inserts
        when(coinMapper.selectExpiredUnsweptCredits()).thenReturn(List.of());
        coinService.sweepExpiredCredits();
        verify(coinMapper, never()).insert(any(CoinLedger.class));
    }
}

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
    void previewCheckoutDiscount_rejectsFlashSaleCart() {
        Product flash = new Product();
        flash.setId(1L); flash.setPriceInCents(1000); flash.setFlashSale(true);
        CoinDiscountPreview p = coinService.previewCheckoutDiscount(1L, List.of(flash), Map.of(1L, 1));
        assertThat(p.getEligible()).isFalse();
        assertThat(p.getReason()).contains("flash");
    }

    @Test
    void previewCheckoutDiscount_capsAt50PercentAndBalance() {
        Product p = new Product();
        p.setId(1L); p.setPriceInCents(2000); p.setFlashSale(false); // subtotal ¥20, 50% = ¥10 = 1000 coins
        when(coinMapper.selectBalance(1L)).thenReturn(500); // balance 500 < 1000 max
        CoinDiscountPreview r = coinService.previewCheckoutDiscount(1L, List.of(p), Map.of(1L, 1));
        assertThat(r.getEligible()).isTrue();
        assertThat(r.getMaxCoins()).isEqualTo(1000);
        assertThat(r.getUsable()).isEqualTo(500); // clamped to balance, floored to 100
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
    void refundCoins_mirrorsSpendRowsAndRestoresExpiry() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        CoinLedger spend = new CoinLedger();
        spend.setId(20L); spend.setAmount(-40); spend.setRefCreditId(30L); spend.setSource(CoinSource.CHECKOUT_SPEND);
        // the original credit's expiry — fetched by refCreditId
        CoinLedger originalCredit = credit(40, LocalDateTime.now().plusDays(10));
        originalCredit.setId(30L);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectById(30L)).thenReturn(originalCredit);
        when(coinMapper.selectBalance(1L)).thenReturn(0);

        coinService.refundCoins(7L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        CoinLedger refund = cap.getValue();
        assertThat(refund.getAmount()).isEqualTo(40);
        assertThat(refund.getSource()).isEqualTo(CoinSource.REFUND_RETURN);
        assertThat(refund.getRefCreditId()).isEqualTo(30L);
        assertThat(refund.getExpiresAt()).isEqualTo(originalCredit.getExpiresAt()); // restored
        assertThat(refund.getIdemKey()).isEqualTo("refund:7:20");
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
        CoinLedger originalCredit = credit(40, LocalDateTime.now().plusDays(10));
        originalCredit.setId(30L);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectById(30L)).thenReturn(originalCredit);
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

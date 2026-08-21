package com.iflytek.frame;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.util.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC012 end-to-end coin ledger test: earn (daily login + review reward) → spend at checkout
 * → refund → expire. Exercises {@link CoinService} against REAL MariaDB + Redis (no mocks),
 * mirroring {@link CheckoutFlowIntegrationTest}'s register/seed approach.
 *
 * <p>The {@code spendAtCheckout_thenRefund_returnsCoins} case inserts the {@code CHECKOUT_SPEND}
 * ledger rows directly (a focused coin-math test of {@code refundCoins}) rather than driving the
 * full async Stream consumer — {@code refundCoins} reads spend rows via
 * {@code selectSpendRowsByOrder} and restores each consumed credit's original expiry via
 * {@code selectById(refCreditId)}, so seeding a credit + a spend row that references it is
 * sufficient to prove the refund path.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CoinIntegrationTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    @Autowired CoinService coinService;
    @Autowired CoinMapper coinMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired ProductMapper productMapper;
    @Autowired UserService userService;
    @Autowired StringRedisTemplate redis;

    @Test
    void dailyLoginThenReviewReward_balanceReflectsBoth() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100
        CoinBalanceVO afterLogin = coinService.getBalance(uid);
        assertThat(afterLogin.getBalance()).isEqualTo(100);
        assertThat(afterLogin.getClaimedToday()).isTrue();

        // a second login same day is a no-op
        coinService.awardDailyLogin(uid);
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(100);

        // review reward on a paid order (paidInCents = 5000 → 50 coins)
        Long oid = seedPaidOrder(uid, 5000);
        coinService.awardReviewReward(oid, uid); // +50
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(150);

        // history shows the rows
        var hist = coinService.getHistory(uid, new PageQuery());
        assertThat(hist.getRecords().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void spendAtCheckout_thenRefund_returnsCoins() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100 (this is the credit the spend will consume)
        // seed a paid order (refundCoins reads its user_id to scope the spend-row lookup)
        Long oid = seedPaidOrder(uid, 20000);
        // insert a CHECKOUT_SPEND row directly — refundCoins reads it via
        // selectSpendRowsByOrder + looks up the original credit's expiry via selectById(refCreditId).
        // Reference the real DAILY_LOGIN credit just awarded (so the spend consumes it fully),
        // then nullify that credit's clock (mirrors spendAtCheckout: spent state).
        CoinLedger credit = coinMapper.selectDailyLogin(uid, LocalDate.now(CST));
        assertThat(credit).isNotNull();

        CoinLedger spend = new CoinLedger();
        spend.setUserId(uid);
        spend.setAmount(-100);
        spend.setBalanceAfter(0);
        spend.setSource(CoinSource.CHECKOUT_SPEND);
        spend.setRefOrderId(oid);
        spend.setRefCreditId(credit.getId());
        spend.setExpiresAt(null);
        coinMapper.insert(spend);

        // nullify the consumed credit's clock (spent state — excludes it from the balance sum)
        credit.setExpiresAt(null);
        coinMapper.updateById(credit);

        assertThat(coinService.getBalance(uid).getBalance()).isLessThanOrEqualTo(0);

        // refund returns the 100 coins (with the original expiry restored)
        coinService.refundCoins(oid);
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(100);

        // a REFUND_RETURN row was created for this order
        var hist = coinService.getHistory(uid, new PageQuery());
        assertThat(hist.getRecords().stream()
                .anyMatch(r -> "REFUND_RETURN".equals(r.getSource()) && oid.equals(r.getRefOrderId())))
                .isTrue();
    }

    @Test
    void sweepExpiredCredits_expiresOldCredit() {
        Long uid = registerTestUser();
        // insert a credit already past its clock
        CoinLedger c = new CoinLedger();
        c.setUserId(uid); c.setAmount(100); c.setBalanceAfter(100);
        c.setSource(CoinSource.DAILY_LOGIN);
        c.setLoginDate(LocalDate.now(CST).minusDays(31));
        c.setExpiresAt(LocalDateTime.now().minusDays(1));
        coinMapper.insert(c);

        coinService.sweepExpiredCredits();

        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(0);
        // re-run is idempotent
        coinService.sweepExpiredCredits();
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(0);
    }

    // --- helpers ---

    /**
     * Register a test user via the real UC001 SMS-gated path (code pre-seeded in Redis so the
     * dev LoggingVerificationProvider verifyAndConsume succeeds). Inlined from
     * {@link CheckoutFlowIntegrationTest} — extracting a shared support class would mean
     * editing the already-merged Task 6 test, which is out of scope.
     */
    private Long registerTestUser() {
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "123456";
        redis.opsForValue().set("sms:code:REGISTER:" + phone, code, java.time.Duration.ofSeconds(300));
        User user = userService.register(new RegisterRequest(
                "buyer" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "Pass1234!", phone, code, true));
        return user.getId();
    }

    /** Insert an Order directly with paidInCents set + COMPLETED status (this is a coin-math test, not a checkout-flow test). */
    private Long seedPaidOrder(Long uid, int paidInCents) {
        Order o = new Order();
        o.setUserId(uid); o.setCustomerName("t"); o.setCustomerEmail("t@t.com");
        o.setShippingAddress("a"); o.setTotalInCents(paidInCents); o.setPaidInCents(paidInCents);
        o.setStatus(OrderStatus.COMPLETED);
        o.setIdempotencyKey("ik-" + UUID.randomUUID());
        orderMapper.insert(o);
        return o.getId();
    }
}

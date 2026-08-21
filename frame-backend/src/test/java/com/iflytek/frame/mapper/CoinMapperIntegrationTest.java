package com.iflytek.frame.mapper;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.entity.CoinLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
@Transactional
class CoinMapperIntegrationTest {

    @Autowired CoinMapper coinMapper;

    private CoinLedger credit(Long userId, int amount, LocalDateTime expiresAt, String idemKey) {
        CoinLedger c = new CoinLedger();
        c.setUserId(userId);
        c.setAmount(amount);
        c.setBalanceAfter(amount);
        c.setSource(CoinSource.DAILY_LOGIN);
        c.setIdemKey(idemKey);
        c.setExpiresAt(expiresAt);
        coinMapper.insert(c);
        return c;
    }

    @Test
    void selectBalance_sumsUnexpiredRows() {
        Long u = 9001L;
        credit(u, 100, LocalDateTime.now().plusDays(20), "bal-1-" + System.nanoTime());
        credit(u, 50, LocalDateTime.now().plusDays(5), "bal-2-" + System.nanoTime());
        assertThat(coinMapper.selectBalance(u)).isEqualTo(150);
    }

    @Test
    void selectBalance_excludesExpiredCredits() {
        Long u = 9002L;
        CoinLedger expired = credit(u, 100, LocalDateTime.now().minusDays(1), "bal-3-" + System.nanoTime());
        // simulate the sweep: nullify expires_at + insert an EXPIRED balancing row
        expired.setExpiresAt(null);
        coinMapper.updateById(expired);
        CoinLedger exp = new CoinLedger();
        exp.setUserId(u); exp.setAmount(-100); exp.setBalanceAfter(0);
        exp.setSource(CoinSource.EXPIRED); exp.setRefCreditId(expired.getId()); exp.setExpiresAt(null);
        coinMapper.insert(exp);
        assertThat(coinMapper.selectBalance(u)).isEqualTo(0);
    }

    @Test
    void selectSpendableCreditsForUpdate_returnsOnlyPendingOrderedByExpiry() {
        Long u = 9003L;
        CoinLedger soon = credit(u, 40, LocalDateTime.now().plusDays(3), "sp-1-" + System.nanoTime());
        CoinLedger later = credit(u, 60, LocalDateTime.now().plusDays(25), "sp-2-" + System.nanoTime());
        var spendable = coinMapper.selectSpendableCreditsForUpdate(u);
        assertThat(spendable).hasSize(2);
        assertThat(spendable.get(0).getId()).isEqualTo(soon.getId()); // sooner expiry first
    }

    @Test
    void selectSpendableCreditsForUpdate_excludesSpentCredits() {
        Long u = 9004L;
        CoinLedger spent = credit(u, 100, LocalDateTime.now().plusDays(10), "sp-3-" + System.nanoTime());
        spent.setExpiresAt(null); // nullified on spend
        coinMapper.updateById(spent);
        assertThat(coinMapper.selectSpendableCreditsForUpdate(u)).isEmpty();
    }

    @Test
    void selectDailyLogin_findsRowForDate() {
        Long u = 9005L;
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        CoinLedger d = new CoinLedger();
        d.setUserId(u); d.setAmount(100); d.setBalanceAfter(100);
        d.setSource(CoinSource.DAILY_LOGIN); d.setLoginDate(today);
        d.setExpiresAt(LocalDateTime.now().plusDays(30));
        coinMapper.insert(d);
        assertThat(coinMapper.selectDailyLogin(u, today)).isNotNull();
        assertThat(coinMapper.selectDailyLogin(u, today.plusDays(1))).isNull();
    }

    @Test
    void selectSpendRowsByOrder_returnsCheckoutsForOrder() {
        Long u = 9006L;
        CoinLedger s = new CoinLedger();
        s.setUserId(u); s.setAmount(-100); s.setBalanceAfter(0);
        s.setSource(CoinSource.CHECKOUT_SPEND); s.setRefOrderId(777L); s.setExpiresAt(null);
        coinMapper.insert(s);
        assertThat(coinMapper.selectSpendRowsByOrder(u, 777L)).hasSize(1);
    }
}

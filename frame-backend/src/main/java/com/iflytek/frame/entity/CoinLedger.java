package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.CoinSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An append-only row in the coin ledger (UC012). Earned credits carry a future
 * {@code expires_at} (pending state); spending nullifies the consumed credit's
 * {@code expires_at} and inserts a {@code CHECKOUT_SPEND} negative row; the sweep
 * inserts an {@code EXPIRED} negative row and nullifies the credit's {@code expires_at}.
 * Balance = SUM(amount) WHERE (expires_at IS NULL OR expires_at > now()).
 */
@Schema(description = "Coin ledger entry")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_ledger")
public class CoinLedger extends BaseEntity {
    private Long userId;
    private Integer amount;
    private Integer balanceAfter;
    private CoinSource source;
    /** The order this row relates to (REVIEW_REWARD/CHECKOUT_SPEND/REFUND_RETURN); null otherwise. */
    private Long refOrderId;
    /** The earned-credit row this debit/expiry balances (CHECKOUT_SPEND/REFUND_RETURN/EXPIRED). */
    private Long refCreditId;
    /** Idempotency key: "review:{orderId}" or "refund:{orderId}:{spendRowId}"; null otherwise. */
    private String idemKey;
    /** CST date, only for DAILY_LOGIN rows (part of uk_coin_daily). */
    private LocalDate loginDate;
    /** Future date = pending; NULL = spent/expired/terminal. ALWAYS-written so updateById can nullify it on spend/sweep. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime expiresAt;
}

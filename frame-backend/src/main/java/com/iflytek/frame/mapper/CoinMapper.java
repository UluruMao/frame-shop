package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iflytek.frame.entity.CoinLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CoinMapper extends BaseMapper<CoinLedger> {

    /** Current usable balance = SUM(amount) over rows where expires_at IS NULL OR > now(). */
    Integer selectBalance(@Param("userId") Long userId);

    /** Pending positive credit rows for FIFO spend, oldest-first, locked for update. */
    List<CoinLedger> selectSpendableCreditsForUpdate(@Param("userId") Long userId);

    /** Pending earned credits past their expiry clock (for the sweep, all users). */
    List<CoinLedger> selectExpiredUnsweptCredits();

    /** The user's DAILY_LOGIN row for a CST date, or null (already-claimed check). */
    CoinLedger selectDailyLogin(@Param("userId") Long userId, @Param("loginDate") LocalDate loginDate);

    /** Paginated ledger history, newest-first. */
    IPage<CoinLedger> selectHistory(@Param("page") IPage<CoinLedger> page, @Param("userId") Long userId);

    /** The CHECKOUT_SPEND rows for an order (to mirror as REFUND_RETURN on refund). */
    List<CoinLedger> selectSpendRowsByOrder(@Param("userId") Long userId, @Param("orderId") Long orderId);
}

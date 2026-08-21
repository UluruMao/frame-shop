package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Coin", description = "UC012 coin balance + history")
@RestController
@RequestMapping("/coins")
@RequiredArgsConstructor
public class CoinController {

    private final CoinService coinService;

    @Operation(summary = "Current coin balance + daily-claim status")
    @GetMapping("/balance")
    public Result<CoinBalanceVO> balance() {
        return Result.success(coinService.getBalance(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Coin ledger history (paginated)")
    @GetMapping("/history")
    public Result<PageResult<CoinHistoryVO>> history(@Valid @ParameterObject PageQuery query) {
        return Result.success(coinService.getHistory(SecurityUtils.currentUserId(), query));
    }
}

package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "A coin ledger row, for the history list")
@Data
public class CoinHistoryVO {
    private Long id;
    private Integer amount;
    private String source;
    private Long refOrderId;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    private Integer balanceAfter;
}

package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Coin balance + daily-claim status")
@Data
public class CoinBalanceVO {
    private Integer balance;
    private Boolean claimedToday;
    private Integer expiringSoon;
}

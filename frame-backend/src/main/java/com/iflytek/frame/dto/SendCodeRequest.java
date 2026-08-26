package com.iflytek.frame.dto;

import com.iflytek.frame.sms.SmsPurpose;
import com.iflytek.frame.validation.Phone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SendCodeRequest {
    @NotBlank @Phone private String phone;
    @NotNull private SmsPurpose purpose;
    @NotBlank private String captchaId;
    @NotBlank private String captchaAnswer;
}

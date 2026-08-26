package com.iflytek.frame.dto;

import com.iflytek.frame.validation.Phone;
import com.iflytek.frame.validation.SmsCode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequest {
    @NotBlank @Phone private String phone;
    @NotBlank @SmsCode private String code;
    @NotBlank private String newPassword;
}

package com.iflytek.frame.dto;

import com.iflytek.frame.validation.Phone;
import com.iflytek.frame.validation.SmsCode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RebindConfirmRequest {
    @NotBlank @Phone private String newPhone;
    @NotBlank @SmsCode private String code;     // code sent to the NEW phone
}

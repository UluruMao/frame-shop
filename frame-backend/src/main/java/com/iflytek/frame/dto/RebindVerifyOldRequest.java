package com.iflytek.frame.dto;

import com.iflytek.frame.validation.SmsCode;
import lombok.Data;

@Data
public class RebindVerifyOldRequest {
    @SmsCode
    private String code;        // present if oldPhoneUsable=true
    private String password;    // present if oldPhoneUsable=false
}

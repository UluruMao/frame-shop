package com.iflytek.frame.dto;

import lombok.Data;

@Data
public class RebindVerifyOldRequest {
    private String code;        // present if oldPhoneUsable=true
    private String password;    // present if oldPhoneUsable=false
}

package com.iflytek.frame.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequest {
    @NotBlank private String phone;
    @NotBlank private String code;
    @NotBlank private String newPassword;
}

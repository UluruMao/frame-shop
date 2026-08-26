package com.iflytek.frame.dto;

import com.iflytek.frame.validation.Phone;
import com.iflytek.frame.validation.SmsCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "Register request (UC001: SMS-gated)")
@Data
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "username must not be blank")
    @Size(min = 4, max = 25, message = "username must be 4-25 chars")
    private String username;

    @NotBlank(message = "password must not be blank")
    private String password;  // strength validated in the service (≥8, ≥2 categories)

    @NotBlank(message = "phone must not be blank")
    @Phone
    private String phone;

    @NotBlank(message = "verification code must not be blank")
    @SmsCode
    private String code;

    @AssertTrue(message = "must agree to terms")
    private boolean agreed;
}

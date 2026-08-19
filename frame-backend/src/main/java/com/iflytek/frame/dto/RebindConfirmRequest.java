package com.iflytek.frame.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RebindConfirmRequest {
    @NotBlank private String newPhone;
    @NotBlank private String code;     // code sent to the NEW phone
}

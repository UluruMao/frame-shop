package com.iflytek.frame.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeleteAccountRequest {
    @NotBlank(message = "password must not be blank")
    private String password;
}

package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "Register request")
@Data
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "username must not be blank")
    @Size(min = 3, max = 64, message = "username must be 3-64 chars")
    private String username;

    @NotBlank(message = "password must not be blank")
    @Size(min = 6, max = 100, message = "password must be 6-100 chars")
    private String password;
}

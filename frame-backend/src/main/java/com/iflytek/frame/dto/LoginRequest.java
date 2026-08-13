package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Login request payload.
 */
@Schema(description = "Login request")
@Data
public class LoginRequest {

    @Schema(description = "Username", example = "admin")
    @NotBlank(message = "username must not be blank")
    private String username;

    @Schema(description = "Password", example = "admin123")
    @NotBlank(message = "password must not be blank")
    private String password;
}

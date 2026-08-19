package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;
import com.iflytek.frame.security.SecurityUser;
import com.iflytek.frame.service.AuthService;
import com.iflytek.frame.service.PasswordService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. {@code /auth/login} is public; {@code /auth/me} requires a valid
 * JWT and echoes the current user — handy for verifying the token flow end-to-end.
 */
@Tag(name = "Auth", description = "Login + current-user endpoints")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;

    @Operation(summary = "Login", description = "Validates credentials and returns a JWT. "
            + "Demo credentials: admin / admin123")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @Operation(summary = "Current user", description = "Returns the authenticated user from the JWT. Requires auth.")
    @GetMapping("/me")
    public Result<SecurityUser> me() {
        return Result.success(SecurityUtils.currentUser());
    }

    @Operation(summary = "Forgot password — reset via SMS code")
    @PostMapping("/password/forgot")
    public Result<Void> forgot(@Valid @RequestBody com.iflytek.frame.dto.ForgotPasswordRequest req) {
        passwordService.resetByPhone(req.getPhone(), req.getCode(), req.getNewPassword());
        return Result.success();
    }

    @Operation(summary = "Change password (logged in)")
    @PostMapping("/password/change")
    public Result<Void> changePassword(@Valid @RequestBody com.iflytek.frame.dto.ChangePasswordRequest req) {
        passwordService.changePassword(com.iflytek.frame.util.SecurityUtils.currentUserId(),
                req.getOldPassword(), req.getNewPassword());
        return Result.success();
    }
}

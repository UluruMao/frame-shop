package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.CaptchaResponse;
import com.iflytek.frame.dto.SendCodeRequest;
import com.iflytek.frame.service.CaptchaService;
import com.iflytek.frame.service.VerificationCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Verification", description = "Captcha + SMS verification codes")
@RestController
@RequiredArgsConstructor
public class VerificationController {

    private final CaptchaService captchaService;
    private final VerificationCodeService codeService;

    @Operation(summary = "Get a captcha challenge (in-house SVG)")
    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha() {
        return Result.success(captchaService.generate());
    }

    @Operation(summary = "Send an SMS verification code (captcha-gated, 60s cooldown)")
    @PostMapping("/auth/sms/send")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        if (!captchaService.verify(request.getCaptchaId(), request.getCaptchaAnswer())) {
            return Result.fail(com.iflytek.frame.common.ResultCode.BAD_REQUEST, "captcha incorrect");
        }
        codeService.sendCode(request.getPhone(), request.getPurpose());
        return Result.success();
    }
}

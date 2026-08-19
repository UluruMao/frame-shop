package com.iflytek.frame.service;

import com.iflytek.frame.dto.CaptchaResponse;

/** In-house SVG captcha (arithmetic challenge). No third-party dependency. */
public interface CaptchaService {
    CaptchaResponse generate();
    boolean verify(String captchaId, String answer);
}

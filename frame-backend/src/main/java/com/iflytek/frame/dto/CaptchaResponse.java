package com.iflytek.frame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** A captcha challenge: an opaque id + an inline SVG image rendering the question. */
@Data
@AllArgsConstructor
public class CaptchaResponse {
    private String captchaId;
    private String svgImage;
}

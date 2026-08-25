package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.dto.CaptchaResponse;
import com.iflytek.frame.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple in-house captcha: an arithmetic question {@code a + b = ?} rendered as an
 * inline SVG. The answer is stored in Redis keyed by a UUID (TTL 120s) and verified
 * one-shot. Self-contained — no reCAPTCHA/TencentCaptcha dependency.
 */
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private final StringRedisTemplate redis;

    @Override
    public CaptchaResponse generate() {
        int a = ThreadLocalRandom.current().nextInt(1, 10);
        int b = ThreadLocalRandom.current().nextInt(1, 10);
        String answer = String.valueOf(a + b);
        String id = UUID.randomUUID().toString();
        redis.opsForValue().set(RedisKeys.captcha(id), answer, Duration.ofSeconds(120));
        String svg = buildSvg(a + " + " + b + " = ?");
        return new CaptchaResponse(id, svg);
    }

    @Override
    public boolean verify(String captchaId, String answer) {
        String key = captchaId.startsWith("captcha:") ? captchaId : RedisKeys.captcha(captchaId);
        String stored = redis.opsForValue().get(key);
        if (stored == null || !stored.equals(answer == null ? "" : answer.trim())) {
            return false;
        }
        redis.delete(key);
        return true;
    }

    private String buildSvg(String text) {
        // A minimal SVG with the question text + light noise lines so it's not trivially OCR-able.
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int x1 = ThreadLocalRandom.current().nextInt(0, 120);
            int y1 = ThreadLocalRandom.current().nextInt(0, 40);
            int x2 = ThreadLocalRandom.current().nextInt(0, 120);
            int y2 = ThreadLocalRandom.current().nextInt(0, 40);
            noise.append("<line x1='").append(x1).append("' y1='").append(y1)
                    .append("' x2='").append(x2).append("' y2='").append(y2)
                    .append("' stroke='#cbd5e1' stroke-width='1'/>");
        }
        return "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='40'>"
                + "<rect width='120' height='40' fill='#f1f5f9'/>"
                + noise
                + "<text x='60' y='26' font-family='sans-serif' font-size='18' "
                + "text-anchor='middle' fill='#1e293b'>" + text + "</text>"
                + "</svg>";
    }
}

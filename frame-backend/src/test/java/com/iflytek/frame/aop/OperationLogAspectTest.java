package com.iflytek.frame.aop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogAspectTest {

    @Test
    void mask_sensitiveFieldsAreRedacted() {
        String json = "{\"username\":\"bob\",\"password\":\"secret\",\"code\":\"123456\","
                + "\"oldPassword\":\"x\",\"newPassword\":\"y\",\"captchaAnswer\":\"7\","
                + "\"accessKeySecret\":\"k\"}";
        String masked = OperationLogAspect.maskSensitive(json);
        assertThat(masked).contains("\"password\":\"***\"")
                .contains("\"code\":\"***\"")
                .contains("\"oldPassword\":\"***\"")
                .contains("\"newPassword\":\"***\"")
                .contains("\"captchaAnswer\":\"***\"")
                .contains("\"accessKeySecret\":\"***\"")
                .contains("\"username\":\"bob\""); // non-sensitive preserved
    }
}

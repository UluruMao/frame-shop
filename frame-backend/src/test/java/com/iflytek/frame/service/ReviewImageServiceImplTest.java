package com.iflytek.frame.service;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.impl.ReviewImageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewImageServiceImplTest {

    @TempDir Path tempDir;
    ReviewImageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReviewImageServiceImpl();
        // inject the temp dir via reflection (field is @Value-injected in prod)
        org.springframework.test.util.ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
    }

    private MultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    @Test
    void upload_jpg_returnsReviewsUrl() {
        String url = service.upload(file("cat.jpg", "image/jpeg", new byte[]{1, 2, 3}));
        assertThat(url).startsWith("/uploads/reviews/review_");
        assertThat(url).endsWith(".jpg");
    }

    @Test
    void upload_png_ok() {
        assertThat(service.upload(file("p.PNG", "image/png", new byte[]{9})).endsWith(".png") ||
                service.upload(file("p.PNG", "image/png", new byte[]{9})).endsWith(".PNG")).isTrue();
    }

    @Test
    void upload_empty_rejected() {
        assertThatThrownBy(() -> service.upload(file("x.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID.getCode());
    }

    @Test
    void upload_oversize_rejected() {
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> service.upload(file("big.jpg", "image/jpeg", big)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID.getCode());
    }

    @Test
    void upload_badType_rejected() {
        assertThatThrownBy(() -> service.upload(file("evil.gif", "image/gif", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID.getCode());
    }

    @Test
    void upload_typeJpegButExtTxt_rejected() {
        assertThatThrownBy(() -> service.upload(file("evil.txt", "image/jpeg", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID.getCode());
    }
}

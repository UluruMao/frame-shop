package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.ReviewImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ReviewImageServiceImpl implements ReviewImageService {

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".jpg", ".jpeg", ".png");
    private static final Set<String> ALLOWED_TYPE = Set.of("image/jpeg", "image/png");

    @Override
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "image exceeds 20MB");
        }
        String contentType = file.getContentType();
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.')).toLowerCase();
        }
        if (contentType == null || !ALLOWED_TYPE.contains(contentType) || !ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "only jpg/png allowed");
        }
        try {
            Path dir = Path.of(uploadDir, "reviews");
            Files.createDirectories(dir);
            String filename = "review_" + UUID.randomUUID() + ext;
            Files.copy(file.getInputStream(), dir.resolve(filename));
            return "/uploads/reviews/" + filename;
        } catch (Exception e) {
            log.error("review image upload failed", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "image upload failed");
        }
    }
}

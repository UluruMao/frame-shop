package com.iflytek.frame.service;

import org.springframework.web.multipart.MultipartFile;

/** Validates + stores a review image (jpg/png ≤20MB). Returns its servable URL. */
public interface ReviewImageService {
    String upload(MultipartFile file);
}

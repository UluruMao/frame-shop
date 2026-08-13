package com.iflytek.frame.service;

import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;

public interface CheckoutService {
    CheckoutResponse checkout(CheckoutRequest request, Long userId);
}

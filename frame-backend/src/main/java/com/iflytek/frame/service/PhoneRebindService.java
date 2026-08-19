package com.iflytek.frame.service;

import com.iflytek.frame.dto.RebindConfirmRequest;
import com.iflytek.frame.dto.RebindVerifyOldRequest;

public interface PhoneRebindService {
    /** UC017 step 1: user picks whether the old phone is usable. */
    void init(Long userId, String currentUsername, boolean oldPhoneUsable);

    /** UC017 step 2: verify ownership — SMS code (old usable) or login password (old not usable). */
    void verifyOld(Long userId, String currentUsername, boolean oldPhoneUsable, String code, String password);

    /** UC017 step 3: new phone + code sent to it → rebind. */
    void confirm(Long userId, String currentUsername, String newPhone, String code);
}

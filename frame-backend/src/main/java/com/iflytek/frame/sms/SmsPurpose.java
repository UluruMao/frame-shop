package com.iflytek.frame.sms;

/** Why an SMS verification code is being sent. Scoped per phone+purpose for cooldown. */
public enum SmsPurpose {
    REGISTER, FORGOT_PASSWORD, REBIND_OLD, REBIND_NEW, LOCKOUT_RESET
}

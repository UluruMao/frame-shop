package com.iflytek.frame.service;

public interface AccountService {
    /** Soft-delete the current user: verify password, anonymize PII, close chats. */
    void deactivateAccount(Long userId, String username, String password);
}

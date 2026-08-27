package com.iflytek.frame.controller;

import com.iflytek.frame.dto.DeleteAccountRequest;
import com.iflytek.frame.service.AccountService;
import com.iflytek.frame.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

class ProfileControllerDeleteTest {

    @Test
    void deleteDelegatesToAccountService() {
        AccountService accountService = mock(AccountService.class);
        ProfileController controller = new ProfileController(null, null, null, accountService);
        DeleteAccountRequest req = new DeleteAccountRequest();
        req.setPassword("right");

        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::currentUserId).thenReturn(7L);
            su.when(SecurityUtils::currentUsername).thenReturn("alice");

            controller.deleteAccount(req);
        }
        verify(accountService).deactivateAccount(7L, "alice", "right");
    }
}

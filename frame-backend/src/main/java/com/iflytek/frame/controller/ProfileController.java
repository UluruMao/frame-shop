package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.DeleteAccountRequest;
import com.iflytek.frame.dto.RebindConfirmRequest;
import com.iflytek.frame.dto.RebindInitRequest;
import com.iflytek.frame.dto.RebindVerifyOldRequest;
import com.iflytek.frame.dto.UpdateProfileRequest;
import com.iflytek.frame.dto.UserProfile;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.service.AccountService;
import com.iflytek.frame.service.PhoneRebindService;
import com.iflytek.frame.service.ProfileService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Profile", description = "Nickname/avatar/signature + phone rebind")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final PhoneRebindService rebindService;
    private final UserService userService;
    private final AccountService accountService;

    @Operation(summary = "Update nickname/signature")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody UpdateProfileRequest req) {
        profileService.updateProfile(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(), req);
        return Result.success();
    }

    @Operation(summary = "Upload avatar (multipart)")
    @PostMapping("/avatar")
    public Result<String> avatar(@RequestParam("file") MultipartFile file) {
        return Result.success(profileService.uploadAvatar(SecurityUtils.currentUserId(), file));
    }

    @Operation(summary = "Phone rebind — init (UC017)")
    @PostMapping("/phone/rebind/init")
    public Result<Void> rebindInit(@Valid @RequestBody RebindInitRequest req) {
        rebindService.init(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(), req.getOldPhoneUsable());
        return Result.success();
    }

    @Operation(summary = "Phone rebind — verify old phone/password (UC017)")
    @PostMapping("/phone/rebind/verify-old")
    public Result<Void> rebindVerifyOld(@Valid @RequestBody RebindVerifyOldRequest req) {
        // oldPhoneUsable is inferred by which field is present here, but the frontend knows; pass both branches.
        boolean usable = req.getCode() != null && !req.getCode().isBlank();
        rebindService.verifyOld(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(),
                usable, req.getCode(), req.getPassword());
        return Result.success();
    }

    @Operation(summary = "Phone rebind — confirm new phone (UC017)")
    @PostMapping("/phone/rebind/confirm")
    public Result<Void> rebindConfirm(@Valid @RequestBody RebindConfirmRequest req) {
        rebindService.confirm(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(),
                req.getNewPhone(), req.getCode());
        return Result.success();
    }

    @Operation(summary = "Self-delete account (soft delete, password re-auth)")
    @PostMapping("/account/delete")
    public Result<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest req) {
        accountService.deactivateAccount(SecurityUtils.currentUserId(), SecurityUtils.currentUsername(), req.getPassword());
        return Result.success();
    }
}

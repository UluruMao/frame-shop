package com.iflytek.frame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserProfile {
    private Long id;
    private String username;
    private String phone;
    private String nickname;
    private String avatarUrl;
    private String signature;
    private String role;
}

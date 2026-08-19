package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "User")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user")
public class User extends BaseEntity {
    @Schema(description = "Login name")
    private String username;
    @Schema(description = "BCrypt-hashed password")
    private String passwordHash;
    @Schema(description = "Role: USER or ADMIN")
    private Role role;
    @Schema(description = "Phone number (SMS verification target)")
    private String phone;
    @Schema(description = "Display nickname (defaults to username if null)")
    private String nickname;
    @Schema(description = "Avatar image URL")
    private String avatarUrl;
    @Schema(description = "Personal signature")
    private String signature;
    @Schema(description = "Account status: ACTIVE or LOCKED")
    private String status;
    @Schema(description = "Consecutive failed login attempts (resets on success)")
    private Integer failedAttempts;
}

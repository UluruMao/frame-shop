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
}

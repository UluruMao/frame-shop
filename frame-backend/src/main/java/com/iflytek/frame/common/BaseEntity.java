package com.iflytek.frame.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Base entity every DB entity extends. Holds the common audit columns:
 * {@code id}, {@code createTime}, {@code updateTime}.
 *
 * <p>{@code createTime}/{@code updateTime} are auto-filled by
 * {@link com.iflytek.frame.config.MybatisMetaObjectHandler} on insert/update.
 */
@Data
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Primary key, DB auto-increment")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "Creation time")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "Last update time")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

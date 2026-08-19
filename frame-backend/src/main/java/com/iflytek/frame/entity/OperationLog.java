package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "Operation log entry")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("operation_log")
public class OperationLog extends BaseEntity {
    private Long userId;
    private String username;
    private String method;
    private String uri;
    private String params;
    private String resultStatus;
    private Integer costMs;
    private String ip;
}

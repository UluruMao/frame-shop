package com.iflytek.frame.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.mapper.OperationLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "Operation-log audit trail")
@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
public class AdminOperationLogController {

    private final OperationLogMapper mapper;

    @Operation(summary = "Paginated operation-log entries (ADMIN)")
    @GetMapping
    public Result<IPage<OperationLog>> list(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OperationLog> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
        return Result.success(mapper.selectPage(page,
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog>()
                        .orderByDesc(OperationLog::getCreateTime)));
    }
}

package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.service.AdminOperationLogService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "Operation-log audit trail")
@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
public class AdminOperationLogController {

    private final AdminOperationLogService service;

    @Operation(summary = "Paginated operation-log entries (ADMIN)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Result<PageResult<OperationLog>> list(@Valid @ParameterObject PageQuery query) {
        return Result.success(service.page(query));
    }
}

package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.PageResults;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.mapper.OperationLogMapper;
import com.iflytek.frame.service.AdminOperationLogService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Operation-log audit trail query. Moved here from
 * {@code AdminOperationLogController} so the controller only wires HTTP → service
 * and the query construction (Page + order-by-desc) lives with the data layer.
 */
@Service
@RequiredArgsConstructor
public class AdminOperationLogServiceImpl implements AdminOperationLogService {

    private final OperationLogMapper mapper;

    @Override
    public PageResult<OperationLog> page(PageQuery query) {
        Page<OperationLog> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<OperationLog> w = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getCreateTime);
        return PageResults.of(mapper.selectPage(page, w));
    }
}

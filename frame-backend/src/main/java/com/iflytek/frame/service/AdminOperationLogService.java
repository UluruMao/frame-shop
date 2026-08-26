package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.OperationLog;
import com.iflytek.frame.util.PageQuery;

/**
 * Admin-side operation-log audit trail query.
 * Encapsulates the MyBatis-Plus {@code Page} + {@code LambdaQueryWrapper} construction
 * (previously inlined in {@code AdminOperationLogController}).
 */
public interface AdminOperationLogService {

    /** Paginated operation-log entries, newest first (order-by createTime desc). */
    PageResult<OperationLog> page(PageQuery query);
}

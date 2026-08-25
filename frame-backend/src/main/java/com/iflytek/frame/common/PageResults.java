package com.iflytek.frame.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * Collapses the 6× {@code PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records)}
 * tail. Also the seam for future PageResult shaping.
 */
public final class PageResults {
    private PageResults() {}

    public static <T> PageResult<T> of(IPage<T> page) {
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }
}

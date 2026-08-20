package com.iflytek.frame.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaults_current1_size10_sortOrderDesc() {
        PageQuery q = new PageQuery();
        assertThat(q.getCurrent()).isEqualTo(1L);
        assertThat(q.getSize()).isEqualTo(10L);
        assertThat(q.getSortOrder()).isEqualTo("desc");
        assertThat(q.getKeyword()).isNull();
        assertThat(q.getCategory()).isNull();
    }

    @Test
    void keywords_and_sortOrderSql_are_transient_null_until_set() {
        PageQuery q = new PageQuery();
        assertThat(q.getKeywords()).isNull();
        assertThat(q.getSortOrderSql()).isNull();
    }
}

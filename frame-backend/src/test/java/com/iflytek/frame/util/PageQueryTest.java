package com.iflytek.frame.util;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void keywords_splits_on_comma_and_space_trims_and_drops_empties() {
        PageQuery q = new PageQuery();
        q.setKeyword("red, widget , ,foo bar");
        assertThat(q.keywords()).containsExactly("red", "widget", "foo", "bar");
    }

    @Test
    void keywords_null_or_blank_returns_null() {
        PageQuery q = new PageQuery();
        assertThat(q.keywords()).isNull();
        q.setKeyword("   ");
        assertThat(q.keywords()).isNull();
        q.setKeyword(",,,");
        assertThat(q.keywords()).isNull();
    }

    @Test
    void sortOrderSql_asc_desc_default() {
        PageQuery q = new PageQuery();
        assertThat(q.sortOrderSql()).isEqualTo(" DESC"); // default (sortOrder="desc")
        q.setSortOrder("asc");
        assertThat(q.sortOrderSql()).isEqualTo(" ASC");
        q.setSortOrder("ASC");
        assertThat(q.sortOrderSql()).isEqualTo(" ASC");
        q.setSortOrder("desc");
        assertThat(q.sortOrderSql()).isEqualTo(" DESC");
        q.setSortOrder(null);
        assertThat(q.sortOrderSql()).isEqualTo(" DESC"); // null → default
        q.setSortOrder("garbage");
        assertThat(q.sortOrderSql()).isEqualTo(" DESC"); // unknown → default
    }
}


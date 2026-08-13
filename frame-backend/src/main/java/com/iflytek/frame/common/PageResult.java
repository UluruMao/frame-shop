package com.iflytek.frame.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Generic paginated list payload, the standard shape returned by list/query endpoints.
 *
 * @param <T> row type
 */
@Schema(description = "Paginated result")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Total rows matching the query", example = "42")
    private long total;

    @Schema(description = "Current page number (1-based)", example = "1")
    private long current;

    @Schema(description = "Page size", example = "10")
    private long size;

    @Schema(description = "Rows on this page")
    private List<T> records;

    public static <T> PageResult<T> of(long total, long current, long size, List<T> records) {
        return new PageResult<>(total, current, size, records);
    }
}

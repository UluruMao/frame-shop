package com.iflytek.frame.util;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Standard pagination input carried by list endpoints.
 * 1-based page number, sensible defaults + bounds.
 */
@Schema(description = "Pagination query parameters")
@Data
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Page number, 1-based", example = "1")
    @Min(value = 1, message = "current must be >= 1")
    private long current = 1;

    @Schema(description = "Page size", example = "10")
    @Min(value = 1, message = "size must be >= 1")
    @Max(value = 200, message = "size must be <= 200")
    private long size = 10;

    @Schema(description = "Sort field name (optional)", example = "id")
    private String sortField;

    @Schema(description = "Sort direction: asc/desc", example = "desc")
    private String sortOrder = "desc";
}

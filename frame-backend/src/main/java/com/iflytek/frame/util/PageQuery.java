package com.iflytek.frame.util;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard pagination input carried by list endpoints.
 * 1-based page number, sensible defaults + bounds.
 *
 * <p>{@link #keywords()} and {@link #sortOrderSql()} derive the values the SQL layer
 * consumes (split keyword list + whitelisted " ASC"/" DESC") from the raw request fields
 * {@code keyword}/{@code sortOrder}, so callers stop re-deriving them inline.
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

    @Schema(description = "Search keyword (split on comma/space into ANDed LIKE terms)", example = "red widget")
    private String keyword;

    @Schema(description = "Category filter (exact match)", example = "electronics")
    private String category;

    /** Derived by the service: keyword split on [,\s]+, trimmed, empties dropped. Null if blank. Not a request param. */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private transient java.util.List<String> keywords;

    /** Derived by the service: " ASC" or " DESC" (whitelist-validated). Not a request param. */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private transient String sortOrderSql;

    /**
     * Derive the keyword list from {@code keyword}: split on {@code [,\s]+}, trim, drop empties.
     * Returns {@code null} when {@code keyword} is blank. The single source of truth for this
     * derivation (previously inlined by {@code ProductServiceImpl.page}).
     */
    public List<String> keywords() {
        String kw = keyword;
        if (kw == null || kw.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String t : kw.split("[,\\s]+")) {
            String tr = t.trim();
            if (!tr.isEmpty()) terms.add(tr);
        }
        return terms.isEmpty() ? null : terms;
    }

    /**
     * Derive the SQL sort-direction suffix from {@code sortOrder}: {@code " ASC"} when the value
     * (trimmed, case-insensitive) is "asc", otherwise {@code " DESC"} (the default). This is the
     * ONLY {@code ${}} value in the product list SQL — it is derived from a fixed whitelist, never
     * raw user input (防SQL注入). The single source of truth for this derivation.
     */
    public String sortOrderSql() {
        String so = sortOrder;
        if (so != null && so.trim().equalsIgnoreCase("asc")) {
            return " ASC";
        }
        return " DESC";
    }
}

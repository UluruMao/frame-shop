package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.DemoProductDTO;
import com.iflytek.frame.entity.DemoProduct;
import com.iflytek.frame.service.DemoProductService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Demo CRUD controller. Every endpoint wraps its payload in {@link Result} and is
 * documented via SpringDoc/OpenAPI annotations visible at /swagger-ui.html.
 */
@Tag(name = "DemoProduct", description = "Sample CRUD + OpenAPI annotation demo")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class DemoProductController {

    private final DemoProductService service;

    @Operation(summary = "Get a product by id", description = "Returns a single product or 404.")
    @GetMapping("/{id}")
    public Result<DemoProduct> get(@Parameter(description = "Product id") @PathVariable Long id) {
        return Result.success(service.getById(id));
    }

    @Operation(summary = "List products (paginated)")
    @GetMapping
    public Result<PageResult<DemoProduct>> page(@Valid @ParameterObject PageQuery query) {
        return Result.success(service.page(query));
    }

    @Operation(summary = "Create a product")
    @PostMapping
    public Result<DemoProduct> create(@Valid @RequestBody DemoProductDTO dto) {
        return Result.success(service.create(dto));
    }

    @Operation(summary = "Update a product")
    @PutMapping("/{id}")
    public Result<DemoProduct> update(@Parameter(description = "Product id") @PathVariable Long id,
                                      @Valid @RequestBody DemoProductDTO dto) {
        return Result.success(service.update(id, dto));
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@Parameter(description = "Product id") @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Result.success());
    }
}

package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Product", description = "Product CRUD + image upload")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService service;

    @Operation(summary = "Get a product by id")
    @GetMapping("/{id}")
    public Result<Product> get(@PathVariable Long id) {
        return Result.success(service.getById(id));
    }

    @Operation(summary = "List products (paginated)")
    @GetMapping
    public Result<PageResult<Product>> page(@Valid @ParameterObject PageQuery query) {
        return Result.success(service.page(query));
    }

    @Operation(summary = "Create a product")
    @PostMapping
    public Result<Product> create(@Valid @RequestBody ProductDTO dto) {
        return Result.success(service.create(dto));
    }

    @Operation(summary = "Update a product")
    @PutMapping("/{id}")
    public Result<Product> update(@PathVariable Long id, @Valid @RequestBody ProductDTO dto) {
        return Result.success(service.update(id, dto));
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "Upload a product image")
    @PostMapping("/{id}/image")
    public Result<String> uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return Result.success(service.uploadImage(id, file));
    }
}

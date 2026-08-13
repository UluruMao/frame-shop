package com.iflytek.frame.dto;

import com.iflytek.frame.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Product create/update payload")
@Data
public class ProductDTO {
    private Long id;
    @NotBlank(message = "name must not be blank")
    private String name;
    @NotNull(message = "priceInCents must not be null")
    @Min(value = 0, message = "priceInCents must be >= 0")
    private Integer priceInCents;
    @NotNull(message = "stock must not be null")
    @Min(value = 0, message = "stock must be >= 0")
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;

    public Product toEntity() {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPriceInCents(priceInCents);
        p.setStock(stock);
        p.setImageUrl(imageUrl);
        p.setCategory(category);
        p.setFlashSale(isFlashSale);
        p.setFlashSaleAt(flashSaleAt);
        p.setDescription(description);
        return p;
    }
}

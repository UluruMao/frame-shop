package com.iflytek.frame.dto;

import com.iflytek.frame.entity.DemoProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Create/update payload for {@link DemoProduct}. Decouples API shape from DB shape.
 */
@Schema(description = "Demo product create/update payload")
@Data
public class DemoProductDTO {

    @Schema(description = "Product id (null on create, required on update)", example = "1")
    private Long id;

    @Schema(description = "Product name")
    @NotBlank(message = "name must not be blank")
    private String name;

    @Schema(description = "Unit price", example = "19.99")
    @NotNull(message = "price must not be null")
    @Min(value = 0, message = "price must be >= 0")
    private BigDecimal price;

    @Schema(description = "Stock on hand", example = "100")
    @NotNull(message = "stock must not be null")
    @Min(value = 0, message = "stock must be >= 0")
    private Integer stock;

    @Schema(description = "Free-text description")
    private String description;

    public DemoProduct toEntity() {
        DemoProduct p = new DemoProduct();
        p.setId(id);
        p.setName(name);
        p.setPrice(price);
        p.setStock(stock);
        p.setDescription(description);
        return p;
    }
}

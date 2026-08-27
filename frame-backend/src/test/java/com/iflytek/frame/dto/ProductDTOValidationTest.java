package com.iflytek.frame.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation tests for {@link ProductDTO}. Guards the imageUrl length:
 * a too-long URL must produce a validation violation (clean 400) rather than
 * reaching the DB and throwing MariaDbDataTruncation (500).
 */
class ProductDTOValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ProductDTO validBase() {
        ProductDTO dto = new ProductDTO();
        dto.setName("键盘");
        dto.setPriceInCents(25900);
        dto.setStock(40);
        return dto;
    }

    @Test
    void imageUrl_underLimit_isValid() {
        ProductDTO dto = validBase();
        dto.setImageUrl("https://example.com/img.png");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void imageUrl_overLimit_isInvalid() {
        ProductDTO dto = validBase();
        // 2100-char URL — over the 2048 cap.
        dto.setImageUrl("https://example.com/" + "a".repeat(2080));
        Set<ConstraintViolation<ProductDTO>> v = validator.validate(dto);
        assertThat(v).anySatisfy(c ->
                assertThat(c.getPropertyPath().toString()).isEqualTo("imageUrl"));
    }
}

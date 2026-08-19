package com.iflytek.frame.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RebindInitRequest {
    @NotNull private Boolean oldPhoneUsable;   // true → verify by SMS to old phone; false → by password
}

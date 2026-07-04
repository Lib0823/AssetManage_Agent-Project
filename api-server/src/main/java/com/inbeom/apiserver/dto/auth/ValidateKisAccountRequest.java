package com.inbeom.apiserver.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateKisAccountRequest {

    @NotBlank(message = "APP Key는 필수입니다")
    private String appKey;

    @NotBlank(message = "APP Secret은 필수입니다")
    private String appSecret;

    /** KIS 모드: MOCK(모의) 또는 REAL(실전). 미지정 시 모의로 검증. */
    private String mode;
}

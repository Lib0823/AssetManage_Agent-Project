package com.inbeom.apiserver.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KisAccountResponse {

    private Long id;
    private String accountNumber;
    private String accountProductCode;
    private String appKey;
    private String appSecret;
    private String htsId;
    /** KIS 모드: MOCK(모의) / REAL(실전). */
    private String accountMode;
    private Boolean isVerified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

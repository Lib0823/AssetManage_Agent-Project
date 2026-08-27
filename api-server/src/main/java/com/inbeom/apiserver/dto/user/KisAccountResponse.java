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
    private Boolean isVerified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

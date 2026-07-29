package com.inbeom.apiserver.dto.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 내부 API: 자동매매 활성 사용자 한 명의 실행 컨텍스트.
 *
 * <p>ai-agent 파이프라인이 사용자별 루프(보유종목 조회 → 매매 실행)를 돌릴 때
 * 필요한 최소 정보만 노출한다. KIS 키 자체는 절대 노출하지 않으며,
 * 매매는 {@code kisAccountId} 로 api-server 가 대행한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoTradingUserResponse {

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("kis_account_id")
    private Long kisAccountId;

    @JsonProperty("order_amount")
    private Long orderAmount;

    @JsonProperty("max_holdings")
    private Integer maxHoldings;

    @JsonProperty("order_type")
    private String orderType;
}

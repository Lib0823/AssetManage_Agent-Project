package com.inbeom.apiserver.dto.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 내부 API: ai-agent 가 특정 사용자 명의로 요청하는 매수/매도 주문.
 *
 * <p>snake_case 본문(Python 클라이언트 친화). 모의투자는 시장가 주문이므로
 * {@code price} 는 선택값이며, 미지정 시 0(시장가)으로 처리한다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class InternalTradeRequest {

    @JsonProperty("stock_code")
    @NotBlank(message = "stock_code is required")
    private String stockCode;

    @JsonProperty("stock_name")
    private String stockName;

    @JsonProperty("quantity")
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @JsonProperty("price")
    private BigDecimal price;
}

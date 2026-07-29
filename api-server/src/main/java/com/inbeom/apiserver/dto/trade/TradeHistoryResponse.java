package com.inbeom.apiserver.dto.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래내역 조회 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistoryResponse {

    private String id;  // KIS 주문번호 (odno)

    private String stockCode;  // 종목코드

    private String stockName;  // 종목명

    private String orderType;  // 주문구분 (BUY, SELL)

    private String orderStatus;  // 주문상태 (COMPLETED, PARTIAL, CANCELLED, PENDING)

    private Integer quantity;  // 주문수량

    private BigDecimal orderPrice;  // 주문가격

    private BigDecimal executedPrice;  // 체결가격 (평균가)

    private Integer executedQuantity;  // 체결수량

    private LocalDateTime orderedAt;  // 주문일시

    private String orderDate;  // 주문일자 (YYYY-MM-DD)

    private String orderTime;  // 주문시각 (HH:MM:SS)

    @lombok.Builder.Default
    private Boolean aiTraded = false;  // AI(봇) 자동매매 주문 여부 (trade_execution_plan ODNO 매칭)
}

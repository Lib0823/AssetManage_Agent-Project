package com.inbeom.apiserver.dto.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 미체결 주문 조회 응답 DTO
 * KIS 주식일별주문체결조회(VTTC0081R) 결과 중 미체결(잔량 > 0, PENDING/PARTIAL)만 매핑한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingOrderResponse {

    private String orderNumber;  // KIS 주문번호 (odno)

    private String stockCode;  // 종목코드 (pdno)

    private String stockName;  // 종목명 (prdt_name)

    private String orderType;  // 주문구분 (BUY, SELL)

    private Integer orderQuantity;  // 주문수량 (ord_qty)

    private Integer remainQuantity;  // 미체결 잔량 (rmn_qty 또는 주문수량 - 총체결수량)

    private BigDecimal orderPrice;  // 주문단가 (ord_unpr)

    private String orderedAt;  // 주문일시 (YYYY-MM-DD HH:MM:SS)
}

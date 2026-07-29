package com.inbeom.apiserver.dto.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 매수가능조회 응답 DTO.
 * KIS 매수가능조회(VTTC8908R, 모의) output 매핑:
 * max_buy_qty → maxBuyQuantity (최대매수수량),
 * ord_psbl_cash → orderableCash (주문가능현금).
 * 조회 실패/rt_cd != 0 시 수량/현금은 0, notice 에 안내 메시지.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderableResponse {

    private String stockCode;  // 종목코드 (PDNO)

    private Long maxBuyQuantity;  // 최대매수수량 (max_buy_qty)

    private Long orderableCash;  // 주문가능현금 (ord_psbl_cash)

    private String notice;  // 조회 실패 시 UI 안내용 메시지 (정상이면 null)
}

package com.inbeom.apiserver.dto.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 예약주문 목록 조회 응답 DTO (camelCase).
 * KIS 예약주문 조회(order-resv-ccnl, CTSC0004R) output 행을 defensive 매핑한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservedOrderResponse {

    /** 예약주문순번 (rsvn_ord_seq). */
    private String seq;

    /** 예약주문 조직번호 (rsvn_ord_orgno). */
    private String orgNo;

    /** 예약주문 접수/주문일자 (YYYYMMDD). */
    private String orderDate;

    /** 종목코드 (pdno). */
    private String stockCode;

    /** 종목명 (prdt_name). */
    private String stockName;

    /** 매매구분 ("buy" | "sell"). KIS sll_buy_dvsn_cd 01→sell / 02→buy. */
    private String side;

    /** 주문수량 (ord_qty). */
    private Long quantity;

    /** 주문단가 (ord_unpr). */
    private Long price;

    /** 가격유형 ("limit" | "market"). KIS ord_dvsn_cd 01→market / else→limit. */
    private String priceType;

    /** 처리상태 (KIS 원문). */
    private String status;

    /** 예약주문 종료일 (rsvn_ord_end_dt, YYYYMMDD). */
    private String endDate;
}

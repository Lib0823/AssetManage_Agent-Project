package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 해외주식(미국) 미체결 내역 응답 DTO (USD 원본 — KRW 환산은 프론트가 처리).
 *
 * <p>KIS 해외주식 미체결(VTTS3018R, 모의, /trading/inquire-nccs) output 매핑(필드명 MUST-VERIFY):
 * odno → orderNo, pdno → symbol, prdt_name → name, sll_buy_dvsn_cd(_name) → side,
 * ft_ord_qty → orderQty, nccs_qty → remainQty, ft_ord_unpr3 → orderPrice,
 * ord_dt+ord_tmd → orderedAt.
 *
 * <p>모의 미지원·rt_cd != 0·예외 시 list 빈 목록 + notice(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasPendingOrderResponse {

    private List<OverseasPendingOrderItem> list;

    private String currency;   // 통화 (기본 USD)

    private String notice;     // 미연동/실패/빈 결과 시 UI 안내용 메시지 (정상이면 null)

    /**
     * 해외 미체결 주문 단건.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverseasPendingOrderItem {

        private String orderNo;        // 주문번호 (odno)

        private String symbol;         // 종목코드 (pdno)

        private String name;           // 종목명 (prdt_name)

        private String side;           // 매도/매수 구분 (BUY/SELL)

        private Integer orderQty;      // 주문수량 (ft_ord_qty)

        private Integer remainQty;     // 미체결수량 (nccs_qty)

        private BigDecimal orderPrice; // 주문단가 (ft_ord_unpr3)

        private String orderedAt;      // 주문일시 (ord_dt + ord_tmd, yyyyMMddHHmmss)
    }
}

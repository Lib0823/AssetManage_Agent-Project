package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 해외주식(미국) 체결내역(거래내역) 응답 DTO (USD 원본 — KRW 환산은 프론트가 처리).
 *
 * <p>KIS 해외주식 체결내역(VTTS3035R, 모의, /trading/inquire-ccnl) output 매핑(필드명 MUST-VERIFY):
 * pdno → symbol, prdt_name → name, sll_buy_dvsn_cd_name(또는 sll_buy_dvsn_cd) → side,
 * ft_ccld_qty → qty, ft_ccld_unpr3 → price, ord_dt+ord_tmd → executedAt,
 * odno → orderNo, prcs_stat_name(또는 nccs_qty 기반) → status.
 *
 * <p>모의 미지원·rt_cd != 0·예외 시 list 빈 목록 + notice(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasTradeHistoryResponse {

    private List<OverseasTradeHistoryItem> list;

    private String currency;   // 통화 (기본 USD)

    private String notice;     // 미연동/실패/빈 결과 시 UI 안내용 메시지 (정상이면 null)

    /**
     * 해외 체결내역 단건.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverseasTradeHistoryItem {

        private String symbol;         // 종목코드 (pdno)

        private String name;           // 종목명 (prdt_name)

        private String side;           // 매도/매수 구분 (BUY/SELL)

        private Integer qty;           // 체결수량 (ft_ccld_qty)

        private BigDecimal price;      // 체결단가 (ft_ccld_unpr3)

        private String executedAt;     // 체결일시 (ord_dt + ord_tmd, yyyyMMddHHmmss)

        private String orderNo;        // 주문번호 (odno)

        private String status;         // 처리상태 (prcs_stat_name 등)
    }
}

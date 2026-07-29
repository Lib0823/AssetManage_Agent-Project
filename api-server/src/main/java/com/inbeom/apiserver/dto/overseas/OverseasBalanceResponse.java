package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 해외주식 잔고 응답 DTO (USD 원본 — KRW 환산은 프론트가 처리).
 *
 * <p>KIS 해외주식 잔고(VTTS3012R, 모의) output1(보유종목)·output2(요약) 매핑:
 * ovrs_pdno → symbol, ovrs_item_name → name, ovrs_cblc_qty → quantity,
 * ord_psbl_qty → orderableQty, pchs_avg_pric → avgPrice, now_pric2 → currentPrice,
 * frcr_evlu_pfls_amt → evalProfitLoss, evlu_pfls_rt → profitLossRate,
 * ovrs_tot_pfls/frcr_pchs_amt1 등 → 요약(totalProfitLoss/totalPurchase). 합계는
 * output2 가 비면 보유종목 합으로 보정한다.
 *
 * <p>모의 해외매매 미지원·rt_cd != 0·예외 시 holdings 빈 목록 + notice(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasBalanceResponse {

    private List<OverseasHolding> holdings;

    private BigDecimal totalEval;        // 총평가금액 (USD)

    private BigDecimal totalPurchase;    // 총매입금액 (USD)

    private BigDecimal totalProfitLoss;  // 총평가손익 (USD)

    private String currency;             // 통화 (기본 USD)

    private String notice;               // 미연동/실패 시 UI 안내용 메시지 (정상이면 null)

    /**
     * 해외 보유 종목 단건.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverseasHolding {

        private String symbol;              // 종목코드 (ovrs_pdno)

        private String name;                // 종목명 (ovrs_item_name)

        private Integer quantity;           // 보유수량 (ovrs_cblc_qty)

        private Integer orderableQty;       // 매도가능수량 (ord_psbl_qty)

        private BigDecimal avgPrice;        // 매입평균가 (pchs_avg_pric)

        private BigDecimal currentPrice;    // 현재가 (now_pric2)

        private BigDecimal evalProfitLoss;  // 평가손익 (frcr_evlu_pfls_amt)

        private BigDecimal profitLossRate;  // 평가손익율(%) (evlu_pfls_rt)
    }
}

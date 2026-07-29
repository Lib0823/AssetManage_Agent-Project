package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 해외주식(미국) 매수가능 조회 응답 DTO (USD 원본 — KRW 환산은 프론트가 처리).
 *
 * <p>KIS 해외주식 매수가능금액(VTTS3007R, 모의, /trading/inquire-psamount) output 매핑(필드명 MUST-VERIFY):
 * ovrs_max_ord_psbl_qty(또는 max_ord_psbl_qty) → maxBuyQty,
 * ord_psbl_frcr_amt(또는 frcr_ord_psbl_amt1) → orderableCash(USD).
 *
 * <p>시세 단가(price)는 호출부에서 OVRS_ORD_UNPR 로 전달된 입력가 기준이다.
 * 모의 미지원·rt_cd != 0·예외 시 maxBuyQty=0 / orderableCash=0 + notice(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasOrderableResponse {

    private String symbol;             // 종목코드 (예: AAPL)

    private String exchange;           // 잔고/매매 거래소코드 (NASD/NYSE/AMEX)

    private Integer maxBuyQty;         // 최대 매수가능수량

    private BigDecimal orderableCash;  // 주문가능 외화금액 (USD)

    private String currency;           // 통화 (기본 USD)

    private String notice;             // 미연동/실패 시 UI 안내용 메시지 (정상이면 null)
}

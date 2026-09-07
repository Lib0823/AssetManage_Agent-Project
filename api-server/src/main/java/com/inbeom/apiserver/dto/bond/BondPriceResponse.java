package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장내채권 현재가 응답 DTO (FHKBJ773400C0, inquire-price).
 *
 * <p>핵심 필드는 {@code bond_prpr}(채권현재가) → {@link #currentPrice}. 주식의
 * {@code stck_prpr} 과 이름이 다르므로 주식 DTO 를 복사해 오면 값이 비어버린다.
 *
 * <p>단가는 {@link BigDecimal} 이다 — 채권 단가는 소수를 가지며, 정수로 반올림하면
 * 화면에 표시되는 가격과 실제 주문 단가가 어긋난다.
 *
 * <p>실패/미연동 시 값 필드는 null 이고 {@code notice} 만 채워진다(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondPriceResponse {

    /** 표준종목코드 (stnd_iscd). */
    private String bondCode;

    /** HTS 한글종목명 (hts_kor_isnm). */
    private String bondName;

    /** 채권현재가 (bond_prpr). */
    private BigDecimal currentPrice;

    /** 전일대비부호 (prdy_vrss_sign). */
    private String prevDaySign;

    /** 채권전일대비 (bond_prdy_vrss). */
    private BigDecimal prevDayDiff;

    /** 전일대비율(%) (prdy_ctrt). */
    private BigDecimal prevDayRate;

    /** 누적거래량 (acml_vol). */
    private BigDecimal accumulatedVolume;

    /** 채권전일종가 (bond_prdy_clpr). */
    private BigDecimal prevClosePrice;

    /** 시가 (bond_oprc). */
    private BigDecimal openPrice;

    /** 고가 (bond_hgpr). */
    private BigDecimal highPrice;

    /** 저가 (bond_lwpr). */
    private BigDecimal lowPrice;

    /** 수익비율 (ernn_rate). */
    private BigDecimal earningRate;

    /** 채권상한가 (bond_mxpr). */
    private BigDecimal upperLimitPrice;

    /** 채권하한가 (bond_llam). */
    private BigDecimal lowerLimitPrice;

    /** 미연동/실패/최신아님 안내 (정상이면 null). */
    private String notice;
}

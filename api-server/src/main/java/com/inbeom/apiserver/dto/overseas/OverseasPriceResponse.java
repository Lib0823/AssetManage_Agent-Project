package com.inbeom.apiserver.dto.overseas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 해외주식 현재가(상세) 응답 DTO.
 *
 * <p>KIS 해외주식 현재가상세(HHDFS76200200, 실패 시 현재가 HHDFS00000300) output 매핑:
 * last → last(현재가), base → base(전일종가), diff → diff(전일대비),
 * rate → rate(등락율%), curr → currency(통화, 기본 USD).
 *
 * <p>시세 미연동(quote 키 미설정)·해외시세 권한없음·rt_cd != 0·예외 시 가격 필드는 null,
 * {@code notice} 에 UI 안내 메시지를 담는다(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasPriceResponse {

    private String symbol;        // 종목코드 (예: AAPL)

    private String exchange;      // 잔고/매매 거래소코드 (NASD/NYSE/AMEX)

    private String currency;      // 통화 (기본 USD)

    private BigDecimal last;      // 현재가

    private BigDecimal base;      // 전일종가

    private BigDecimal diff;      // 전일대비

    private BigDecimal rate;      // 등락율(%)

    private String notice;        // 미연동/실패 시 UI 안내용 메시지 (정상이면 null)
}

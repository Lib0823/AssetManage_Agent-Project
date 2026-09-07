package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 장내채권 호가 응답 DTO (FHKBJ773401C0, inquire-asking-price). 5단 호가.
 *
 * <p><b>필드 접두사가 가격과 잔량에서 다르다</b> — 가격은 {@code bond_askp1..5}/{@code bond_bidp1..5}
 * 로 {@code bond_} 접두사가 붙지만, 잔량은 {@code askp_rsqn1..5}/{@code bidp_rsqn1..5} 로 붙지 않는다.
 * 주식 호가 DTO 를 복사해 오면 반드시 한쪽이 빈다.
 *
 * <p>장내채권은 유동성이 낮아 <b>호가가 아예 없는 것이 정상</b>이다. 빈 호가는 오류가 아니므로
 * 예외를 던지지 않고 빈 목록으로 내려간다 — 화면은 "현재 호가가 없습니다"로 안내한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondOrderbookResponse {

    /** 표준종목코드 (요청값 그대로). */
    private String bondCode;

    /** 호가접수시각 (aspr_acpt_hour, HHmmss). */
    private String quoteTime;

    /** 매도호가 1~5단 (없으면 빈 목록). */
    private List<BondQuoteLevel> asks;

    /** 매수호가 1~5단 (없으면 빈 목록). */
    private List<BondQuoteLevel> bids;

    /** 총 매도잔량 (total_askp_rsqn). */
    private BigDecimal totalAskQty;

    /** 총 매수잔량 (total_bidp_rsqn). */
    private BigDecimal totalBidQty;

    /** 순매수호가잔량 (ntby_aspr_rsqn). */
    private BigDecimal netQty;

    /** 미연동/실패/최신아님 안내 (정상이면 null). */
    private String notice;

    /**
     * 호가 1단.
     *
     * @see BondOrderbookResponse 가격/잔량의 KIS 필드 접두사가 다르다는 점
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BondQuoteLevel {

        /** 호가 단계 (1~5). */
        private int level;

        /** 호가 가격 (bond_askpN / bond_bidpN). */
        private BigDecimal price;

        /** 호가 잔량 (askp_rsqnN / bidp_rsqnN — 접두사 없음). */
        private BigDecimal remainQty;

        /** 해당 호가의 수익률 (seln_ernn_rateN / shnu_ernn_rateN). */
        private BigDecimal yieldRate;
    }
}

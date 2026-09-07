package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장내채권 기본조회 응답 DTO (CTPF1114R, search-bond-info).
 *
 * <p>KIS output(83개 필드) 중 화면이 쓰는 것만 매핑한다:
 * pdno → bondCode, ksd_bond_item_name → bondName, issu_dt/rdpt_dt/lstg_dt → 일자,
 * ksd_rcvg_bond_srfc_inrt → couponRate, iso_crcy_cd → currencyCode,
 * bond_clsf_kor_name → bondClassName, sprx_psbl_yn → separateTaxationPossible.
 *
 * <p>{@code currencyCode} 는 총자산 합산에서 외화표시채권을 걸러내기 위한 것이다 —
 * 원화가 아닌 채권을 그대로 더하면 총액이 조용히 틀어진다.
 *
 * <p>금액/이율은 전부 {@link BigDecimal}. 채권 단가는 소수를 가지며 {@code double} 로 받으면
 * 반올림 오차가 주문 단가로 그대로 나간다.
 *
 * <p>실패/미연동 시 값 필드는 null 이고 {@code notice} 만 채워진다(예외 전파 금지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondInfoResponse {

    /** 표준종목코드 (pdno) — 12자리 영숫자 혼합. */
    private String bondCode;

    /** 채권종목명 (ksd_bond_item_name). */
    private String bondName;

    /** 발행일자 (issu_dt, yyyyMMdd). */
    private String issueDate;

    /** 상환일자 (rdpt_dt, yyyyMMdd). */
    private String redemptionDate;

    /** 상장일자 (lstg_dt, yyyyMMdd). */
    private String listingDate;

    /** 표면이율(%) (ksd_rcvg_bond_srfc_inrt). */
    private BigDecimal couponRate;

    /** 만기상환율 (bond_expd_rdpt_rt). */
    private BigDecimal maturityRedemptionRate;

    /** 만기보장수익율 (bond_expd_asrc_erng_rt). */
    private BigDecimal maturityYield;

    /** 통화코드 (iso_crcy_cd) — "KRW" 가 아니면 원화 합산 대상이 아니다. */
    private String currencyCode;

    /** 채권분류한글명 (bond_clsf_kor_name). */
    private String bondClassName;

    /** 분리과세가능여부 (sprx_psbl_yn, "Y"/"N"). */
    private String separateTaxationPossible;

    /** 부도발생여부 (dshn_occr_yn, "Y"/"N"). */
    private String defaultOccurred;

    /** 미연동/실패/최신아님 안내 (정상이면 null). */
    private String notice;
}

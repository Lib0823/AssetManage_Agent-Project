package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장내채권 발행정보 응답 DTO (CTPF1101R, issue-info).
 *
 * <p><b>신용등급은 단일 필드가 아니다.</b> KIS 는 평가사별로 4개 필드를 따로 준다
 * (kis/kbp/nice/fnp). 하나로 뭉개면 어느 평가사 등급인지 알 수 없어지고, 평가사마다 등급이
 * 다른 경우(흔하다) 어느 쪽을 보여줬는지도 추적할 수 없다. 그래서 4개를 그대로 노출한다.
 *
 * <p>{@code faceValue}(papr, 액면가)와 {@code quoteUnitPrice}(bond_nmpr_unit_pric, 호가단위)는
 * 주문 단가 입력 검증·금액 환산의 근거값이다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondIssueInfoResponse {

    /** 표준종목코드 (pdno). */
    private String bondCode;

    /** 상품명 (prdt_name). */
    private String bondName;

    /** 상품약어명 (prdt_abrv_name). */
    private String shortName;

    /** 액면가 (papr). */
    private BigDecimal faceValue;

    /** 만기일자 (expd_dt, yyyyMMdd). */
    private String maturityDate;

    /** 표면이율(%) (srfc_inrt). */
    private BigDecimal couponRate;

    /** 한국신용평가 신용등급 (kis_crdt_grad_text). */
    private String kisCreditGrade;

    /** 한국채권평가 신용등급 (kbp_crdt_grad_text). */
    private String kbpCreditGrade;

    /** 한국신용정보(NICE) 신용등급 (nice_crdt_grad_text). */
    private String niceCreditGrade;

    /** 에프앤자산평가 신용등급 (fnp_crdt_grad_text). */
    private String fnpCreditGrade;

    /** 채권호가단위가격 (bond_nmpr_unit_pric) — 단가 입력 검증용. */
    private BigDecimal quoteUnitPrice;

    /** 발행금액 (issu_amt). */
    private BigDecimal issueAmount;

    /** 상장잔액 (lstg_rmnd) — 유동성 판단. */
    private BigDecimal listedBalance;

    /** 이자지급개월수 (int_dfrm_mcnt). */
    private String interestPaymentMonths;

    /** 직전 이자지급일자 (rgbf_int_dfrm_dt). */
    private String prevInterestPaymentDate;

    /** 차기 이자지급일자 (nxtm_int_dfrm_dt). */
    private String nextInterestPaymentDate;

    /** 분리과세가능여부 (sprx_psbl_yn, "Y"/"N"). */
    private String separateTaxationPossible;

    /** 채권거래정지구분코드 (bond_tr_stop_dvsn_cd) — 주문 전 확인 가치가 있다. */
    private String tradingStopCode;

    /** 투자유의상품여부 (ivst_heed_prdt_yn). */
    private String investmentCaution;

    /** 미연동/실패/최신아님 안내 (정상이면 null). */
    private String notice;
}

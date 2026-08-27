package com.inbeom.apiserver.dto.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * KIS API 주식일별주문체결조회 응답 DTO
 * TR_ID: TTTC0081R
 */
@Data
public class KisDailyCcldResponse {

    @JsonProperty("rt_cd")
    private String rtCd;  // 응답코드

    @JsonProperty("msg_cd")
    private String msgCd;  // 메시지코드

    @JsonProperty("msg1")
    private String msg1;  // 메시지

    @JsonProperty("output1")
    private List<DailyCcldItem> output1;  // 주문체결 리스트

    @JsonProperty("output2")
    private PaginationInfo output2;  // 페이징 정보

    @JsonProperty("ctx_area_fk100")
    private String ctxAreaFk100;  // 연속조회검색조건100

    @JsonProperty("ctx_area_nk100")
    private String ctxAreaNk100;  // 연속조회키100

    /**
     * 주문체결 아이템
     */
    @Data
    public static class DailyCcldItem {

        @JsonProperty("ord_dt")
        private String ordDt;  // 주문일자 (YYYYMMDD)

        @JsonProperty("ord_gno_brno")
        private String ordGnoBrno;  // 주문채번지점번호

        @JsonProperty("odno")
        private String odno;  // 주문번호

        @JsonProperty("orgn_odno")
        private String orgnOdno;  // 원주문번호

        @JsonProperty("ord_dvsn_name")
        private String ordDvsnName;  // 주문구분명

        @JsonProperty("sll_buy_dvsn_cd")
        private String sllBuyDvsnCd;  // 매도매수구분코드 (01:매도, 02:매수)

        @JsonProperty("sll_buy_dvsn_cd_name")
        private String sllBuyDvsnCdName;  // 매도매수구분명

        @JsonProperty("pdno")
        private String pdno;  // 상품번호 (종목코드)

        @JsonProperty("prdt_name")
        private String prdtName;  // 상품명 (종목명)

        @JsonProperty("ord_qty")
        private String ordQty;  // 주문수량

        @JsonProperty("ord_unpr")
        private String ordUnpr;  // 주문단가

        @JsonProperty("ord_tmd")
        private String ordTmd;  // 주문시각 (HHMMSS)

        @JsonProperty("tot_ccld_qty")
        private String totCcldQty;  // 총체결수량

        @JsonProperty("avg_prvs")
        private String avgPrvs;  // 평균가

        @JsonProperty("cncl_yn")
        private String cnclYn;  // 취소여부

        @JsonProperty("tot_ccld_amt")
        private String totCcldAmt;  // 총체결금액

        @JsonProperty("loan_dt")
        private String loanDt;  // 대출일자

        @JsonProperty("ord_dvsn_cd")
        private String ordDvsnCd;  // 주문구분코드

        @JsonProperty("cncl_cfrm_qty")
        private String cnclCfrmQty;  // 취소확인수량

        @JsonProperty("rmn_qty")
        private String rmnQty;  // 잔여수량

        @JsonProperty("rjct_qty")
        private String rjctQty;  // 거부수량

        @JsonProperty("ccld_cndt_name")
        private String ccldCndtName;  // 체결조건명

        @JsonProperty("infm_tmd")
        private String infmTmd;  // 통보시각

        @JsonProperty("ctac_tlno")
        private String ctacTlno;  // 연락전화번호

        @JsonProperty("prdt_type_cd")
        private String prdtTypeCd;  // 상품유형코드

        @JsonProperty("excg_dvsn_cd")
        private String excgDvsnCd;  // 거래소구분코드
    }

    /**
     * 페이징 정보
     */
    @Data
    public static class PaginationInfo {

        @JsonProperty("tot_ord_qty")
        private String totOrdQty;  // 총주문수량

        @JsonProperty("tot_ccld_qty")
        private String totCcldQty;  // 총체결수량

        @JsonProperty("tot_ccld_amt")
        private String totCcldAmt;  // 총체결금액

        @JsonProperty("pchg_qty")
        private String pchgQty;  // 매수체결수량

        @JsonProperty("sll_qty")
        private String sllQty;  // 매도체결수량
    }
}

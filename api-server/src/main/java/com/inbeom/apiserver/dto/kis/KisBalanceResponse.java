package com.inbeom.apiserver.dto.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * KIS API 주식잔고조회 응답 DTO
 * TR_ID: TTTC8434R
 */
@Getter
@Setter
@NoArgsConstructor
public class KisBalanceResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output1")
    private List<Output1> output1;

    @JsonProperty("output2")
    private List<Output2> output2;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Output1 {
        @JsonProperty("pdno")
        private String pdno;  // 종목코드

        @JsonProperty("prdt_name")
        private String prdtName;  // 종목명

        @JsonProperty("hldg_qty")
        private String hldgQty;  // 보유수량

        @JsonProperty("ord_psbl_qty")
        private String ordPsblQty;  // 주문가능수량 (매도가능수량)

        @JsonProperty("pchs_avg_pric")
        private String pchsAvgPric;  // 매입평균가격

        @JsonProperty("prpr")
        private String prpr;  // 현재가

        @JsonProperty("evlu_amt")
        private String evluAmt;  // 평가금액

        @JsonProperty("evlu_pfls_amt")
        private String evluPflsAmt;  // 평가손익금액

        @JsonProperty("evlu_pfls_rt")
        private String evluPflsRt;  // 평가손익율

        @JsonProperty("pchs_amt")
        private String pchsAmt;  // 매입금액
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Output2 {
        @JsonProperty("tot_evlu_amt")
        private String totEvluAmt;  // 총평가금액

        @JsonProperty("pchs_amt_smtl")
        private String pchsAmtSmtl;  // 매입금액합계

        @JsonProperty("evlu_pfls_smtl")
        private String evluPflsSmtl;  // 평가손익합계

        @JsonProperty("evlu_pfls_rt")
        private String evluPflsRt;  // 평가손익율

        @JsonProperty("dnca_tot_amt")
        private String dncaTotAmt;  // 예수금총액
    }
}

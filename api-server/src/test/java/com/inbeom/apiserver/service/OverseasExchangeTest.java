package com.inbeom.apiserver.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OverseasExchange 단위 테스트")
class OverseasExchangeTest {

    @Test
    @DisplayName("코드 매핑 - 잔고/매매 코드(NASD/NYSE/AMEX)와 시세 코드(NAS/NYS/AMS)가 분리되어 있다")
    void codes_BalanceAndQuoteAreDistinct() {
        // Given / When / Then
        assertThat(OverseasExchange.NASD.balanceCode()).isEqualTo("NASD");
        assertThat(OverseasExchange.NASD.quoteCode()).isEqualTo("NAS");
        assertThat(OverseasExchange.NYSE.balanceCode()).isEqualTo("NYSE");
        assertThat(OverseasExchange.NYSE.quoteCode()).isEqualTo("NYS");
        assertThat(OverseasExchange.AMEX.balanceCode()).isEqualTo("AMEX");
        assertThat(OverseasExchange.AMEX.quoteCode()).isEqualTo("AMS");
    }

    @Test
    @DisplayName("currency - MVP 범위(미국)는 모두 USD")
    void currency_AllUsd() {
        // Given / When / Then
        for (OverseasExchange ex : OverseasExchange.values()) {
            assertThat(ex.currency()).isEqualTo("USD");
        }
    }

    @Test
    @DisplayName("fromCode - 잔고 코드로 매핑된다")
    void fromCode_BalanceCode() {
        // Given / When / Then
        assertThat(OverseasExchange.fromCode("NASD")).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("NYSE")).isEqualTo(OverseasExchange.NYSE);
        assertThat(OverseasExchange.fromCode("AMEX")).isEqualTo(OverseasExchange.AMEX);
    }

    @Test
    @DisplayName("fromCode - 시세 코드로도 매핑된다")
    void fromCode_QuoteCode() {
        // Given / When / Then
        assertThat(OverseasExchange.fromCode("NAS")).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("NYS")).isEqualTo(OverseasExchange.NYSE);
        assertThat(OverseasExchange.fromCode("AMS")).isEqualTo(OverseasExchange.AMEX);
    }

    @Test
    @DisplayName("fromCode - 소문자/공백 포함 입력도 정규화된다")
    void fromCode_TrimsAndUppercases() {
        // Given / When / Then
        assertThat(OverseasExchange.fromCode("nyse")).isEqualTo(OverseasExchange.NYSE);
        assertThat(OverseasExchange.fromCode("  ams  ")).isEqualTo(OverseasExchange.AMEX);
    }

    @Test
    @DisplayName("fromCode - null/공백/미지원 코드는 NASD 로 폴백한다")
    void fromCode_UnknownFallsBackToNasd() {
        // Given / When / Then
        assertThat(OverseasExchange.fromCode(null)).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("")).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("   ")).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("TSE")).isEqualTo(OverseasExchange.NASD);
        assertThat(OverseasExchange.fromCode("HKEX")).isEqualTo(OverseasExchange.NASD);
    }
}

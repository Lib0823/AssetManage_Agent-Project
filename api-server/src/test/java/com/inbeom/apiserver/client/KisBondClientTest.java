package com.inbeom.apiserver.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장내채권 TR_ID·엔드포인트 계약 고정 테스트.
 *
 * <p>KIS 는 TR_ID 한 글자만 달라도 "권한 없음"으로 조용히 거부하므로, 상수를 리팩터링 중에
 * 잘못 건드리면 런타임에서야 드러난다. 실측 계약(`_workspace/bond_api_contract.md`)의 값을
 * 여기에 고정해 두면 그 변경이 컴파일/테스트 단계에서 잡힌다.
 */
@DisplayName("KisBondClient — 채권 TR_ID·엔드포인트 계약")
class KisBondClientTest {

    @Test
    @DisplayName("채권 TR_ID 상수가 KIS 실전값으로 고정돼 있다")
    void bondTrIdsAreRealTradingValues() {
        assertThat(KisBondClient.TR_SEARCH_BOND_INFO).isEqualTo("CTPF1114R");
        assertThat(KisBondClient.TR_ISSUE_INFO).isEqualTo("CTPF1101R");
        assertThat(KisBondClient.TR_INQUIRE_PRICE).isEqualTo("FHKBJ773400C0");
        assertThat(KisBondClient.TR_INQUIRE_ASKING_PRICE).isEqualTo("FHKBJ773401C0");
        assertThat(KisBondClient.TR_INQUIRE_BALANCE).isEqualTo("CTSC8407R");
        assertThat(KisBondClient.TR_SELL).isEqualTo("TTTC0958U");
        assertThat(KisBondClient.TR_INQUIRE_DAILY_CCLD).isEqualTo("CTSC8013R");
    }

    @Test
    @DisplayName("채권 엔드포인트가 /uapi/domestic-bond/v1/** 경로다")
    void bondEndpointsUseDomesticBondPath() {
        assertThat(KisBondClient.PATH_SEARCH_BOND_INFO)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/search-bond-info");
        assertThat(KisBondClient.PATH_ISSUE_INFO)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/issue-info");
        assertThat(KisBondClient.PATH_INQUIRE_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-price");
        assertThat(KisBondClient.PATH_INQUIRE_ASKING_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-asking-price");
        assertThat(KisBondClient.PATH_INQUIRE_BALANCE)
                .isEqualTo("/uapi/domestic-bond/v1/trading/inquire-balance");
        assertThat(KisBondClient.PATH_SELL)
                .isEqualTo("/uapi/domestic-bond/v1/trading/sell");
        assertThat(KisBondClient.PATH_INQUIRE_DAILY_CCLD)
                .isEqualTo("/uapi/domestic-bond/v1/trading/inquire-daily-ccld");
    }

    @Test
    @DisplayName("채권 코드는 12자리 영숫자 혼합을 허용한다 (숫자 전용·6자리 가정 금지)")
    void bondCodeAcceptsAlphanumericTwelveChars() {
        // 실제 예시값: KR2033022D33, KR6449111CB8 — 뒤쪽에 영문자가 섞인다.
        assertThat("KR2033022D33").matches(KisBondClient.BOND_CODE_PATTERN);
        assertThat("KR6449111CB8").matches(KisBondClient.BOND_CODE_PATTERN);
        assertThat("KR1234567890").matches(KisBondClient.BOND_CODE_PATTERN);
        assertThat("005930").doesNotMatch(KisBondClient.BOND_CODE_PATTERN);
    }
}

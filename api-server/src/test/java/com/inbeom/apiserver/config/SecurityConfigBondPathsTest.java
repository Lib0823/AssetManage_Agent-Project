package com.inbeom.apiserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채권 공개 경로 패턴의 회귀 테스트.
 *
 * <p>스프링 컨텍스트를 띄우지 않고 {@link SecurityConfig#PUBLIC_BOND_QUOTE_PATTERNS} 를
 * 실제 매처({@link PathPatternParser})로 파싱해 직접 매칭한다 — Security 설정을 통째로
 * 부팅하는 것보다 빠르면서, 정작 깨지기 쉬운 지점(패턴 문자열)을 그대로 검증한다.
 *
 * <p><b>이 테스트가 지키는 것</b>: 잔고·매도·거래내역이 인증 없이 열리지 않는다.
 * 종목코드 자리의 12자리 제약을 {@code /bonds/*} 같은 형태로 느슨하게 바꾸면
 * {@code /bonds/balance} 가 "공개 시세" 로 매칭돼 남의 보유 채권이 노출되는데,
 * 그 변경을 여기서 잡는다.
 */
@DisplayName("SecurityConfig — 채권 공개 경로 패턴")
class SecurityConfigBondPathsTest {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<PathPattern> PUBLIC_PATTERNS =
            Arrays.stream(SecurityConfig.PUBLIC_BOND_QUOTE_PATTERNS)
                    .map(PARSER::parse)
                    .toList();

    /** 어떤 공개 패턴에라도 걸리면 비인증 접근이 가능하다는 뜻이다. */
    private static boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return PUBLIC_PATTERNS.stream().anyMatch(p -> p.matches(container));
    }

    @Nested
    @DisplayName("인증이 필요한 경로")
    class Authenticated {

        @Test
        @DisplayName("잔고·매도·거래내역은 공개 패턴에 걸리지 않는다")
        void tradingEndpointsAreNotPublic() {
            assertThat(isPublic("/bonds/balance"))
                    .as("보유 채권 잔고가 인증 없이 열리면 남의 자산이 노출된다")
                    .isFalse();
            assertThat(isPublic("/bonds/history"))
                    .as("거래내역이 인증 없이 열리면 남의 매매 이력이 노출된다")
                    .isFalse();
            assertThat(isPublic("/bonds/sell"))
                    .as("매도는 절대 공개일 수 없다")
                    .isFalse();
        }

        @Test
        @DisplayName("12자리가 아닌 종목코드 자리는 공개로 새지 않는다")
        void nonTwelveCharSegmentsAreNotPublic() {
            assertThat(isPublic("/bonds/KR123")).isFalse();          // 너무 짧음
            assertThat(isPublic("/bonds/KR2033022D33X")).isFalse();  // 너무 김
            assertThat(isPublic("/bonds/KR2033022D3-")).isFalse();   // 영숫자 아닌 문자
        }
    }

    @Nested
    @DisplayName("공개 시세 경로")
    class Public {

        @Test
        @DisplayName("12자리 종목코드의 시세 4종은 공개다")
        void quoteEndpointsArePublic() {
            assertThat(isPublic("/bonds/KR2033022D33")).isTrue();
            assertThat(isPublic("/bonds/KR2033022D33/issue-info")).isTrue();
            assertThat(isPublic("/bonds/KR2033022D33/price")).isTrue();
            assertThat(isPublic("/bonds/KR2033022D33/orderbook")).isTrue();
        }

        @Test
        @DisplayName("영숫자 혼합 종목코드를 숫자 전용으로 좁히지 않았다")
        void alphanumericBondCodesAreAccepted() {
            // 채권 코드는 KR2033022D33 처럼 영문이 섞인다 — \d{12} 로 바꾸면 여기서 깨진다.
            assertThat(isPublic("/bonds/KR6449111CB8/price")).isTrue();
        }
    }
}

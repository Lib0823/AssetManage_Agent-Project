package com.inbeom.apiserver.client;

import com.inbeom.apiserver.config.UpbitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업비트 요청 URL 인코딩 테스트.
 *
 * <p>실제로 났던 버그를 고정한다: 값을 {@code URLEncoder} 로 미리 인코딩한 뒤
 * {@code UriComponentsBuilder} 에 넘기면 {@code %} 가 다시 인코딩돼 다중 마켓 조회의 콤마가
 * {@code %252C} 가 된다. 업비트는 그걸 마켓 코드 하나로 읽고 <b>404 Code not found</b> 를 준다.
 *
 * <p>증상이 고약했던 이유는 <b>호가·캔들 같은 단일 마켓 조회는 멀쩡히 동작</b>했다는 점이다.
 * 콤마가 없으면 이중 인코딩할 것도 없어서, 자산 화면의 평가금액 계산(배치 티커 조회)만
 * 조용히 비어 있었다.
 */
@DisplayName("UpbitApiClient — 요청 URL 인코딩")
class UpbitUrlEncodingTest {

    private final UpbitApiClient client = new UpbitApiClient(new UpbitProperties());

    @Test
    @DisplayName("다중 마켓 조회의 콤마가 이중 인코딩되지 않는다")
    void commaIsNotDoubleEncoded() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", "KRW-BTC,KRW-ETH");

        String url = client.buildUrl("/v1/ticker", params);

        assertThat(url)
                .as("%%252C 는 업비트가 마켓 코드로 읽지 못해 404 를 준다")
                .doesNotContain("%252C");
        assertThat(url).contains("markets=KRW-BTC");
        assertThat(url).contains("KRW-ETH");
    }

    @Test
    @DisplayName("파라미터가 없으면 물음표를 붙이지 않는다")
    void noQueryStringWhenNoParams() {
        assertThat(client.buildUrl("/v1/market/all", Map.of()))
                .isEqualTo("https://api.upbit.com/v1/market/all");
    }

    @Test
    @DisplayName("인증 GET 은 인코딩으로 변형되는 파라미터를 미리 거부한다")
    void authenticatedGetRejectsParamsThatEncodingWouldChange() {
        // query_hash 는 원문을 해싱하는데 URL 은 인코딩된다. 이 상태로 보내면 업비트가
        // 계산한 해시와 어긋나 401 만 돌아오고 원인은 드러나지 않는다 — 나가기 전에 끊는다.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("identifier", "order id");

        assertThatThrownBy(() -> client.getAuthenticated(
                "/v1/order", params, "access", "secret", Map.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query_hash");
    }

    @Test
    @DisplayName("콤마·하이픈처럼 인코딩되지 않는 값은 인증 GET 가드를 통과한다")
    void authenticatedGetAllowsUnencodedSafeValues() {
        // 마켓 코드(KRW-BTC)와 콤마는 쿼리 성분에서 인코딩되지 않는다 — 거부하면 오탐이다.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", "KRW-BTC,KRW-ETH");

        assertThatNoException()
                .isThrownBy(() -> UpbitApiClient.requireEncodingSafeParams(params));
        assertThatNoException()
                .isThrownBy(() -> UpbitApiClient.requireEncodingSafeParams(Map.of()));
    }

    @Test
    @DisplayName("단일 마켓 조회는 그대로 통과한다")
    void singleMarketIsUnaffected() {
        // 이 경로는 버그가 있을 때도 동작했다 — 그래서 회귀를 놓치기 쉽다.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("markets", "KRW-BTC");

        assertThat(client.buildUrl("/v1/orderbook", params))
                .isEqualTo("https://api.upbit.com/v1/orderbook?markets=KRW-BTC");
    }
}

package com.inbeom.apiserver.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업비트 요청 서명(JWT) 규격 테스트.
 *
 * <p><b>이 기능에서 가장 위험한 지점이다.</b> 서명이나 {@code query_hash} 가 틀리면 업비트가
 * 401 만 돌려주고 <b>무엇이 틀렸는지는 알려주지 않는다.</b> 특히 POST 바디 해싱을 잘못하면
 * 조회는 전부 정상 동작하는데 주문만 실패해서, 원인을 엉뚱한 곳에서 찾게 된다.
 *
 * <p>테스트 secret 이 40자인 것은 실수가 아니다. jjwt 는 RFC 7518 최소 키 길이를 강제하므로
 * 짧은 문자열을 쓰면 <b>구현이 맞아도</b> {@code WeakKeyException} 으로 테스트가 실행조차
 * 되지 않는다. 실제 업비트 Secret Key 와 같은 길이를 써서, 덤으로 "실제 키 길이에서 서명이
 * 되는가" 까지 검증한다.
 */
@DisplayName("UpbitApiClient — 요청 서명(JWT) 규격")
class UpbitJwtTest {

    /** 실제 업비트 Secret Key 와 같은 40자(320비트). */
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef01234567";
    private static final String TEST_ACCESS_KEY = "test-access-key";

    private static Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String sha512Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    @Nested
    @DisplayName("파라미터 없는 요청")
    class WithoutParams {

        @Test
        @DisplayName("access_key 와 nonce 만 담고 query_hash 는 넣지 않는다")
        void hasNoQueryHash() {
            // GET /v1/accounts 처럼 파라미터가 없는 요청.
            // 빈 문자열의 해시를 넣으면 업비트가 서명 불일치로 거부한다.
            String token = UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, Map.of());

            Claims claims = parse(token);
            assertThat(claims.get("access_key")).isEqualTo(TEST_ACCESS_KEY);
            assertThat(claims.get("nonce")).isNotNull();
            assertThat(claims.get("query_hash")).isNull();
            assertThat(claims.get("query_hash_alg")).isNull();
        }

        @Test
        @DisplayName("nonce 는 매 호출마다 달라진다")
        void nonceIsUniquePerCall() {
            // 같은 nonce 가 재사용되면 업비트가 재전송 공격으로 보고 거부할 수 있다.
            String a = UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, Map.of());
            String b = UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, Map.of());

            assertThat(parse(a).get("nonce")).isNotEqualTo(parse(b).get("nonce"));
        }
    }

    @Nested
    @DisplayName("파라미터 있는 요청")
    class WithParams {

        @Test
        @DisplayName("쿼리스트링의 SHA512 hex 를 query_hash 로 담는다")
        void hashesQueryString() throws Exception {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("market", "KRW-BTC");
            params.put("side", "bid");

            Claims claims = parse(UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, params));

            assertThat(claims.get("query_hash")).isEqualTo(sha512Hex("market=KRW-BTC&side=bid"));
            assertThat(claims.get("query_hash_alg")).isEqualTo("SHA512");
        }

        @Test
        @DisplayName("POST 바디도 JSON 이 아니라 쿼리스트링 형태를 해싱한다")
        void postBodyHashesQueryStringFormNotJson() throws Exception {
            // 이 계획에서 가장 값비싼 함정. objectMapper.writeValueAsString(body) 를 해싱하면
            // 조회는 멀쩡한데 주문만 401 이 나서 원인 파악이 크게 늦어진다.
            Map<String, String> body = new LinkedHashMap<>();
            body.put("market", "KRW-BTC");
            body.put("side", "bid");
            body.put("ord_type", "price");
            body.put("price", "100000");

            Claims claims = parse(UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, body));

            String queryStringForm = "market=KRW-BTC&side=bid&ord_type=price&price=100000";
            assertThat(claims.get("query_hash")).isEqualTo(sha512Hex(queryStringForm));
        }

        @Test
        @DisplayName("삽입 순서를 유지하고 정렬하지 않는다")
        void preservesInsertionOrderWithoutSorting() throws Exception {
            // 업비트는 정렬하지 않은 순서 그대로를 해싱한다. 알파벳 정렬하면 서명이 어긋난다.
            Map<String, String> params = new LinkedHashMap<>();
            params.put("side", "ask");      // 알파벳 순이면 market 이 먼저 와야 한다
            params.put("market", "KRW-BTC");

            Claims claims = parse(UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, params));

            assertThat(claims.get("query_hash")).isEqualTo(sha512Hex("side=ask&market=KRW-BTC"));
        }
    }

    @Test
    @DisplayName("Secret Key 를 Base64 디코딩하지 않고 raw bytes 로 서명한다")
    void signsWithRawUtf8SecretNotBase64Decoded() {
        // 업비트 Secret Key 는 Base64 가 아니다. 디코딩해서 쓰면 서명이 전부 틀어진다.
        // raw UTF-8 바이트로 만든 키로 검증이 통과한다는 것이 곧 raw 사용의 증거다.
        String token = UpbitApiClient.buildJwt(TEST_ACCESS_KEY, TEST_SECRET, Map.of());

        assertThat(parse(token).get("access_key")).isEqualTo(TEST_ACCESS_KEY);
    }
}

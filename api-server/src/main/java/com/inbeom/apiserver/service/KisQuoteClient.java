package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * KIS 주식현재가 시세(FHKST01010100) 공용 호출 헬퍼.
 *
 * <p>{@link CompanyInfoService} 의 사설(private) 시세 조회 로직을 추출한 컴포넌트로,
 * StockService/FavoriteService 등 현재가가 필요한 곳에서 재사용한다.
 *
 * <p>KIS 시세 API 는 모의(openapivts) 도메인에서 제공되지 않으므로, 매매/잔고 흐름과 분리된
 * 실전 도메인 + 실전 자격증명({@link KisQuoteService}, 설정 kis.quote-*)으로 호출한다.
 * DB 의 모의 UserKisAccount(매매 전용)는 이 경로에서 사용하지 않는다.
 *
 * <p>비활성({@link #isEnabled()}==false)·실패·rt_cd!=0 시 절대 예외를 전파하지 않고
 * null 로 degrade 한다. 미연동 사유는 호출부가 {@link #getNotice()} 로 노출할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KisQuoteClient {

    private final KisQuoteService kisQuoteService;
    private final KisApiClient kisApiClient;

    private static final String MARKET_DIV = "J";

    /**
     * 시세 미연동 시 UI 안내용 메시지.
     */
    public static final String NOTICE_KIS_QUOTE =
            "실시간 시세가 연동되지 않았습니다 (실전 KIS 키 필요)";

    /**
     * 키는 설정됐으나 KIS 점검/일시 오류로 조회에 실패한 경우의 안내.
     * (키 미설정과 구분 — 키가 있는데 "키 필요"라고 잘못 안내하지 않도록.)
     */
    public static final String NOTICE_KIS_UNAVAILABLE =
            "KIS 점검 또는 일시적 연동 오류로 시세를 불러올 수 없습니다";

    /**
     * KIS 조회에 실패했지만 캐시에 남아 있던 마지막 성공값을 대신 보여주는 경우의 안내.
     *
     * <p>"불러올 수 없습니다"({@link #NOTICE_KIS_UNAVAILABLE})와 반드시 구분해야 한다 — 저쪽은
     * 화면에 값이 <b>없다</b>는 뜻이고, 이쪽은 값이 <b>있지만 지금 시점의 값이 아니라는</b> 뜻이다.
     * 문구가 섞이면 사용자가 낡은 가격을 현재가로 오인한다.
     */
    public static final String NOTICE_KIS_STALE =
            "KIS 연동 오류로 마지막으로 조회된 값을 표시합니다 (최신 아님)";

    /**
     * 시세 연동 가능 여부 = quote app key/secret 둘 다 설정됨.
     */
    public boolean isEnabled() {
        return kisQuoteService.isQuoteEnabled();
    }

    /**
     * 시세 미연동 안내 메시지. 연동 정상이면 null.
     */
    public String getNotice() {
        return isEnabled() ? null : NOTICE_KIS_QUOTE;
    }

    /**
     * 시세 데이터가 null 일 때 호출부가 노출할 안내 메시지.
     * 키 미설정이면 "키 필요", 키는 있으나 조회 실패(점검/일시 오류)면 "점검" 문구를 반환한다.
     */
    public String unavailableNotice() {
        return isEnabled() ? NOTICE_KIS_UNAVAILABLE : NOTICE_KIS_QUOTE;
    }

    /**
     * 주식현재가 시세 (FHKST01010100). 실전 시세 도메인 + 실전 quote 자격증명 사용.
     * 비활성/실패/rt_cd!=0 시 null (예외 전파하지 않음).
     *
     * @return KIS 응답 output 맵 (stck_prpr/prdy_vrss/prdy_ctrt 등) 또는 null
     */
    public Map<String, Object> fetchCurrentPrice(String stockCode) {
        return fetchCurrentPriceResult(stockCode).data();
    }

    /**
     * {@link #fetchCurrentPrice} 와 같지만 값이 캐시의 stale 폴백에서 왔는지도 함께 알려준다.
     * 종목 상세처럼 notice 로 "최신 아님"을 안내해야 하는 화면이 쓴다.
     */
    @SuppressWarnings("unchecked")
    public QuoteResult fetchCurrentPriceResult(String stockCode) {
        try {
            QuoteContext ctx = resolveQuoteContext();
            if (ctx == null) {
                return QuoteResult.empty();
            }
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", MARKET_DIV);
            params.put("FID_INPUT_ISCD", stockCode);

            ResponseEntity<Map> response = kisApiClient.get(
                    ctx.baseUrl(),
                    "/uapi/domestic-stock/v1/quotations/inquire-price",
                    "FHKST01010100",
                    ctx.token(),
                    ctx.appKey(),
                    ctx.appSecret(),
                    params,
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            if (!isRtOk(body)) {
                log.warn("KIS inquire-price rt_cd!=0 for stockCode={}: {}", stockCode,
                        body != null ? body.get("msg1") : "null body");
                return QuoteResult.empty();
            }
            Object output = body.get("output");
            return new QuoteResult((output instanceof Map) ? (Map<String, Object>) output : null,
                    KisApiClient.isStale(response));
        } catch (Exception e) {
            log.warn("KIS inquire-price call failed for stockCode={}: {}", stockCode, e.getMessage());
            return QuoteResult.empty();
        }
    }

    /**
     * 주식현재가 호가/예상체결 (FHKST01010200). 실전 시세 도메인 + 실전 quote 자격증명 사용.
     * 비활성/실패/rt_cd!=0 시 null (예외 전파하지 않음).
     *
     * <p>호가 데이터는 응답 body 의 {@code output1}(NOT output)에 있다:
     * askp1..askp10/bidp1..bidp10(호가), askp_rsqn1..askp_rsqn10/bidp_rsqn1..bidp_rsqn10(잔량).
     *
     * @return KIS 응답 output1 맵 또는 null
     */
    public Map<String, Object> fetchOrderbook(String stockCode) {
        return fetchOrderbookResult(stockCode).data();
    }

    /** {@link #fetchOrderbook} 의 stale 여부까지 알려주는 변형. */
    @SuppressWarnings("unchecked")
    public QuoteResult fetchOrderbookResult(String stockCode) {
        try {
            QuoteContext ctx = resolveQuoteContext();
            if (ctx == null) {
                return QuoteResult.empty();
            }
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", MARKET_DIV);
            params.put("FID_INPUT_ISCD", stockCode);

            ResponseEntity<Map> response = kisApiClient.get(
                    ctx.baseUrl(),
                    "/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn",
                    "FHKST01010200",
                    ctx.token(),
                    ctx.appKey(),
                    ctx.appSecret(),
                    params,
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            if (!isRtOk(body)) {
                log.warn("KIS inquire-asking-price rt_cd!=0 for stockCode={}: {}", stockCode,
                        body != null ? body.get("msg1") : "null body");
                return QuoteResult.empty();
            }
            Object output1 = body.get("output1");
            return new QuoteResult((output1 instanceof Map) ? (Map<String, Object>) output1 : null,
                    KisApiClient.isStale(response));
        } catch (Exception e) {
            log.warn("KIS inquire-asking-price call failed for stockCode={}: {}", stockCode, e.getMessage());
            return QuoteResult.empty();
        }
    }

    private boolean isRtOk(Map<String, Object> body) {
        return body != null && "0".equals(String.valueOf(body.get("rt_cd")));
    }

    /**
     * 시세 호출용 실전(real) 자격증명·토큰·도메인 해석.
     * 매매 흐름(DB 모의 키)과 분리된 설정(kis.quote-*) 기반.
     * 비활성(키 미설정)이거나 토큰 획득 실패 시 null → 시세 필드 전체 degrade.
     */
    private QuoteContext resolveQuoteContext() {
        if (!kisQuoteService.isQuoteEnabled()) {
            log.warn("KIS quote credentials not configured; quotation fields will be null");
            return null;
        }
        String token = kisQuoteService.getQuoteAccessToken();
        if (token == null) {
            log.warn("KIS quote token unavailable; quotation fields will be null");
            return null;
        }
        return new QuoteContext(
                kisQuoteService.getQuoteBaseUrl(),
                token,
                kisQuoteService.getQuoteAppKey(),
                kisQuoteService.getQuoteAppSecret()
        );
    }

    private record QuoteContext(String baseUrl, String token, String appKey, String appSecret) {}

    /**
     * @param data  KIS 응답 output (없으면 null — 기존 degrade 계약 그대로)
     * @param stale 값이 캐시의 마지막 성공값 폴백이면 true → 호출부가 {@link #NOTICE_KIS_STALE} 안내
     */
    public record QuoteResult(Map<String, Object> data, boolean stale) {

        static QuoteResult empty() {
            return new QuoteResult(null, false);
        }
    }
}

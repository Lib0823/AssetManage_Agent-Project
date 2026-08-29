package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.UpbitApiClient;
import com.inbeom.apiserver.dto.coin.CoinCandleListResponse;
import com.inbeom.apiserver.dto.coin.CoinCandleResponse;
import com.inbeom.apiserver.dto.coin.CoinMarketListResponse;
import com.inbeom.apiserver.dto.coin.CoinMarketResponse;
import com.inbeom.apiserver.dto.coin.CoinOrderbookResponse;
import com.inbeom.apiserver.dto.coin.CoinOrderbookResponse.CoinOrderbookUnit;
import com.inbeom.apiserver.dto.coin.CoinTickerResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.inbeom.apiserver.service.CoinResponses.decimal;
import static com.inbeom.apiserver.service.CoinResponses.epochMillis;
import static com.inbeom.apiserver.service.CoinResponses.string;
import static com.inbeom.apiserver.service.CoinResponses.toMaps;

/**
 * 업비트 공개 시세 조회 (마켓 목록 · 현재가 · 호가 · 캔들).
 *
 * <p>인증이 필요 없는 경로다. 사용자별 키가 아니라 <b>서버 공인 IP</b> 로 나가므로 rate limit 버킷이
 * 전 사용자 공유다({@code UpbitApiClient} 가 관리).
 *
 * <p><b>절대 예외를 전파하지 않는다.</b> 코인 상세 화면은 티커·호가·캔들을 병렬로 부른 뒤 되는
 * 것만 그린다. 하나가 예외로 끊기면 화면 전체가 사라지므로 실패는 값 null/빈 목록 +
 * {@code notice} 로 내려간다. (단, <b>잘못된 요청 파라미터</b>는 degrade 대상이 아니라 400 이다 —
 * 업비트 장애가 아니라 호출부의 버그이므로 숨기면 안 된다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinQuoteService {

    /** 원화 마켓만 다룬다. BTC/USDT 마켓은 이 기능의 범위 밖이다. */
    private static final String KRW_PREFIX = "KRW-";

    private static final String NOTICE_UPBIT_UNAVAILABLE =
            "업비트 시세를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /**
     * 캔들 단위 화이트리스트.
     *
     * <p>{@code days}/{@code weeks}/{@code months} 는 {@code /v1/candles/{unit}} 로, 숫자는
     * <b>분봉</b>이라 {@code /v1/candles/minutes/{n}} 로 간다 — 경로 규칙이 갈리므로 임의 문자열을
     * 그대로 경로에 넣으면 경로 조작이 된다. 그래서 화이트리스트 외에는 400 으로 거절한다.
     */
    private static final Set<String> UNIT_PERIODS = Set.of("days", "weeks", "months");
    private static final Set<String> UNIT_MINUTES = Set.of("1", "3", "5", "10", "15", "30", "60", "240");

    /** 업비트 상한. 초과 요청도 200 으로 응답하되 200개만 오므로 여기서 잘라 계약을 명확히 한다. */
    private static final int MAX_CANDLE_COUNT = 200;

    private final UpbitApiClient upbitApiClient;

    /**
     * 원화마켓 목록. {@code isDetails=true} 로 받아 유의/주의 플래그를 함께 담는다.
     *
     * <p>업비트는 849개 마켓(KRW 288 · BTC 327 · USDT 234)을 한 번에 주므로 <b>서버에서 KRW 만
     * 걸러</b> 내려보낸다. 프론트가 거르면 3배 크기의 응답을 매번 전송하게 된다.
     */
    public CoinMarketListResponse getKrwMarkets() {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("isDetails", "true");

            List<Map<String, Object>> raw = toMaps(
                    upbitApiClient.getPublic("/v1/market/all", params, Map[].class).getBody());

            List<CoinMarketResponse> markets = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                String market = string(row, "market");
                if (market == null || !market.startsWith(KRW_PREFIX)) {
                    continue;
                }
                markets.add(mapMarket(market, row));
            }
            markets.sort(Comparator.comparing(CoinMarketResponse::getMarket));
            return CoinMarketListResponse.builder().markets(markets).build();
        } catch (Exception e) {
            log.warn("Upbit market list lookup failed: {}", e.getMessage());
            return CoinMarketListResponse.builder()
                    .markets(List.of())
                    .notice(NOTICE_UPBIT_UNAVAILABLE)
                    .build();
        }
    }

    /**
     * 현재가 <b>배치</b> 조회. 단건 조회 메서드를 따로 두지 않는다.
     *
     * <p>이유는 자산 화면이다 — 보유 코인마다 티커를 부르면 IP 단위 10 req/s 한도를 즉시 소진해
     * <b>다른 모든 사용자의 시세까지 굶긴다.</b> 배치가 기본형이어야 그 사고가 구조적으로 안 난다.
     *
     * @return 요청한 마켓 수만큼. 실패 시에도 각 마켓에 대해 {@code notice} 만 채운 항목을 돌려준다
     *         (화면이 "어느 코인이 실패했는지"를 잃지 않도록).
     */
    public List<CoinTickerResponse> getTickers(List<String> markets) {
        List<String> targets = normalizeMarkets(markets);
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("markets", String.join(",", targets));

            List<Map<String, Object>> raw = toMaps(
                    upbitApiClient.getPublic("/v1/ticker", params, Map[].class).getBody());

            List<CoinTickerResponse> result = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                result.add(mapTicker(row));
            }
            return result;
        } catch (Exception e) {
            log.warn("Upbit ticker lookup failed for {}: {}", targets, e.getMessage());
            return targets.stream()
                    .map(m -> CoinTickerResponse.builder()
                            .market(m)
                            .notice(NOTICE_UPBIT_UNAVAILABLE)
                            .build())
                    .toList();
        }
    }

    /** 15단계 호가. 실패 시 빈 {@code units} + {@code notice}. */
    public CoinOrderbookResponse getOrderbook(String market) {
        String target = requireKrwMarket(market);
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("markets", target);

            List<Map<String, Object>> raw = toMaps(
                    upbitApiClient.getPublic("/v1/orderbook", params, Map[].class).getBody());
            if (raw.isEmpty()) {
                return emptyOrderbook(target, NOTICE_UPBIT_UNAVAILABLE);
            }
            return mapOrderbook(target, raw.get(0));
        } catch (Exception e) {
            log.warn("Upbit orderbook lookup failed for {}: {}", target, e.getMessage());
            return emptyOrderbook(target, NOTICE_UPBIT_UNAVAILABLE);
        }
    }

    /**
     * 캔들 조회.
     *
     * @param unit  {@code days}/{@code weeks}/{@code months} 또는 분봉 숫자
     *              ({@code 1,3,5,10,15,30,60,240}). 그 외는 400.
     * @param count 1~200. 범위를 벗어나면 잘라 낸다.
     */
    public CoinCandleListResponse getCandles(String market, String unit, int count) {
        String target = requireKrwMarket(market);
        String path = candlePath(unit);
        int safeCount = Math.clamp(count, 1, MAX_CANDLE_COUNT);

        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("market", target);
            params.put("count", String.valueOf(safeCount));

            List<Map<String, Object>> raw = toMaps(
                    upbitApiClient.getPublic(path, params, Map[].class).getBody());

            List<CoinCandleResponse> candles = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                candles.add(mapCandle(row));
            }
            // 업비트는 최신→과거 순으로 준다. 차트는 과거→현재로 그리므로 여기서 뒤집는다.
            java.util.Collections.reverse(candles);
            return CoinCandleListResponse.builder().market(target).unit(unit).candles(candles).build();
        } catch (Exception e) {
            log.warn("Upbit candle lookup failed for {} unit={}: {}", target, unit, e.getMessage());
            return CoinCandleListResponse.builder()
                    .market(target).unit(unit).candles(List.of())
                    .notice(NOTICE_UPBIT_UNAVAILABLE)
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // 매핑
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private CoinMarketResponse mapMarket(String market, Map<String, Object> row) {
        boolean warning = false;
        List<String> cautions = new ArrayList<>();

        Object event = row.get("market_event");
        if (event instanceof Map<?, ?> eventMap) {
            warning = Boolean.TRUE.equals(eventMap.get("warning"));
            Object caution = eventMap.get("caution");
            if (caution instanceof Map<?, ?> cautionMap) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) cautionMap).entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) {
                        cautions.add(e.getKey());
                    }
                }
            }
        }

        return CoinMarketResponse.builder()
                .market(market)
                .symbol(market.substring(KRW_PREFIX.length()))
                .koreanName(string(row, "korean_name"))
                .englishName(string(row, "english_name"))
                .warning(warning)
                .cautions(cautions)
                .build();
    }

    private CoinTickerResponse mapTicker(Map<String, Object> row) {
        return CoinTickerResponse.builder()
                .market(string(row, "market"))
                .tradePrice(decimal(row, "trade_price"))
                .openingPrice(decimal(row, "opening_price"))
                .highPrice(decimal(row, "high_price"))
                .lowPrice(decimal(row, "low_price"))
                .prevClosingPrice(decimal(row, "prev_closing_price"))
                .change(string(row, "change"))
                // signed_* 를 쓴다 — change_price/change_rate 는 절대값이라 하락을 상승처럼 그린다.
                .signedChangePrice(decimal(row, "signed_change_price"))
                .signedChangeRate(decimal(row, "signed_change_rate"))
                .accTradePrice24h(decimal(row, "acc_trade_price_24h"))
                .accTradeVolume24h(decimal(row, "acc_trade_volume_24h"))
                .highest52WeekPrice(decimal(row, "highest_52_week_price"))
                .lowest52WeekPrice(decimal(row, "lowest_52_week_price"))
                .timestamp(epochMillis(row, "timestamp"))
                .build();
    }

    private CoinOrderbookResponse mapOrderbook(String market, Map<String, Object> row) {
        List<CoinOrderbookUnit> units = new ArrayList<>();
        Object rawUnits = row.get("orderbook_units");
        if (rawUnits instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> unit) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> u = (Map<String, Object>) unit;
                    units.add(CoinOrderbookUnit.builder()
                            .askPrice(decimal(u, "ask_price"))
                            .askSize(decimal(u, "ask_size"))
                            .bidPrice(decimal(u, "bid_price"))
                            .bidSize(decimal(u, "bid_size"))
                            .build());
                }
            }
        }
        return CoinOrderbookResponse.builder()
                .market(market)
                .timestamp(epochMillis(row, "timestamp"))
                .totalAskSize(decimal(row, "total_ask_size"))
                .totalBidSize(decimal(row, "total_bid_size"))
                .units(units)
                .build();
    }

    private CoinCandleResponse mapCandle(Map<String, Object> row) {
        return CoinCandleResponse.builder()
                .market(string(row, "market"))
                .candleDateTimeUtc(string(row, "candle_date_time_utc"))
                .candleDateTimeKst(string(row, "candle_date_time_kst"))
                .openingPrice(decimal(row, "opening_price"))
                .highPrice(decimal(row, "high_price"))
                .lowPrice(decimal(row, "low_price"))
                .tradePrice(decimal(row, "trade_price"))
                .prevClosingPrice(decimal(row, "prev_closing_price"))
                // 캔들의 change_* 는 부호가 있다 (ticker 와 반대).
                .changePrice(decimal(row, "change_price"))
                .changeRate(decimal(row, "change_rate"))
                .candleAccTradePrice(decimal(row, "candle_acc_trade_price"))
                .candleAccTradeVolume(decimal(row, "candle_acc_trade_volume"))
                .timestamp(epochMillis(row, "timestamp"))
                .build();
    }

    private CoinOrderbookResponse emptyOrderbook(String market, String notice) {
        return CoinOrderbookResponse.builder()
                .market(market)
                .units(List.of())
                .notice(notice)
                .build();
    }

    // ------------------------------------------------------------------
    // 입력 검증
    // ------------------------------------------------------------------

    /** 대문자 정규화 + KRW 마켓 확인. 원화 외 마켓은 이 기능의 범위 밖이므로 400 이다. */
    String requireKrwMarket(String market) {
        if (market == null || market.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "마켓 코드가 필요합니다");
        }
        String normalized = market.trim().toUpperCase();
        if (!normalized.startsWith(KRW_PREFIX) || normalized.length() <= KRW_PREFIX.length()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "원화(KRW) 마켓만 지원합니다: " + market);
        }
        return normalized;
    }

    private List<String> normalizeMarkets(List<String> markets) {
        if (markets == null || markets.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "조회할 마켓 코드가 필요합니다");
        }
        List<String> normalized = new ArrayList<>();
        for (String m : markets) {
            String one = requireKrwMarket(m);
            if (!normalized.contains(one)) {
                normalized.add(one);
            }
        }
        return normalized;
    }

    /** 화이트리스트 밖의 단위는 경로에 넣지 않고 400 으로 끊는다. */
    private String candlePath(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "캔들 단위(unit)가 필요합니다");
        }
        String u = unit.trim().toLowerCase();
        if (UNIT_PERIODS.contains(u)) {
            return "/v1/candles/" + u;
        }
        if (UNIT_MINUTES.contains(u)) {
            return "/v1/candles/minutes/" + u;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                "지원하지 않는 캔들 단위입니다: " + unit + " (days/weeks/months 또는 1,3,5,10,15,30,60,240)");
    }
}

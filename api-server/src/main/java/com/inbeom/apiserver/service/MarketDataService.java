package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.dto.market.ExchangeRateResponse;
import com.inbeom.apiserver.dto.market.IndicesResponse;
import com.inbeom.apiserver.dto.market.NewsItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 홈 화면 위젯(주요지수 / 환율 / 속보)용 외부 데이터 오케스트레이션.
 *
 * <p>세 엔드포인트 모두 읽기 전용이며 외부 소스 실패 시 부분/빈 데이터로 degrade 한다(절대 예외 전파 금지).
 * <ul>
 *     <li>주요지수: KIS 국내 지수 시세(inquire-index-price). {@link KisQuoteService} 의 캐시된
 *         실전 토큰을 재사용해 KIS OAuth 1분 throttle 을 회피한다.</li>
 *     <li>환율: frankfurter.app (ECB, 무료/키 불필요, 과거 시계열 지원).</li>
 *     <li>속보: 경제 RSS 피드(한국경제/매일경제/연합뉴스) StAX 파싱.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final KisQuoteService kisQuoteService;
    private final KisApiClient kisApiClient;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        return new RestTemplate(factory);
    }

    // -----------------------------------------------------------------
    // 1) 주요지수 (KIS 국내 지수)
    // -----------------------------------------------------------------

    // 지수 결과 캐시 + 마지막 성공값 폴백: KIS 가 느리거나 일시 실패해도
    // 홈 화면이 매 요청 KIS 를 때리지 않고, 한 번 성공한 뒤엔 비지 않도록 한다.
    private static final long INDICES_TTL_MS = 60_000L;
    private volatile IndicesResponse cachedIndices;
    private volatile long cachedIndicesAt;

    private static final String INDEX_ENDPOINT =
            "/uapi/domestic-stock/v1/quotations/inquire-index-price";
    private static final String INDEX_TR_ID = "FHKUP03500100";

    /** 국내 지수: (label, KIS 업종코드). 4번째는 코스피200 다음으로 KRX300. */
    private static final Map<String, String> DOMESTIC_INDICES = new LinkedHashMap<>() {{
        put("코스피", "0001");
        put("코스닥", "1001");
        put("코스피200", "2001");
        put("KRX300", "0028");
    }};

    // 해외 지수: KIS 해외주식 종목/지수/환율 기간별시세(FHKST03030100, 시장코드 N=해외지수).
    // 공식 문서상 미국은 다우30/나스닥100/S&P500 지수만 조회 가능. 지수코드(FID_INPUT_ISCD)는
    // KIS 해외지수 마스터 기준 — MUST-VERIFY(.DJI/COMP/SPX). 권한(엔타이틀먼트) 없으면 graceful degrade.
    private static final String OVERSEAS_INDEX_ENDPOINT =
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
    private static final String OVERSEAS_INDEX_TR_ID = "FHKST03030100";

    /** 해외 지수: (label, KIS 해외지수코드). */
    private static final Map<String, String> OVERSEAS_INDICES = new LinkedHashMap<>() {{
        put("다우존스", ".DJI");
        put("나스닥", "COMP");
        put("S&P500", "SPX");
    }};

    public IndicesResponse getIndices() {
        // 신선한 캐시면 그대로 반환 (KIS 재호출 회피).
        IndicesResponse cached = cachedIndices;
        if (cached != null && (System.currentTimeMillis() - cachedIndicesAt) < INDICES_TTL_MS) {
            return cached;
        }

        List<IndicesResponse.IndexItem> domestic = fetchDomesticIndices();
        List<IndicesResponse.IndexCategory> categories = new ArrayList<>();
        categories.add(IndicesResponse.IndexCategory.builder()
                .key("domestic")
                .label("주식(국내)")
                .items(domestic)
                .build());

        // 해외 지수(다우/나스닥/S&P500): KIS FHKST03030100(시장코드 N)로 조회. 권한/실패 시
        // 빈 목록이면 카테고리를 추가하지 않아 프론트가 mock 으로 폴백한다(가짜 데이터 미주입).
        List<IndicesResponse.IndexItem> overseas = fetchOverseasIndices();
        if (!overseas.isEmpty()) {
            categories.add(IndicesResponse.IndexCategory.builder()
                    .key("overseas")
                    .label("주식(해외)")
                    .items(overseas)
                    .build());
        }

        IndicesResponse fresh = IndicesResponse.builder().categories(categories).build();

        if (!domestic.isEmpty() || !overseas.isEmpty()) {
            cachedIndices = fresh;
            cachedIndicesAt = System.currentTimeMillis();
            return fresh;
        }
        // 이번 조회 실패/빈값이지만 직전 성공값이 있으면 그것을 제공(stale-on-error).
        if (cached != null) {
            log.warn("indices fetch returned empty; serving last-good cached indices");
            return cached;
        }
        return fresh;
    }

    private List<IndicesResponse.IndexItem> fetchDomesticIndices() {
        List<IndicesResponse.IndexItem> items = new ArrayList<>();

        if (!kisQuoteService.isQuoteEnabled()) {
            log.warn("KIS quote credentials not configured; /market/indices domestic degrades to empty");
            return items;
        }
        String token = kisQuoteService.getQuoteAccessToken();
        if (token == null) {
            log.warn("KIS quote token unavailable; /market/indices domestic degrades to empty");
            return items;
        }
        String baseUrl = kisQuoteService.getQuoteBaseUrl();
        String appKey = kisQuoteService.getQuoteAppKey();
        String appSecret = kisQuoteService.getQuoteAppSecret();

        for (Map.Entry<String, String> entry : DOMESTIC_INDICES.entrySet()) {
            IndicesResponse.IndexItem item =
                    fetchSingleIndex(baseUrl, token, appKey, appSecret, entry.getKey(), entry.getValue());
            if (item != null) {
                items.add(item);
            } else if (items.isEmpty()) {
                // 첫 호출부터 실패(주로 KIS 도달 불가/타임아웃)면 같은 토큰·연결로 나머지도 실패할
                // 가능성이 높다. 4회 타임아웃이 누적돼 응답이 지연되는 것을 막기 위해 즉시 중단.
                log.warn("First KIS index fetch failed; skipping remaining indices to avoid stacked timeouts");
                break;
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private IndicesResponse.IndexItem fetchSingleIndex(
            String baseUrl, String token, String appKey, String appSecret,
            String label, String code) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "U");
            params.put("FID_INPUT_ISCD", code);
            params.put("FID_INPUT_DATE_1", "");
            params.put("FID_INPUT_DATE_2", "");
            params.put("FID_PERIOD_DIV_CODE", "D");

            ResponseEntity<Map> response = kisApiClient.get(
                    baseUrl, INDEX_ENDPOINT, INDEX_TR_ID,
                    token, appKey, appSecret, params, Map.class);

            Map<String, Object> body = response.getBody();
            if (!isRtOk(body)) {
                log.warn("KIS inquire-index-price rt_cd!=0 for {}({}): {}", label, code,
                        body != null ? body.get("msg1") : "null body");
                return null;
            }
            Object output1 = body.get("output1");
            if (!(output1 instanceof Map)) {
                log.warn("KIS inquire-index-price missing output1 for {}({})", label, code);
                return null;
            }
            Map<String, Object> out = (Map<String, Object>) output1;
            return IndicesResponse.IndexItem.builder()
                    .label(label)
                    .value(parseBigDecimal(out.get("bstp_nmix_prpr")))
                    .change(parseBigDecimal(out.get("bstp_nmix_prdy_vrss")))
                    .changePercent(parseBigDecimal(out.get("bstp_nmix_prdy_ctrt")))
                    .build();
        } catch (Exception e) {
            log.warn("KIS inquire-index-price call failed for {}({}): {}", label, code, e.getMessage());
            return null;
        }
    }

    private List<IndicesResponse.IndexItem> fetchOverseasIndices() {
        List<IndicesResponse.IndexItem> items = new ArrayList<>();
        if (!kisQuoteService.isQuoteEnabled()) {
            return items;
        }
        String token = kisQuoteService.getQuoteAccessToken();
        if (token == null) {
            return items;
        }
        String baseUrl = kisQuoteService.getQuoteBaseUrl();
        String appKey = kisQuoteService.getQuoteAppKey();
        String appSecret = kisQuoteService.getQuoteAppSecret();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
        String end = LocalDate.now().format(fmt);
        String start = LocalDate.now().minusDays(14).format(fmt);

        for (Map.Entry<String, String> entry : OVERSEAS_INDICES.entrySet()) {
            IndicesResponse.IndexItem item = fetchSingleOverseasIndex(
                    baseUrl, token, appKey, appSecret, entry.getKey(), entry.getValue(), start, end);
            if (item != null) {
                items.add(item);
            } else if (items.isEmpty()) {
                // 첫 호출부터 실패(권한 없음/도달 불가)면 나머지도 실패 가능성이 높아 즉시 중단(타임아웃 누적 방지).
                log.warn("First KIS overseas index fetch failed; skipping remaining to avoid stacked timeouts");
                break;
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private IndicesResponse.IndexItem fetchSingleOverseasIndex(
            String baseUrl, String token, String appKey, String appSecret,
            String label, String code, String start, String end) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("FID_COND_MRKT_DIV_CODE", "N");  // N = 해외지수
            params.put("FID_INPUT_ISCD", code);
            params.put("FID_INPUT_DATE_1", start);
            params.put("FID_INPUT_DATE_2", end);
            params.put("FID_PERIOD_DIV_CODE", "D");

            ResponseEntity<Map> response = kisApiClient.get(
                    baseUrl, OVERSEAS_INDEX_ENDPOINT, OVERSEAS_INDEX_TR_ID,
                    token, appKey, appSecret, params, Map.class);

            Map<String, Object> body = response.getBody();
            if (!isRtOk(body)) {
                log.warn("KIS overseas index rt_cd!=0 for {}({}): {}", label, code,
                        body != null ? body.get("msg1") : "null body");
                return null;
            }

            // output1: 지수 요약(현재가/전일대비). 필드명 MUST-VERIFY → 후보 키 순차 탐색.
            BigDecimal value = null, change = null, pct = null;
            Object o1 = body.get("output1");
            if (o1 instanceof Map) {
                Map<String, Object> out1 = (Map<String, Object>) o1;
                value = firstNonNull(out1, "ovrs_nmix_prpr", "ovrs_prpr", "stck_prpr");
                change = firstNonNull(out1, "ovrs_nmix_prdy_vrss", "prdy_vrss");
                pct = firstNonNull(out1, "prdy_ctrt", "ovrs_nmix_prdy_ctrt");
            }

            // output1 에 값이 없으면 output2(일별 캔들, 최신순)의 종가/전일대비로 도출.
            if (value == null) {
                Object o2 = body.get("output2");
                if (o2 instanceof List) {
                    List<Object> candles = (List<Object>) o2;
                    BigDecimal v0 = candleClose(candles, 0);
                    BigDecimal v1 = candleClose(candles, 1);
                    value = v0;
                    if (v0 != null && v1 != null && v1.signum() != 0) {
                        change = v0.subtract(v1);
                        pct = v0.subtract(v1).multiply(BigDecimal.valueOf(100))
                                .divide(v1, 2, java.math.RoundingMode.HALF_UP);
                    }
                }
            }

            if (value == null) {
                log.warn("KIS overseas index no value parsed for {}({})", label, code);
                return null;
            }
            return IndicesResponse.IndexItem.builder()
                    .label(label).value(value).change(change).changePercent(pct).build();
        } catch (Exception e) {
            log.warn("KIS overseas index call failed for {}({}): {}", label, code, e.getMessage());
            return null;
        }
    }

    /** map 에서 후보 키들을 순서대로 시도해 첫 파싱 성공값 반환. 없으면 null. */
    private BigDecimal firstNonNull(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            BigDecimal v = parseBigDecimal(map.get(k));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** output2 캔들 리스트의 idx 번째 종가. 후보 키 탐색. */
    @SuppressWarnings("unchecked")
    private BigDecimal candleClose(List<Object> candles, int idx) {
        if (candles == null || idx >= candles.size()) {
            return null;
        }
        Object c = candles.get(idx);
        if (!(c instanceof Map)) {
            return null;
        }
        return firstNonNull((Map<String, Object>) c, "ovrs_nmix_prpr", "clos", "stck_clpr");
    }

    // -----------------------------------------------------------------
    // 2) 환율 (frankfurter.app)
    // -----------------------------------------------------------------

    private static final String FX_BASE = "https://api.frankfurter.app";

    /** (currency, country, 100엔 환산 여부). */
    private record FxSpec(String currency, String country, boolean per100) {}

    private static final List<FxSpec> FX_SPECS = List.of(
            new FxSpec("USD", "미국", false),
            new FxSpec("JPY", "일본", true),
            new FxSpec("EUR", "유럽", false),
            new FxSpec("CNY", "중국", false)
    );

    public List<ExchangeRateResponse> getExchangeRates() {
        List<ExchangeRateResponse> result = new ArrayList<>();
        for (FxSpec spec : FX_SPECS) {
            result.add(fetchExchangeRate(spec));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ExchangeRateResponse fetchExchangeRate(FxSpec spec) {
        BigDecimal multiplier = spec.per100() ? new BigDecimal("100") : BigDecimal.ONE;

        BigDecimal rate = null;
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal changePercent = BigDecimal.ZERO;
        List<BigDecimal> history = new ArrayList<>();

        // --- history(최근 ~7 영업일) : timeseries 엔드포인트로 한 번에 조회 ---
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(14); // 주말/공휴일 감안해 넉넉히
            String url = FX_BASE + "/" + start + ".." + end + "?from=" + spec.currency() + "&to=KRW";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.get("rates") instanceof Map<?, ?> ratesMap) {
                // 날짜 오름차순 정렬
                TreeMap<String, BigDecimal> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) ratesMap).entrySet()) {
                    BigDecimal v = extractKrw(e.getValue(), multiplier);
                    if (v != null) {
                        sorted.put(String.valueOf(e.getKey()), v);
                    }
                }
                List<BigDecimal> ordered = new ArrayList<>(sorted.values());
                if (!ordered.isEmpty()) {
                    // 마지막 7개를 history 로
                    int from = Math.max(0, ordered.size() - 7);
                    history = new ArrayList<>(ordered.subList(from, ordered.size()));
                    rate = ordered.get(ordered.size() - 1);
                    if (ordered.size() >= 2) {
                        BigDecimal prev = ordered.get(ordered.size() - 2);
                        change = rate.subtract(prev);
                        if (prev.signum() != 0) {
                            changePercent = change.divide(prev, 6, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100"))
                                    .setScale(2, RoundingMode.HALF_UP);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("frankfurter timeseries failed for {}: {}", spec.currency(), e.getMessage());
        }

        // --- timeseries 가 비었으면 latest 단건으로 rate 만이라도 확보 ---
        if (rate == null) {
            try {
                String url = FX_BASE + "/latest?from=" + spec.currency() + "&to=KRW";
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                Map<String, Object> body = response.getBody();
                if (body != null && body.get("rates") instanceof Map<?, ?> ratesMap) {
                    rate = extractKrw(((Map<?, ?>) ratesMap).get("KRW"), multiplier);
                }
            } catch (Exception e) {
                log.warn("frankfurter latest failed for {}: {}", spec.currency(), e.getMessage());
            }
        }

        return ExchangeRateResponse.builder()
                .currency(spec.currency())
                .country(spec.country())
                .rate(rate)
                .change(change)
                .changePercent(changePercent)
                .history(history)
                .build();
    }

    private BigDecimal extractKrw(Object raw, BigDecimal multiplier) {
        // raw 가 단일 숫자(KRW 값)이거나, {"KRW": value} 맵일 수 있음
        if (raw instanceof Map<?, ?> m) {
            raw = m.get("KRW");
        }
        BigDecimal v = parseBigDecimal(raw);
        if (v == null) {
            return null;
        }
        return v.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    // -----------------------------------------------------------------
    // 3) 속보 (경제 RSS)
    // -----------------------------------------------------------------

    private record RssFeed(String url, String source) {}

    private static final List<RssFeed> RSS_FEEDS = List.of(
            new RssFeed("https://www.hankyung.com/feed/finance", "한국경제"),
            new RssFeed("https://www.mk.co.kr/rss/30000001/", "매일경제"),
            new RssFeed("https://www.yna.co.kr/rss/economy.xml", "연합뉴스")
    );

    private static final DateTimeFormatter OUT_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public List<NewsItemResponse> getNews() {
        List<RssItem> all = new ArrayList<>();
        for (RssFeed feed : RSS_FEEDS) {
            try {
                all.addAll(parseFeed(feed));
            } catch (Exception e) {
                log.warn("RSS feed parse failed for {}: {}", feed.source(), e.getMessage());
            }
        }

        // pubDate 내림차순 정렬 (null pubDate 는 뒤로)
        all.sort(Comparator.comparing(
                (RssItem r) -> r.pubDate != null ? r.pubDate : LocalDateTime.MIN).reversed());

        // 제목 기준 dedupe, 최신 ~8개
        List<NewsItemResponse> result = new ArrayList<>();
        java.util.Set<String> seenTitles = new java.util.HashSet<>();
        for (RssItem item : all) {
            if (item.title == null || item.title.isBlank()) {
                continue;
            }
            String key = item.title.trim();
            if (!seenTitles.add(key)) {
                continue;
            }
            result.add(NewsItemResponse.builder()
                    .id(stableId(item))
                    .title(item.title.trim())
                    .description(item.description)
                    .source(item.source)
                    .date(item.pubDate != null ? item.pubDate.format(OUT_DATE) : null)
                    .link(item.link)
                    .image(item.image)
                    .build());
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    /** RSS <item> 파싱 결과 (내부용). */
    private static class RssItem {
        String title;
        String link;
        String description;
        String guid;
        String image;
        String source;
        LocalDateTime pubDate;
    }

    private List<RssItem> parseFeed(RssFeed feed) throws Exception {
        // 일부 피드(매일경제 등)는 gzip 을 강제로 내려주는데 RestTemplate 의 byte[] 변환은
        // 자동 압축 해제를 하지 않으므로 Accept-Encoding: identity 로 비압축 응답을 요청한다.
        // (브라우저 UA 는 일부 피드가 다른 페이지를 반환하므로 설정하지 않는다.)
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.ACCEPT_ENCODING, "identity");
        org.springframework.http.HttpEntity<Void> request =
                new org.springframework.http.HttpEntity<>(headers);
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                feed.url(), org.springframework.http.HttpMethod.GET, request, byte[].class);
        byte[] raw = resp.getBody();
        if (raw == null || raw.length == 0) {
            return List.of();
        }

        List<RssItem> items = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 외부 엔티티 비활성화 (XXE 방지)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLStreamReader reader = factory.createXMLStreamReader(
                new ByteArrayInputStream(raw), "UTF-8");

        RssItem current = null;
        String localName = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    localName = reader.getLocalName();
                    if ("item".equals(localName)) {
                        current = new RssItem();
                        current.source = feed.source();
                    } else if (current != null) {
                        // media:thumbnail / media:content / enclosure 의 url 속성
                        if (("thumbnail".equals(localName) || "content".equals(localName)
                                || "enclosure".equals(localName)) && current.image == null) {
                            String url = reader.getAttributeValue(null, "url");
                            if (url != null && !url.isBlank()) {
                                current.image = url.trim();
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (current != null && localName != null) {
                        String text = reader.getText();
                        if (text != null && !text.isBlank()) {
                            applyText(current, localName, text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("item".equals(reader.getLocalName())) {
                        if (current != null) {
                            items.add(current);
                            current = null;
                        }
                    }
                    localName = null;
                }
            }
        } finally {
            reader.close();
        }
        return items;
    }

    private void applyText(RssItem item, String tag, String text) {
        String value = text.trim();
        switch (tag) {
            case "title" -> item.title = appendText(item.title, value);
            case "link" -> {
                if (item.link == null || item.link.isBlank()) {
                    item.link = value;
                }
            }
            case "description" -> item.description = appendText(item.description, stripTags(value));
            case "guid" -> item.guid = appendText(item.guid, value);
            case "pubDate", "date" -> {
                if (item.pubDate == null) {
                    item.pubDate = parsePubDate(value);
                }
            }
            default -> {
                // ignore other tags
            }
        }
    }

    // CHARACTERS 이벤트가 분할될 수 있어 누적
    private String appendText(String existing, String add) {
        if (add == null || add.isBlank()) {
            return existing;
        }
        return existing == null ? add : (existing + add);
    }

    private String stripTags(String s) {
        if (s == null) {
            return null;
        }
        String cleaned = s.replaceAll("<[^>]+>", "").trim();
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200).trim();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** RFC-822(RSS 표준) 및 ISO 형식 모두 시도. */
    private LocalDateTime parsePubDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        // RFC 1123 (예: Tue, 17 Jun 2026 09:41:00 +0900)
        try {
            ZonedDateTime z = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return z.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (Exception ignored) {
            // fall through
        }
        // ISO offset (예: 2026-06-17T09:41:00+09:00)
        try {
            ZonedDateTime z = ZonedDateTime.parse(v);
            return z.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (Exception ignored) {
            // fall through
        }
        // 일부 피드의 변형 RFC-822 (single-digit day 등) — Locale.ENGLISH 명시
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(
                    "EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
            ZonedDateTime z = ZonedDateTime.parse(v, f);
            return z.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (Exception ignored) {
            // give up
        }
        log.debug("Unparseable pubDate: {}", value);
        return null;
    }

    /** guid → link → title 우선순위로 안정 id 생성(hash). */
    private String stableId(RssItem item) {
        String basis = item.guid != null ? item.guid
                : (item.link != null ? item.link
                : (item.title != null ? item.title : ""));
        return Integer.toHexString(basis.hashCode());
    }

    // -----------------------------------------------------------------
    // shared helpers
    // -----------------------------------------------------------------

    private boolean isRtOk(Map<String, Object> body) {
        return body != null && "0".equals(String.valueOf(body.get("rt_cd")));
    }

    private BigDecimal parseBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.stock.StockSearchResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/**
 * 국내 종목 검색용 인메모리 카탈로그.
 *
 * <p>KIS 는 종목명 키워드 검색 REST API 를 제공하지 않으므로, 공식 <b>종목마스터 파일</b>
 * (kospi_code.mst / kosdaq_code.mst)을 KIS CDN 에서 내려받아 검색에 필요한 최소 필드
 * (단축코드 / 종목명 / 마켓)만 메모리에 적재한다. DB 에 저장하지 않는다.
 *
 * <p>부팅을 막지 않도록 {@link ApplicationReadyEvent} 이후 백그라운드로 로드하고,
 * KIS 가 매일 갱신하므로 주기적으로 새로 받는다. 다운로드/파싱 실패 시 이전 스냅샷을 유지하며,
 * 아직 비어 있으면 호출측({@link StockService})이 DB {@code stock_master} 로 폴백한다.
 *
 * <p>파일 포맷(공식 샘플 kis_kospi_code_mst.py 기준, cp949/MS949):
 * 각 행의 마지막 고정 트레일러(KOSPI 228 / KOSDAQ 222)를 제외한 앞부분(part1)에서
 * [0:9]=단축코드, [9:21]=표준코드, [21:]=한글 종목명(공백 padding).
 */
@Slf4j
@Component
public class StockMasterCatalog {

    /** (다운로드 URL, 마켓, 고정 트레일러 길이). */
    private record Source(String url, String market, int trailerLen) {}

    private static final List<Source> SOURCES = List.of(
            new Source("https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip", "KOSPI", 228),
            new Source("https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip", "KOSDAQ", 222)
    );

    /** KIS 마스터 파일 인코딩 (cp949). */
    private static final Charset MS949 = Charset.forName("MS949");
    /** part1 에서 종목명 시작 오프셋 = 단축코드(9) + 표준코드(12). */
    private static final int NAME_OFFSET = 21;
    /** 주기적 갱신 간격(시간). KIS 는 영업일 1회 갱신. */
    private static final long REFRESH_HOURS = 12;

    /**
     * 검색 화면 기본 노출용 코스피 대표(시가총액 상위권) 종목 코드.
     * 종목명은 카탈로그에서 해석해 최신 상태로 표시한다(코드만 관리).
     * ※ 정적 큐레이션 — 추후 KIS 시가총액 상위 랭킹 API 로 대체 가능.
     */
    private static final List<String> TOP_KOSPI_CODES = List.of(
            "005930", "000660", "373220", "207940", "005380",
            "000270", "068270", "105560", "005490", "035420",
            "012450", "329180", "055550", "028260", "012330",
            "035720", "000810", "086790", "015760", "032830",
            "051910", "138040", "009540", "011200", "010130",
            "259960", "096770", "066570", "323410", "003670"
    );

    /** 불변 스냅샷 (읽기 중 교체 안전). */
    private volatile List<StockSearchResponse> catalog = List.of();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stock-master-catalog");
                t.setDaemon(true);
                return t;
            });

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // 부팅 완료 후 즉시 1회 + 이후 주기 갱신 (부팅 스레드를 막지 않음).
        scheduler.scheduleWithFixedDelay(this::refresh, 0, REFRESH_HOURS, TimeUnit.HOURS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 카탈로그가 적재되었는지. false 면 호출측이 DB 로 폴백한다. */
    public boolean isLoaded() {
        return !catalog.isEmpty();
    }

    /**
     * 코드 prefix 또는 종목명 부분일치(대소문자 무시), 최대 {@code limit} 건.
     */
    public List<StockSearchResponse> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.trim().toUpperCase(Locale.ROOT);
        List<StockSearchResponse> snapshot = catalog;
        List<StockSearchResponse> out = new ArrayList<>();
        for (StockSearchResponse s : snapshot) {
            if (s.getStockCode().toUpperCase(Locale.ROOT).startsWith(k)
                    || s.getStockName().toUpperCase(Locale.ROOT).contains(k)) {
                out.add(s);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 검색 화면 기본 노출용 코스피 상위 종목(최대 {@code limit}건).
     * 큐레이션 코드 순서대로 카탈로그에서 이름을 해석해 반환한다.
     * 카탈로그 미로드 시 빈 리스트(호출측이 폴백/공백 처리).
     */
    public List<StockSearchResponse> topDomestic(int limit) {
        List<StockSearchResponse> snapshot = catalog;
        if (snapshot.isEmpty()) {
            return List.of();
        }
        Map<String, StockSearchResponse> byCode = new HashMap<>();
        for (StockSearchResponse s : snapshot) {
            byCode.putIfAbsent(s.getStockCode(), s);
        }
        List<StockSearchResponse> out = new ArrayList<>();
        for (String code : TOP_KOSPI_CODES) {
            StockSearchResponse s = byCode.get(code);
            if (s != null) {
                out.add(s);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private void refresh() {
        try {
            List<StockSearchResponse> next = new ArrayList<>();
            for (Source src : SOURCES) {
                next.addAll(parse(src));
            }
            if (!next.isEmpty()) {
                catalog = List.copyOf(next);
                log.info("Stock master catalog loaded from KIS: {} symbols (KOSPI+KOSDAQ)", catalog.size());
            } else {
                log.warn("Stock master catalog refresh produced 0 symbols; keeping previous ({}).",
                        catalog.size());
            }
        } catch (Exception e) {
            log.warn("Stock master catalog refresh failed; keeping previous ({} symbols): {}",
                    catalog.size(), e.getMessage());
        }
    }

    private List<StockSearchResponse> parse(Source src) throws Exception {
        List<StockSearchResponse> out = new ArrayList<>();
        URLConnection conn = URI.create(src.url()).toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(20000);
        try (ZipInputStream zis = new ZipInputStream(conn.getInputStream())) {
            if (zis.getNextEntry() == null) {
                return out;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(zis, MS949));
            String row;
            while ((row = br.readLine()) != null) {
                int cut = row.length() - src.trailerLen();
                if (cut <= NAME_OFFSET) {
                    continue;
                }
                String part1 = row.substring(0, cut);
                String code = part1.substring(0, 9).trim();
                String name = part1.substring(NAME_OFFSET).trim();
                if (!code.isEmpty() && !name.isEmpty()) {
                    out.add(StockSearchResponse.builder()
                            .stockCode(code)
                            .stockName(name)
                            .market(src.market())
                            .build());
                }
            }
        }
        return out;
    }
}

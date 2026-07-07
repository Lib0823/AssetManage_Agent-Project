package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.StockMaster;
import com.inbeom.apiserver.dto.stock.OrderbookResponse;
import com.inbeom.apiserver.dto.stock.StockPriceResponse;
import com.inbeom.apiserver.dto.stock.StockSearchResponse;
import com.inbeom.apiserver.repository.StockMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 종목 검색·현재가 조회 서비스 (검색 화면 - SearchView).
 *
 * <p>검색은 stock_master 카탈로그(코드 prefix / 종목명 부분일치)에서, 현재가는 공용
 * {@link KisQuoteClient} 로 KIS 실전 시세를 조회한다. 시세 비활성/실패 시 가격은 null,
 * notice 로 사유를 노출하며 절대 예외를 전파하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockMasterRepository stockMasterRepository;
    private final StockMasterCatalog stockMasterCatalog;
    private final KisQuoteClient kisQuoteClient;

    /** 검색 상위 건수 제한 (코드 prefix / 종목명 부분일치). */
    private static final int TOP_30_LIMIT = 30;
    private static final Pageable TOP_30 = PageRequest.of(0, TOP_30_LIMIT);

    /** 통화 코드 상수. */
    private static final String CURRENCY_KRW = "KRW";
    private static final String CURRENCY_USD = "USD";

    /** 해외(US) 마켓 식별자. */
    private static final String MARKET_US = "US";

    /**
     * 검색 화면 기본 노출용 S&P500 대표(시가총액 상위권) 종목.
     * 미국 대형주는 심볼·상장거래소가 안정적이라 정적 큐레이션(코드/이름/거래소)으로 관리한다.
     * ※ 추후 KIS 해외 시가총액 랭킹으로 대체 가능.
     */
    private static final List<StockSearchResponse> TOP_US_STOCKS = List.of(
            us("AAPL", "Apple", "NASD"),
            us("MSFT", "Microsoft", "NASD"),
            us("NVDA", "NVIDIA", "NASD"),
            us("AMZN", "Amazon.com", "NASD"),
            us("GOOGL", "Alphabet", "NASD"),
            us("META", "Meta Platforms", "NASD"),
            us("AVGO", "Broadcom", "NASD"),
            us("TSLA", "Tesla", "NASD"),
            us("LLY", "Eli Lilly", "NYSE"),
            us("JPM", "JPMorgan Chase", "NYSE"),
            us("V", "Visa", "NYSE"),
            us("WMT", "Walmart", "NYSE"),
            us("MA", "Mastercard", "NYSE"),
            us("XOM", "Exxon Mobil", "NYSE"),
            us("UNH", "UnitedHealth", "NYSE"),
            us("ORCL", "Oracle", "NYSE"),
            us("COST", "Costco", "NASD"),
            us("NFLX", "Netflix", "NASD"),
            us("JNJ", "Johnson & Johnson", "NYSE"),
            us("HD", "Home Depot", "NYSE")
    );

    private static StockSearchResponse us(String code, String name, String exchange) {
        return StockSearchResponse.builder()
                .stockCode(code)
                .stockName(name)
                .market(exchange)
                .exchangeCode(exchange)
                .build();
    }

    /**
     * 종목 검색: 코드 prefix 또는 종목명 부분일치(대소문자 무시), 최대 30건.
     * 빈/공백 질의는 빈 리스트. 마켓 필터 없이 호출하면 국내(KRW) 결과를 반환한다.
     */
    @Transactional(readOnly = true)
    public List<StockSearchResponse> searchStocks(String q) {
        return searchStocks(q, null);
    }

    /**
     * 종목 검색(마켓 분기): 코드 prefix 또는 종목명 부분일치(대소문자 무시), 최대 30건.
     * 빈/공백 질의는 빈 리스트.
     *
     * <p>{@code market} 이 "US"(대소문자 무시)면 통화 USD(해외) 종목을, 그 외(null/빈값/국내)는
     * 통화 KRW(국내) 종목을 검색한다. 파라미터 없는 기존 호출은 국내 결과를 유지한다.
     */
    @Transactional(readOnly = true)
    public List<StockSearchResponse> searchStocks(String q, String market) {
        if (q == null || q.isBlank()) {
            return Collections.emptyList();
        }
        String term = q.trim();
        boolean us = MARKET_US.equalsIgnoreCase(market != null ? market.trim() : null);

        // 국내(KRW): KIS 종목마스터 인메모리 카탈로그를 우선 사용(코드/이름 전 종목).
        // 카탈로그 로드 전이거나 다운로드 실패면 DB stock_master 시드로 폴백한다.
        if (!us && stockMasterCatalog.isLoaded()) {
            return stockMasterCatalog.search(term, TOP_30_LIMIT);
        }

        // 해외(US)는 아직 DB 카탈로그(v1.9 시드) 사용. 국내 폴백도 여기로 온다.
        String currency = us ? CURRENCY_USD : CURRENCY_KRW;
        List<StockMaster> matches =
                stockMasterRepository.searchByKeywordAndCurrency(term, currency, TOP_30);
        return matches.stream()
                .map(m -> StockSearchResponse.builder()
                        .stockCode(m.getStockCode())
                        .stockName(m.getStockName())
                        .market(m.getMarket())
                        .exchangeCode(m.getExchangeCode())  // 해외 시세/이동에 필요 (국내는 null)
                        .build())
                .toList();
    }

    /**
     * 검색 화면 기본 노출용 상위 종목(국내 코스피 대표). 최대 30건.
     * 국내는 인메모리 카탈로그의 큐레이션 상위 종목, 해외(US)는 미지원(빈 리스트).
     */
    @Transactional(readOnly = true)
    public List<StockSearchResponse> topStocks(String market) {
        boolean us = MARKET_US.equalsIgnoreCase(market != null ? market.trim() : null);
        if (us) {
            return TOP_US_STOCKS;
        }
        return stockMasterCatalog.topDomestic(TOP_30_LIMIT);
    }

    /**
     * 종목 현재가 조회. 시세 비활성/실패 시 가격 null + notice.
     * KIS output 매핑: stck_prpr→currentPrice, prdy_vrss→changeAmount, prdy_ctrt→changeRate.
     */
    public StockPriceResponse getPrice(String stockCode) {
        Map<String, Object> price = kisQuoteClient.fetchCurrentPrice(stockCode);
        if (price == null) {
            return StockPriceResponse.builder()
                    .stockCode(stockCode)
                    .notice(kisQuoteClient.unavailableNotice())
                    .build();
        }
        return StockPriceResponse.builder()
                .stockCode(stockCode)
                .currentPrice(parseLong(price.get("stck_prpr")))
                .changeAmount(parseLong(price.get("prdy_vrss")))
                .changeRate(parseBigDecimal(price.get("prdy_ctrt")))
                .build();
    }

    /**
     * 종목 호가 조회. 호가(FHKST01010200 output1) + 현재가(FHKST01010100 stck_prpr)를 조합한다.
     * 호가 비활성/실패 시 asks/bids 빈 리스트, currentPrice null, notice 로 사유 노출.
     * KIS output1 매핑: askp{n}/askp_rsqn{n}→asks, bidp{n}/bidp_rsqn{n}→bids (0원 호가 제외).
     */
    public OrderbookResponse getOrderbook(String stockCode) {
        Map<String, Object> orderbook = kisQuoteClient.fetchOrderbook(stockCode);
        if (orderbook == null) {
            return OrderbookResponse.builder()
                    .stockCode(stockCode)
                    .currentPrice(null)
                    .asks(Collections.emptyList())
                    .bids(Collections.emptyList())
                    .notice(kisQuoteClient.unavailableNotice())
                    .build();
        }

        Long currentPrice = null;
        Map<String, Object> price = kisQuoteClient.fetchCurrentPrice(stockCode);
        if (price != null) {
            currentPrice = parseLong(price.get("stck_prpr"));
        }

        return OrderbookResponse.builder()
                .stockCode(stockCode)
                .currentPrice(currentPrice)
                .asks(buildLevels(orderbook, "askp", "askp_rsqn"))
                .bids(buildLevels(orderbook, "bidp", "bidp_rsqn"))
                .build();
    }

    /**
     * 1~10단계 호가/잔량을 OrderbookLevel 리스트로 변환. 가격이 null 또는 0인 단계는 제외한다.
     */
    private List<OrderbookResponse.OrderbookLevel> buildLevels(Map<String, Object> output1,
                                                               String priceKeyPrefix,
                                                               String quantityKeyPrefix) {
        List<OrderbookResponse.OrderbookLevel> levels = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Long price = parseLong(output1.get(priceKeyPrefix + i));
            if (price == null || price == 0L) {
                continue;
            }
            Long quantity = parseLong(output1.get(quantityKeyPrefix + i));
            levels.add(OrderbookResponse.OrderbookLevel.builder()
                    .price(price)
                    .quantity(quantity != null ? quantity : 0L)
                    .build());
        }
        return levels;
    }

    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private Long parseLong(Object o) {
        String s = asString(o);
        if (s == null) {
            return null;
        }
        try {
            String cleaned = s.replace(",", "");
            if (cleaned.contains(".")) {
                return (long) Double.parseDouble(cleaned);
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object o) {
        String s = asString(o);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

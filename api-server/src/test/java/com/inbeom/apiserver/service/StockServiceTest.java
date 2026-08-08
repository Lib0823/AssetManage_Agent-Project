package com.inbeom.apiserver.service;

import com.inbeom.apiserver.domain.StockMaster;
import com.inbeom.apiserver.dto.stock.OrderbookResponse;
import com.inbeom.apiserver.dto.stock.StockPriceResponse;
import com.inbeom.apiserver.dto.stock.StockSearchResponse;
import com.inbeom.apiserver.repository.StockMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockService 단위 테스트")
class StockServiceTest {

    @Mock
    private StockMasterRepository stockMasterRepository;

    @Mock
    private StockMasterCatalog stockMasterCatalog;

    @Mock
    private KisQuoteClient kisQuoteClient;

    @InjectMocks
    private StockService stockService;

    private static StockMaster stockMaster(String code, String name, String market,
                                           String exchangeCode, String currency) {
        return StockMaster.builder()
                .stockCode(code)
                .stockName(name)
                .market(market)
                .exchangeCode(exchangeCode)
                .currency(currency)
                .build();
    }

    // ---------------------------------------------------------------------
    // searchStocks
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("searchStocks - 빈/공백/null 질의는 빈 리스트 (조회 없음)")
    void searchStocks_BlankQuery_ReturnsEmpty() {
        // Given / When
        List<StockSearchResponse> nullResult = stockService.searchStocks(null);
        List<StockSearchResponse> blankResult = stockService.searchStocks("   ");
        List<StockSearchResponse> emptyResult = stockService.searchStocks("");

        // Then
        assertThat(nullResult).isEmpty();
        assertThat(blankResult).isEmpty();
        assertThat(emptyResult).isEmpty();
        verifyNoInteractions(stockMasterCatalog, stockMasterRepository);
    }

    @Test
    @DisplayName("searchStocks - 국내(카탈로그 로드됨): 인메모리 카탈로그 사용, DB 미조회")
    void searchStocks_Domestic_UsesCatalogWhenLoaded() {
        // Given
        List<StockSearchResponse> catalogHits = List.of(
                StockSearchResponse.builder().stockCode("005930").stockName("삼성전자").market("KOSPI").build());
        when(stockMasterCatalog.isLoaded()).thenReturn(true);
        when(stockMasterCatalog.search("삼성", 30)).thenReturn(catalogHits);

        // When
        List<StockSearchResponse> result = stockService.searchStocks("삼성");

        // Then
        assertThat(result).isEqualTo(catalogHits);
        verify(stockMasterCatalog, times(1)).search("삼성", 30);
        verifyNoInteractions(stockMasterRepository);
    }

    @Test
    @DisplayName("searchStocks - 국내(카탈로그 미로드): DB stock_master(KRW)로 폴백")
    void searchStocks_Domestic_FallsBackToDbWhenCatalogNotLoaded() {
        // Given
        when(stockMasterCatalog.isLoaded()).thenReturn(false);
        when(stockMasterRepository.searchByKeywordAndCurrency(eq("삼성"), eq("KRW"), any(Pageable.class)))
                .thenReturn(List.of(stockMaster("005930", "삼성전자", "KOSPI", null, "KRW")));

        // When
        List<StockSearchResponse> result = stockService.searchStocks("삼성");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
        assertThat(result.get(0).getMarket()).isEqualTo("KOSPI");
        assertThat(result.get(0).getExchangeCode()).isNull();
        verify(stockMasterCatalog, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("searchStocks - 해외(US): 카탈로그를 거치지 않고 DB(USD) 조회, exchangeCode 포함")
    void searchStocks_Us_UsesDbWithUsdCurrency() {
        // Given
        when(stockMasterRepository.searchByKeywordAndCurrency(eq("AAPL"), eq("USD"), any(Pageable.class)))
                .thenReturn(List.of(stockMaster("AAPL", "Apple", "NASD", "NASD", "USD")));

        // When
        List<StockSearchResponse> result = stockService.searchStocks("AAPL", "US");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("AAPL");
        assertThat(result.get(0).getExchangeCode()).isEqualTo("NASD");
        verifyNoInteractions(stockMasterCatalog);
    }

    @Test
    @DisplayName("searchStocks - market 은 대소문자 무시 (us == US)")
    void searchStocks_MarketIsCaseInsensitive() {
        // Given
        when(stockMasterRepository.searchByKeywordAndCurrency(eq("AAPL"), eq("USD"), any(Pageable.class)))
                .thenReturn(List.of());

        // When
        stockService.searchStocks("AAPL", " us ");

        // Then
        verify(stockMasterRepository).searchByKeywordAndCurrency(eq("AAPL"), eq("USD"), any(Pageable.class));
    }

    @Test
    @DisplayName("searchStocks - 질의 앞뒤 공백은 trim 후 전달")
    void searchStocks_TrimsQuery() {
        // Given
        when(stockMasterCatalog.isLoaded()).thenReturn(true);
        when(stockMasterCatalog.search("삼성전자", 30)).thenReturn(List.of());

        // When
        stockService.searchStocks("  삼성전자  ");

        // Then
        verify(stockMasterCatalog).search("삼성전자", 30);
    }

    @Test
    @DisplayName("searchStocks - DB 결과가 없으면 빈 리스트")
    void searchStocks_NoDbMatch_ReturnsEmpty() {
        // Given
        when(stockMasterCatalog.isLoaded()).thenReturn(false);
        when(stockMasterRepository.searchByKeywordAndCurrency(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        // When
        List<StockSearchResponse> result = stockService.searchStocks("없는종목");

        // Then
        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------
    // topStocks
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("topStocks - 국내는 카탈로그의 큐레이션 상위 종목")
    void topStocks_Domestic_UsesCatalog() {
        // Given
        List<StockSearchResponse> top = List.of(
                StockSearchResponse.builder().stockCode("005930").stockName("삼성전자").market("KOSPI").build());
        when(stockMasterCatalog.topDomestic(30)).thenReturn(top);

        // When
        List<StockSearchResponse> result = stockService.topStocks(null);

        // Then
        assertThat(result).isEqualTo(top);
        verify(stockMasterCatalog).topDomestic(30);
    }

    @Test
    @DisplayName("topStocks - 해외(US)는 정적 큐레이션 S&P500 대표 20종목")
    void topStocks_Us_ReturnsStaticCuration() {
        // Given / When
        List<StockSearchResponse> result = stockService.topStocks("US");

        // Then
        assertThat(result).hasSize(20);
        assertThat(result.get(0).getStockCode()).isEqualTo("AAPL");
        assertThat(result.get(0).getExchangeCode()).isEqualTo("NASD");
        assertThat(result).extracting(StockSearchResponse::getStockCode).contains("NVDA", "JPM");
        verifyNoInteractions(stockMasterCatalog, stockMasterRepository);
    }

    // ---------------------------------------------------------------------
    // getPrice
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getPrice - KIS 시세 매핑 성공 (stck_prpr/prdy_vrss/prdy_ctrt)")
    void getPrice_Success() {
        // Given
        Map<String, Object> output = new HashMap<>();
        output.put("stck_prpr", "71500");
        output.put("prdy_vrss", "-1200");
        output.put("prdy_ctrt", "-1.65");
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(output);

        // When
        StockPriceResponse result = stockService.getPrice("005930");

        // Then
        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getCurrentPrice()).isEqualTo(71500L);
        assertThat(result.getChangeAmount()).isEqualTo(-1200L);
        assertThat(result.getChangeRate()).isEqualByComparingTo(new BigDecimal("-1.65"));
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getPrice - 시세 조회 실패 시 가격 null + notice (예외 미전파)")
    void getPrice_QuoteUnavailable_ReturnsNotice() {
        // Given
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(null);
        when(kisQuoteClient.unavailableNotice()).thenReturn(KisQuoteClient.NOTICE_KIS_QUOTE);

        // When
        StockPriceResponse result = stockService.getPrice("005930");

        // Then
        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getChangeAmount()).isNull();
        assertThat(result.getChangeRate()).isNull();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_QUOTE);
    }

    @Test
    @DisplayName("getPrice - 콤마/소수 포함 값과 파싱 불가 값 처리")
    void getPrice_ParsesCommaAndDecimal_AndNullsOnGarbage() {
        // Given
        Map<String, Object> output = new HashMap<>();
        output.put("stck_prpr", "1,234,500");
        output.put("prdy_vrss", "150.7");        // 소수 → long 절삭
        output.put("prdy_ctrt", "not-a-number"); // 파싱 실패 → null
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(output);

        // When
        StockPriceResponse result = stockService.getPrice("005930");

        // Then
        assertThat(result.getCurrentPrice()).isEqualTo(1234500L);
        assertThat(result.getChangeAmount()).isEqualTo(150L);
        assertThat(result.getChangeRate()).isNull();
    }

    @Test
    @DisplayName("getPrice - 빈 문자열 필드는 null 로 매핑")
    void getPrice_BlankFields_MapToNull() {
        // Given
        Map<String, Object> output = new HashMap<>();
        output.put("stck_prpr", "   ");
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(output);

        // When
        StockPriceResponse result = stockService.getPrice("005930");

        // Then
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getNotice()).isNull();
    }

    // ---------------------------------------------------------------------
    // getOrderbook
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getOrderbook - 호가 + 현재가 조합 성공, 0원 호가는 제외")
    void getOrderbook_Success() {
        // Given
        Map<String, Object> output1 = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            // 1~3단계만 유효, 4~10단계는 0원 호가
            output1.put("askp" + i, i <= 3 ? String.valueOf(71500 + i * 100) : "0");
            output1.put("askp_rsqn" + i, i <= 3 ? String.valueOf(i * 10) : "0");
            output1.put("bidp" + i, i <= 3 ? String.valueOf(71400 - i * 100) : "0");
            output1.put("bidp_rsqn" + i, i <= 3 ? String.valueOf(i * 20) : "0");
        }
        Map<String, Object> price = Map.of("stck_prpr", "71450");

        when(kisQuoteClient.fetchOrderbook("005930")).thenReturn(output1);
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(price);

        // When
        OrderbookResponse result = stockService.getOrderbook("005930");

        // Then
        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getCurrentPrice()).isEqualTo(71450L);
        assertThat(result.getAsks()).hasSize(3);
        assertThat(result.getBids()).hasSize(3);
        assertThat(result.getAsks().get(0).getPrice()).isEqualTo(71600L);
        assertThat(result.getAsks().get(0).getQuantity()).isEqualTo(10L);
        assertThat(result.getBids().get(0).getPrice()).isEqualTo(71300L);
        assertThat(result.getBids().get(0).getQuantity()).isEqualTo(20L);
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getOrderbook - 잔량이 없으면 0 으로 채움")
    void getOrderbook_MissingQuantity_DefaultsToZero() {
        // Given
        Map<String, Object> output1 = new HashMap<>();
        output1.put("askp1", "71600");
        output1.put("bidp1", "71300");

        when(kisQuoteClient.fetchOrderbook("005930")).thenReturn(output1);
        when(kisQuoteClient.fetchCurrentPrice("005930")).thenReturn(null);

        // When
        OrderbookResponse result = stockService.getOrderbook("005930");

        // Then
        assertThat(result.getAsks()).hasSize(1);
        assertThat(result.getAsks().get(0).getQuantity()).isZero();
        assertThat(result.getBids().get(0).getQuantity()).isZero();
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getNotice()).isNull();
    }

    @Test
    @DisplayName("getOrderbook - 호가 조회 실패 시 빈 리스트 + notice, 현재가는 조회하지 않음")
    void getOrderbook_Unavailable_ReturnsEmptyWithNotice() {
        // Given
        when(kisQuoteClient.fetchOrderbook("005930")).thenReturn(null);
        when(kisQuoteClient.unavailableNotice()).thenReturn(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);

        // When
        OrderbookResponse result = stockService.getOrderbook("005930");

        // Then
        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getCurrentPrice()).isNull();
        assertThat(result.getAsks()).isEmpty();
        assertThat(result.getBids()).isEmpty();
        assertThat(result.getNotice()).isEqualTo(KisQuoteClient.NOTICE_KIS_UNAVAILABLE);
        verify(kisQuoteClient, never()).fetchCurrentPrice(anyString());
    }
}

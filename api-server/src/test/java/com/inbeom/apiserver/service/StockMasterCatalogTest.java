package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.stock.StockSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockMasterCatalog 단위 테스트")
class StockMasterCatalogTest {

    @InjectMocks
    private StockMasterCatalog stockMasterCatalog;

    private StockSearchResponse samsung;
    private StockSearchResponse skHynix;
    private StockSearchResponse kakao;

    @BeforeEach
    void setUp() {
        samsung = entry("005930", "삼성전자", "KOSPI");
        skHynix = entry("000660", "SK하이닉스", "KOSPI");
        kakao = entry("035720", "카카오", "KOSPI");
    }

    private static StockSearchResponse entry(String code, String name, String market) {
        return StockSearchResponse.builder()
                .stockCode(code)
                .stockName(name)
                .market(market)
                .build();
    }

    /** 인메모리 카탈로그 스냅샷 주입 (실제로는 KIS 마스터 파일 다운로드로 채워짐). */
    private void loadCatalog(StockSearchResponse... entries) {
        ReflectionTestUtils.setField(stockMasterCatalog, "catalog", List.of(entries));
    }

    @Test
    @DisplayName("isLoaded - 카탈로그가 비어있으면 false (호출측이 DB 로 폴백)")
    void isLoaded_EmptyCatalog_ReturnsFalse() {
        // Given - 초기 상태 (다운로드 전)

        // When
        boolean loaded = stockMasterCatalog.isLoaded();

        // Then
        assertThat(loaded).isFalse();
    }

    @Test
    @DisplayName("isLoaded - 카탈로그가 적재되면 true")
    void isLoaded_LoadedCatalog_ReturnsTrue() {
        // Given
        loadCatalog(samsung, skHynix);

        // When
        boolean loaded = stockMasterCatalog.isLoaded();

        // Then
        assertThat(loaded).isTrue();
    }

    @Test
    @DisplayName("search - 종목 코드 prefix 일치")
    void search_ByCodePrefix() {
        // Given
        loadCatalog(samsung, skHynix, kakao);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("0059", 30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
        assertThat(result.get(0).getMarket()).isEqualTo("KOSPI");
    }

    @Test
    @DisplayName("search - 종목명 부분일치")
    void search_ByNameContains() {
        // Given
        loadCatalog(samsung, skHynix, kakao);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("하이닉스", 30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("000660");
    }

    @Test
    @DisplayName("search - 종목명 대소문자 무시 매칭")
    void search_ByNameIgnoresCase() {
        // Given
        loadCatalog(entry("068270", "Celltrion", "KOSPI"));

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("celltrion", 30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("068270");
    }

    @Test
    @DisplayName("search - 앞뒤 공백은 trim 후 매칭")
    void search_TrimsKeyword() {
        // Given
        loadCatalog(samsung, skHynix);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("  005930  ", 30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("search - limit 건수까지만 반환")
    void search_RespectsLimit() {
        // Given
        List<StockSearchResponse> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(entry(String.format("%06d", i), "테스트종목" + i, "KOSPI"));
        }
        ReflectionTestUtils.setField(stockMasterCatalog, "catalog", List.copyOf(many));

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("테스트종목", 30);

        // Then
        assertThat(result).hasSize(30);
    }

    @Test
    @DisplayName("search - null/공백 키워드는 빈 리스트")
    void search_NullOrBlankKeyword_ReturnsEmpty() {
        // Given
        loadCatalog(samsung, skHynix);

        // When
        List<StockSearchResponse> nullResult = stockMasterCatalog.search(null, 30);
        List<StockSearchResponse> blankResult = stockMasterCatalog.search("   ", 30);

        // Then
        assertThat(nullResult).isEmpty();
        assertThat(blankResult).isEmpty();
    }

    @Test
    @DisplayName("search - 카탈로그 미로드 시 빈 리스트")
    void search_EmptyCatalog_ReturnsEmpty() {
        // Given - 카탈로그 미적재

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("삼성", 30);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("search - 매칭되는 종목이 없으면 빈 리스트")
    void search_NoMatch_ReturnsEmpty() {
        // Given
        loadCatalog(samsung, skHynix);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.search("존재하지않는종목", 30);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("topDomestic - 큐레이션 코드 순서대로 카탈로그에서 이름 해석")
    void topDomestic_ResolvesNamesInCurationOrder() {
        // Given - 큐레이션 목록의 1번(005930) / 2번(000660) 순서와 반대로 적재
        loadCatalog(kakao, skHynix, samsung);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.topDomestic(30);

        // Then - TOP_KOSPI_CODES 순서(005930 → 000660 → ... → 035720)를 따른다
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(1).getStockCode()).isEqualTo("000660");
        assertThat(result.get(2).getStockCode()).isEqualTo("035720");
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("topDomestic - 큐레이션에 없는 종목은 제외")
    void topDomestic_SkipsNonCuratedCodes() {
        // Given
        loadCatalog(samsung, entry("999999", "비큐레이션종목", "KOSDAQ"));

        // When
        List<StockSearchResponse> result = stockMasterCatalog.topDomestic(30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("topDomestic - limit 건수까지만 반환")
    void topDomestic_RespectsLimit() {
        // Given
        loadCatalog(samsung, skHynix, kakao);

        // When
        List<StockSearchResponse> result = stockMasterCatalog.topDomestic(2);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStockCode()).isEqualTo("005930");
        assertThat(result.get(1).getStockCode()).isEqualTo("000660");
    }

    @Test
    @DisplayName("topDomestic - 카탈로그 미로드 시 빈 리스트 (호출측 폴백)")
    void topDomestic_EmptyCatalog_ReturnsEmpty() {
        // Given - 카탈로그 미적재

        // When
        List<StockSearchResponse> result = stockMasterCatalog.topDomestic(30);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("topDomestic - 중복 코드는 첫 항목만 사용 (putIfAbsent)")
    void topDomestic_DuplicateCode_KeepsFirst() {
        // Given
        loadCatalog(
                entry("005930", "삼성전자", "KOSPI"),
                entry("005930", "삼성전자중복", "KOSDAQ"));

        // When
        List<StockSearchResponse> result = stockMasterCatalog.topDomestic(30);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
    }
}

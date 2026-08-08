package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.market.StockNewsResponse;
import com.inbeom.apiserver.repository.StockNewsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockNewsService 단위 테스트")
class StockNewsServiceTest {

    @Mock
    private StockNewsRepository stockNewsRepository;

    private StockNewsService stockNewsService;

    private LocalDate analysisDate;

    @BeforeEach
    void setUp() {
        // tags(JSONB) 파싱을 실제로 검증해야 하므로 ObjectMapper 는 목이 아닌 실제 인스턴스를 사용
        stockNewsService = new StockNewsService(stockNewsRepository, new ObjectMapper());
        analysisDate = LocalDate.of(2026, 7, 31);
    }

    private Map<String, Object> newsRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 42L);
        row.put("stock_code", "005930");
        row.put("stock_name", "삼성전자");
        row.put("title", "삼성전자 2분기 실적 발표");
        row.put("summary", "영업이익 시장 기대치 상회");
        row.put("url", "https://example.com/news/42");
        row.put("source", "한국경제");
        row.put("sentiment_score", new BigDecimal("0.72"));
        row.put("sentiment_label", "positive");
        row.put("tags", "[\"실적\",\"반도체\"]");
        row.put("published_at", Timestamp.valueOf(LocalDateTime.of(2026, 7, 31, 9, 30)));
        row.put("analysis_date", java.sql.Date.valueOf(analysisDate));
        return row;
    }

    // ---------------------------------------------------------------------
    // getBySymbol
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getBySymbol - stock_news row 를 DTO 로 전량 매핑")
    void getBySymbol_Success() {
        // Given
        when(stockNewsRepository.findBySymbol("005930", analysisDate))
                .thenReturn(List.of(newsRow()));

        // When
        List<StockNewsResponse> result = stockNewsService.getBySymbol("005930", analysisDate);

        // Then
        assertThat(result).hasSize(1);
        StockNewsResponse news = result.get(0);
        assertThat(news.getId()).isEqualTo(42L);
        assertThat(news.getStockCode()).isEqualTo("005930");
        assertThat(news.getStockName()).isEqualTo("삼성전자");
        assertThat(news.getTitle()).isEqualTo("삼성전자 2분기 실적 발표");
        assertThat(news.getSummary()).isEqualTo("영업이익 시장 기대치 상회");
        assertThat(news.getUrl()).isEqualTo("https://example.com/news/42");
        assertThat(news.getSource()).isEqualTo("한국경제");
        assertThat(news.getSentimentScore()).isEqualByComparingTo(new BigDecimal("0.72"));
        assertThat(news.getSentimentLabel()).isEqualTo("positive");
        assertThat(news.getTags()).containsExactly("실적", "반도체");
        assertThat(news.getPublishedAt()).isEqualTo("2026-07-31T09:30");
        assertThat(news.getAnalysisDate()).isEqualTo("2026-07-31");
        verify(stockNewsRepository, times(1)).findBySymbol("005930", analysisDate);
    }

    @Test
    @DisplayName("getBySymbol - date 가 null 이면 그대로 전달 (리포지토리가 최신일로 fallback)")
    void getBySymbol_NullDate_DelegatesToRepository() {
        // Given
        when(stockNewsRepository.findBySymbol("005930", null)).thenReturn(List.of(newsRow()));

        // When
        List<StockNewsResponse> result = stockNewsService.getBySymbol("005930", null);

        // Then
        assertThat(result).hasSize(1);
        verify(stockNewsRepository).findBySymbol("005930", null);
    }

    @Test
    @DisplayName("getBySymbol - 조회 결과가 없으면 빈 리스트")
    void getBySymbol_NoRows_ReturnsEmpty() {
        // Given
        when(stockNewsRepository.findBySymbol("999999", analysisDate)).thenReturn(List.of());

        // When
        List<StockNewsResponse> result = stockNewsService.getBySymbol("999999", analysisDate);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getBySymbol - 리포지토리 예외 시 빈 리스트로 degrade (예외 미전파)")
    void getBySymbol_RepositoryThrows_ReturnsEmpty() {
        // Given
        when(stockNewsRepository.findBySymbol(any(), any()))
                .thenThrow(new RuntimeException("DB down"));

        // When
        List<StockNewsResponse> result = stockNewsService.getBySymbol("005930", analysisDate);

        // Then
        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------
    // getRecent
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getRecent - 전체 종목 통합 최신 뉴스 매핑")
    void getRecent_Success() {
        // Given
        Map<String, Object> second = newsRow();
        second.put("id", 43L);
        second.put("stock_code", "000660");
        when(stockNewsRepository.findRecent(20)).thenReturn(List.of(newsRow(), second));

        // When
        List<StockNewsResponse> result = stockNewsService.getRecent(20);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(StockNewsResponse::getStockCode)
                .containsExactly("005930", "000660");
        verify(stockNewsRepository).findRecent(20);
    }

    @Test
    @DisplayName("getRecent - 리포지토리 예외 시 빈 리스트로 degrade")
    void getRecent_RepositoryThrows_ReturnsEmpty() {
        // Given
        when(stockNewsRepository.findRecent(anyInt())).thenThrow(new RuntimeException("DB down"));

        // When
        List<StockNewsResponse> result = stockNewsService.getRecent(10);

        // Then
        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------
    // getById
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getById - 단건 조회 성공")
    void getById_Success() {
        // Given
        when(stockNewsRepository.findById(42L)).thenReturn(newsRow());

        // When
        StockNewsResponse result = stockNewsService.getById(42L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getTags()).containsExactly("실적", "반도체");
    }

    @Test
    @DisplayName("getById - 존재하지 않으면 null")
    void getById_NotFound_ReturnsNull() {
        // Given
        when(stockNewsRepository.findById(999L)).thenReturn(null);

        // When
        StockNewsResponse result = stockNewsService.getById(999L);

        // Then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getById - 리포지토리 예외 시 null 로 degrade")
    void getById_RepositoryThrows_ReturnsNull() {
        // Given
        when(stockNewsRepository.findById(anyLong())).thenThrow(new RuntimeException("DB down"));

        // When
        StockNewsResponse result = stockNewsService.getById(42L);

        // Then
        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------------
    // 매핑/파싱 엣지 케이스
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("mapRow - tags 가 null/공백이면 빈 리스트")
    void mapRow_NullOrBlankTags_ReturnsEmptyList() {
        // Given
        Map<String, Object> nullTags = newsRow();
        nullTags.put("tags", null);
        Map<String, Object> blankTags = newsRow();
        blankTags.put("tags", "   ");
        when(stockNewsRepository.findRecent(2)).thenReturn(List.of(nullTags, blankTags));

        // When
        List<StockNewsResponse> result = stockNewsService.getRecent(2);

        // Then
        assertThat(result.get(0).getTags()).isEmpty();
        assertThat(result.get(1).getTags()).isEmpty();
    }

    @Test
    @DisplayName("mapRow - tags JSON 파싱 실패 시 빈 리스트 (예외 미전파)")
    void mapRow_InvalidTagsJson_ReturnsEmptyList() {
        // Given
        Map<String, Object> row = newsRow();
        row.put("tags", "{not-valid-json");
        when(stockNewsRepository.findById(42L)).thenReturn(row);

        // When
        StockNewsResponse result = stockNewsService.getById(42L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTags()).isEmpty();
    }

    @Test
    @DisplayName("mapRow - 숫자/날짜 타입 변환 (Integer id, Double 점수, LocalDate/LocalDateTime)")
    void mapRow_ConvertsAlternativeTypes() {
        // Given - JDBC 드라이버가 다른 타입으로 반환하는 경우
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7);                                    // Integer
        row.put("sentiment_score", -0.5d);                   // Double
        row.put("published_at", LocalDateTime.of(2026, 7, 30, 18, 0));
        row.put("analysis_date", LocalDate.of(2026, 7, 30));
        row.put("tags", "[]");
        when(stockNewsRepository.findById(7L)).thenReturn(row);

        // When
        StockNewsResponse result = stockNewsService.getById(7L);

        // Then
        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getSentimentScore()).isEqualByComparingTo(new BigDecimal("-0.5"));
        assertThat(result.getPublishedAt()).isEqualTo("2026-07-30T18:00");
        assertThat(result.getAnalysisDate()).isEqualTo("2026-07-30");
        assertThat(result.getTags()).isEmpty();
    }

    @Test
    @DisplayName("mapRow - 모든 값이 null 인 행도 null 필드로 안전하게 매핑")
    void mapRow_AllNullValues_MapsToNullFields() {
        // Given
        Map<String, Object> row = new HashMap<>();
        row.put("id", null);
        row.put("stock_code", null);
        row.put("sentiment_score", null);
        row.put("published_at", null);
        row.put("analysis_date", null);
        row.put("tags", null);
        when(stockNewsRepository.findById(1L)).thenReturn(row);

        // When
        StockNewsResponse result = stockNewsService.getById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNull();
        assertThat(result.getStockCode()).isNull();
        assertThat(result.getSentimentScore()).isNull();
        assertThat(result.getPublishedAt()).isNull();
        assertThat(result.getAnalysisDate()).isNull();
        assertThat(result.getTags()).isEmpty();
    }
}

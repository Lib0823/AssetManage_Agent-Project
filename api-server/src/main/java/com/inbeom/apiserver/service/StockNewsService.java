package com.inbeom.apiserver.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.inbeom.apiserver.dto.market.StockNewsResponse;
import com.inbeom.apiserver.repository.StockNewsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 종목 뉴스 읽기 전용 서비스.
 *
 * <p>stock_news raw row → {@link StockNewsResponse} 매핑. 날짜/타임스탬프는 ISO 문자열로,
 * tags(JSONB)는 Jackson 으로 {@code List<String>} 파싱(실패 시 빈 리스트)한다.
 * 데이터 부재 시 빈 리스트/Null 로 안전하게 응답한다.
 */
@Slf4j
@Service
public class StockNewsService {

    private final StockNewsRepository stockNewsRepository;
    private final ObjectMapper objectMapper;

    public StockNewsService(StockNewsRepository stockNewsRepository, ObjectMapper objectMapper) {
        this.stockNewsRepository = stockNewsRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 단일 종목 뉴스 조회. date 가 null 이면 해당 종목 최신 analysis_date 로 fallback.
     */
    @Transactional(readOnly = true)
    public List<StockNewsResponse> getBySymbol(String symbol, LocalDate date) {
        try {
            List<Map<String, Object>> rows = stockNewsRepository.findBySymbol(symbol, date);
            return mapRows(rows);
        } catch (Exception e) {
            log.error("Failed to get stock news for symbol: {}, date: {}", symbol, date, e);
            return new ArrayList<>();
        }
    }

    /**
     * 전체 종목 통합 최신 뉴스 조회.
     */
    @Transactional(readOnly = true)
    public List<StockNewsResponse> getRecent(int limit) {
        try {
            List<Map<String, Object>> rows = stockNewsRepository.findRecent(limit);
            return mapRows(rows);
        } catch (Exception e) {
            log.error("Failed to get recent stock news (limit: {})", limit, e);
            return new ArrayList<>();
        }
    }

    /**
     * 단일 뉴스 조회 (id). 없거나 실패 시 null.
     */
    @Transactional(readOnly = true)
    public StockNewsResponse getById(long id) {
        try {
            Map<String, Object> row = stockNewsRepository.findById(id);
            return row != null ? mapRow(row) : null;
        } catch (Exception e) {
            log.error("Failed to get stock news by id: {}", id, e);
            return null;
        }
    }

    private List<StockNewsResponse> mapRows(List<Map<String, Object>> rows) {
        List<StockNewsResponse> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(mapRow(row));
        }
        return result;
    }

    private StockNewsResponse mapRow(Map<String, Object> row) {
        return StockNewsResponse.builder()
                .id(toLong(row.get("id")))
                .stockCode((String) row.get("stock_code"))
                .stockName((String) row.get("stock_name"))
                .title((String) row.get("title"))
                .summary((String) row.get("summary"))
                .url((String) row.get("url"))
                .source((String) row.get("source"))
                .sentimentScore(toBigDecimal(row.get("sentiment_score")))
                .sentimentLabel((String) row.get("sentiment_label"))
                .tags(parseTags(row.get("tags")))
                .publishedAt(toIsoDateTime(row.get("published_at")))
                .analysisDate(toIsoDate(row.get("analysis_date")))
                .build();
    }

    /**
     * JSONB tags(문자열 배열) → {@code List<String>}. null/파싱 실패 시 빈 리스트.
     */
    private List<String> parseTags(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        // JSONB 는 드라이버에 따라 PGobject/String 으로 반환됨 → toString() 으로 JSON 문자열 확보
        String json = value.toString();
        if (json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse stock_news tags JSON, returning empty list: {}", json, e);
            return new ArrayList<>();
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private String toIsoDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        return value.toString();
    }

    private String toIsoDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toString();
        }
        return value.toString();
    }
}

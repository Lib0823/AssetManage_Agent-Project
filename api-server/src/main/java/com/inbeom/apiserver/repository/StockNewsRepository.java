package com.inbeom.apiserver.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * stock_news 읽기 전용 조회 (JdbcTemplate).
 *
 * <p>tags 컬럼(JSONB)은 PGobject/String 형태로 반환되므로, 파싱은 서비스 레이어에서
 * Jackson 으로 수행한다(여기서는 raw row 만 반환).
 */
@Repository
public class StockNewsRepository {

    private final JdbcTemplate jdbcTemplate;

    public StockNewsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 단일 종목의 뉴스 조회.
     * date 가 null 이면 해당 종목의 가장 최근 analysis_date 를 먼저 해석한 뒤 그 날짜의 행을 반환한다.
     * 분석 이력이 전혀 없으면 빈 리스트.
     */
    public List<Map<String, Object>> findBySymbol(String stockCode, LocalDate date) {
        LocalDate targetDate = date;
        if (targetDate == null) {
            String latestSql = """
                SELECT MAX(analysis_date)
                FROM stock_news
                WHERE stock_code = ?
                """;
            List<LocalDate> latest = jdbcTemplate.queryForList(latestSql, LocalDate.class, stockCode);
            targetDate = latest.isEmpty() ? null : latest.get(0);
            if (targetDate == null) {
                return List.of();
            }
        }

        String sql = """
            SELECT
                id,
                stock_code,
                stock_name,
                analysis_date,
                title,
                summary,
                url,
                source,
                sentiment_score,
                sentiment_label,
                tags,
                published_at
            FROM stock_news
            WHERE stock_code = ?
                AND analysis_date = ?
            ORDER BY published_at DESC NULLS LAST, id DESC
            """;

        return jdbcTemplate.queryForList(sql, stockCode, targetDate);
    }

    /**
     * 전체 종목 통합 최신 뉴스 조회 (종목 지정 없는 "뉴스 더보기"용).
     */
    public List<Map<String, Object>> findRecent(int limit) {
        String sql = """
            SELECT
                id,
                stock_code,
                stock_name,
                analysis_date,
                title,
                summary,
                url,
                source,
                sentiment_score,
                sentiment_label,
                tags,
                published_at
            FROM stock_news
            ORDER BY analysis_date DESC, published_at DESC NULLS LAST, id DESC
            LIMIT ?
            """;

        return jdbcTemplate.queryForList(sql, limit);
    }

    /**
     * 단일 뉴스 조회 (id). 없으면 null.
     */
    public Map<String, Object> findById(long id) {
        String sql = """
            SELECT
                id,
                stock_code,
                stock_name,
                analysis_date,
                title,
                summary,
                url,
                source,
                sentiment_score,
                sentiment_label,
                tags,
                published_at
            FROM stock_news
            WHERE id = ?
            """;

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, id);
        return results.isEmpty() ? null : results.get(0);
    }
}

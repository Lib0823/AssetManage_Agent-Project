package com.inbeom.apiserver.migration;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * stock_filter_score / prophet_forecast / news_analysis 의 TimescaleDB hypertable 전환
 * (Liquibase v1.22~v1.24, 시계열 마이그레이션 2차 — 조인 허브)이 데이터 무결성과
 * {@code MarketAnalysisRepository}가 실제로 쓰는 3중 LEFT JOIN 쿼리를 그대로 보존하는지
 * 검증하는 TDD 통합 테스트. 패턴은 {@link AssetDailySnapshotHypertableMigrationTest}(Phase 1)와
 * 동일하다. Docker 필요 — {@code ./gradlew timescaledbTest}로만 실행.
 */
@Testcontainers
@Tag("timescaledb")
@DisplayName("stock_filter_score/prophet_forecast/news_analysis TimescaleDB 마이그레이션 무결성 테스트")
class AnalysisTablesHypertableMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("financemanage")
            .withUsername("admin")
            .withPassword("admin1234")
            .withCommand("postgres", "-c", "shared_preload_libraries=timescaledb");

    @BeforeAll
    static void applyLiquibaseChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("mvp");
        liquibase.afterPropertiesSet();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @DisplayName("세 테이블 모두 올바른 파티션 컬럼으로 hypertable 등록된다")
    void allThreeTablesAreRegisteredAsHypertables() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT hypertable_name, primary_dimension FROM timescaledb_information.hypertables " +
                            "WHERE hypertable_name IN ('stock_filter_score', 'prophet_forecast', 'news_analysis') " +
                            "ORDER BY hypertable_name");
            java.util.Map<String, String> dims = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                dims.put(rs.getString("hypertable_name"), rs.getString("primary_dimension"));
            }
            assertThat(dims).containsExactly(
                    java.util.Map.entry("news_analysis", "analysis_date"),
                    java.util.Map.entry("prophet_forecast", "forecast_date"),
                    java.util.Map.entry("stock_filter_score", "score_date"));
        }
    }

    @Test
    @DisplayName("stock_filter_score 는 (stock_code, score_date) 중복 삽입이 UNIQUE 제약으로 막힌다")
    void stockFilterScoreUniqueConstraintStillEnforced() throws Exception {
        try (Connection conn = connect()) {
            insertFilterScore(conn, "999001", "999001", "2026-05-01", true);
            assertThatThrownBy(() -> insertFilterScore(conn, "999001", "999001", "2026-05-01", true))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("ai-agent 의 DELETE-then-INSERT 재적재 패턴이 세 테이블 모두에서 정상 동작한다")
    void deleteThenReinsertPatternWorksForAllThree() throws Exception {
        try (Connection conn = connect()) {
            String date = "2026-05-02";
            insertFilterScore(conn, "999002", "999002", date, true);
            insertFilterScore(conn, "999003", "999003", date, false);

            try (PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM stock_filter_score WHERE score_date = ?")) {
                delete.setDate(1, Date.valueOf(date));
                assertThat(delete.executeUpdate()).isEqualTo(2);
            }

            insertFilterScore(conn, "999002", "999002", date, true);

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT count(*) FROM stock_filter_score WHERE score_date = ?")) {
                select.setDate(1, Date.valueOf(date));
                ResultSet rs = select.executeQuery();
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("MarketAnalysisRepository 가 실제로 쓰는 3중 LEFT JOIN(sfs+news+prophet)이 정확한 결과를 반환한다")
    void marketAnalysisThreeWayJoinStillWorksCorrectly() throws Exception {
        try (Connection conn = connect()) {
            String date = "2026-05-03";
            insertFilterScore(conn, "999004", "테스트종목", date, true);
            insertNewsAnalysis(conn, "999004", date, new BigDecimal("0.42"), 7);
            insertProphetForecast(conn, "999004", date, new BigDecimal("70000"), new BigDecimal("73000"));

            // MarketAnalysisRepository.getStockFeatures() 와 동일한 조인 형태
            String sql = """
                    SELECT sfs.stock_name, na.sentiment_score, na.news_count, pf.yhat_d1, pf.yhat_d5
                    FROM stock_filter_score sfs
                    LEFT JOIN news_analysis na
                        ON sfs.stock_code = na.stock_code AND sfs.score_date = na.analysis_date
                    LEFT JOIN prophet_forecast pf
                        ON sfs.stock_code = pf.stock_code AND sfs.score_date = pf.forecast_date
                    WHERE sfs.stock_code = ? AND sfs.score_date = ?
                    """;
            try (PreparedStatement select = conn.prepareStatement(sql)) {
                select.setString(1, "999004");
                select.setDate(2, Date.valueOf(date));
                ResultSet rs = select.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("stock_name")).isEqualTo("테스트종목");
                assertThat(rs.getBigDecimal("sentiment_score")).isEqualByComparingTo("0.42");
                assertThat(rs.getInt("news_count")).isEqualTo(7);
                assertThat(rs.getBigDecimal("yhat_d1")).isEqualByComparingTo("70000");
                assertThat(rs.getBigDecimal("yhat_d5")).isEqualByComparingTo("73000");
                assertThat(rs.next()).as("종목+날짜당 정확히 한 행만 나와야 함(조인 카디널리티 폭발 없음)").isFalse();
            }
        }
    }

    @Test
    @DisplayName("stock_filter_score 의 날짜 범위 조회가 chunk exclusion으로 관련 chunk만 스캔한다")
    void dateRangeQueryExcludesUnrelatedChunks() throws Exception {
        try (Connection conn = connect()) {
            for (int m = 1; m <= 6; m++) {
                insertFilterScore(conn, String.format("9990%02d", 10 + m), "종목" + m,
                        String.format("2026-%02d-15", m), true);
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT stock_code FROM stock_filter_score " +
                            "WHERE score_date BETWEEN ? AND ? ORDER BY score_date")) {
                select.setDate(1, Date.valueOf("2026-03-01"));
                select.setDate(2, Date.valueOf("2026-04-30"));
                ResultSet rs = select.executeQuery();
                List<String> codes = new ArrayList<>();
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
                assertThat(codes).containsExactly("999013", "999014");
            }

            long totalChunks = countChunks(conn, "stock_filter_score");

            try (PreparedStatement explain = conn.prepareStatement(
                    "EXPLAIN SELECT * FROM stock_filter_score WHERE score_date BETWEEN ? AND ?")) {
                explain.setDate(1, Date.valueOf("2026-03-01"));
                explain.setDate(2, Date.valueOf("2026-04-30"));
                ResultSet rs = explain.executeQuery();
                StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                long scannedChunks = plan.toString().lines().filter(l -> l.contains("_hyper_")).count();
                assertThat(scannedChunks)
                        .as("전체 %d개 chunk 중 2개월 조회 범위에 해당하는 chunk만 스캔해야 함(=chunk exclusion 동작)",
                                totalChunks)
                        .isLessThan(totalChunks);
            }
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private long countChunks(Connection conn, String hypertableName) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT count(*) FROM timescaledb_information.chunks WHERE hypertable_name = ?")) {
            select.setString(1, hypertableName);
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertFilterScore(Connection conn, String stockCode, String stockName, String date, boolean selected)
            throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO stock_filter_score
                    (stock_code, stock_name, score_date, foreign_net_buy, institutional_net_buy,
                     vol_avg_multiple, price_volatility, scaler_score, is_selected)
                VALUES (?, ?, ?, 0, 0, 1.0, 1.0, 5.0, ?)
                """)) {
            insert.setString(1, stockCode);
            insert.setString(2, stockName);
            insert.setDate(3, Date.valueOf(date));
            insert.setBoolean(4, selected);
            insert.executeUpdate();
        }
    }

    private void insertNewsAnalysis(Connection conn, String stockCode, String date, BigDecimal sentiment, int newsCount)
            throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO news_analysis (stock_code, analysis_date, sentiment_score, news_count) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, stockCode);
            insert.setDate(2, Date.valueOf(date));
            insert.setBigDecimal(3, sentiment);
            insert.setInt(4, newsCount);
            insert.executeUpdate();
        }
    }

    private void insertProphetForecast(Connection conn, String stockCode, String date, BigDecimal yhatD1, BigDecimal yhatD5)
            throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO prophet_forecast (stock_code, stock_name, forecast_date, yhat_d1, yhat_d5)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, stockCode);
            insert.setString(2, stockCode);
            insert.setDate(3, Date.valueOf(date));
            insert.setBigDecimal(4, yhatD1);
            insert.setBigDecimal(5, yhatD5);
            insert.executeUpdate();
        }
    }
}

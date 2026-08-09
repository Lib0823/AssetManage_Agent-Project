package com.inbeom.apiserver.migration;

import com.inbeom.apiserver.dto.market.MarketHeatmapResponse;
import com.inbeom.apiserver.dto.market.StockAnalysisResponse;
import com.inbeom.apiserver.dto.market.StockDetailAnalysisResponse;
import com.inbeom.apiserver.repository.MarketAnalysisRepository;
import com.inbeom.apiserver.service.MarketAnalysisService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MarketAnalysisRepository/MarketAnalysisService 를 "실제 클래스 그대로" TimescaleDB
 * hypertable(v1.20~v1.24 적용)에 붙여 검증하는 테스트.
 *
 * <p>{@link AnalysisTablesHypertableMigrationTest}는 필자가 손으로 옮겨 적은 재현 SQL로
 * 조인 형태를 검증했지만, 실제 프로덕션 SQL(JdbcTemplate native query)과 미묘하게 달라질
 * 위험이 있다. 이 테스트는 그 갭을 메운다 — new MarketAnalysisRepository(jdbcTemplate)를
 * 그대로 생성해 진짜 getHeatmapData/getStockAnalysis/getStockDetailAnalysis 를 호출한다.
 */
@Testcontainers
@Tag("timescaledb")
@DisplayName("MarketAnalysisRepository/Service 실클래스 - hypertable 배선 검증")
class MarketAnalysisRealRepositoryHypertableTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("financemanage")
            .withUsername("admin")
            .withPassword("admin1234")
            .withCommand("postgres", "-c", "shared_preload_libraries=timescaledb");

    static MarketAnalysisService marketAnalysisService;

    @BeforeAll
    static void applyLiquibaseAndWireService() throws Exception {
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

        DataSource ds = dataSource;
        MarketAnalysisRepository repository = new MarketAnalysisRepository(new JdbcTemplate(ds));
        marketAnalysisService = new MarketAnalysisService(repository);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeEach
    void cleanTestRows() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM stock_filter_score WHERE stock_code LIKE '8%'");
            stmt.execute("DELETE FROM news_analysis WHERE stock_code LIKE '8%'");
            stmt.execute("DELETE FROM prophet_forecast WHERE stock_code LIKE '8%'");
            stmt.execute("DELETE FROM stock_financial WHERE stock_code LIKE '8%'");
        }
    }

    @Test
    @DisplayName("getHeatmapData() 실호출 - hypertable 4개 LEFT JOIN 결과가 정확하다")
    void getHeatmapDataReturnsCorrectJoinedValues() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 1);
        try (Connection conn = connect()) {
            insertFilterScore(conn, "800001", "삼성테스트", date, 1_000_000L, 2_000_000L, true);
            insertNewsAnalysis(conn, "800001", date, new BigDecimal("0.5"), 10);
            insertProphetForecast(conn, "800001", date, new BigDecimal("100"), new BigDecimal("110"));
            insertStockFinancial(conn, "800001", date.minusDays(30), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15"));
        }

        MarketHeatmapResponse response = marketAnalysisService.getHeatmapData(date);

        assertThat(response.getStocks()).hasSize(1);
        MarketHeatmapResponse.StockFeatures stock = response.getStocks().get(0);
        assertThat(stock.getStockCode()).isEqualTo("800001");
        assertThat(stock.getForeignNetBuy()).isEqualTo(1_000_000L);
        assertThat(stock.getInstitutionalNetBuy()).isEqualTo(2_000_000L);
        assertThat(stock.getSentimentScore()).isEqualByComparingTo("0.5");
        assertThat(stock.getPer()).isEqualByComparingTo("10");
        assertThat(stock.getRoe()).isEqualByComparingTo("20");
        // (110-100)/100*100 = 10.00%
        assertThat(stock.getExpectedReturn5d()).isEqualByComparingTo("10.00");
        assertThat(response.getSummary().getTopStock().getStockCode()).isEqualTo("800001");
    }

    @Test
    @DisplayName("getStockAnalysis() 실호출 - 단일 종목 큐레이션 피처가 정확히 조립된다")
    void getStockAnalysisReturnsCorrectFeatures() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 2);
        try (Connection conn = connect()) {
            insertFilterScore(conn, "800002", "SK테스트", date, 500_000L, -300_000L, true);
            insertNewsAnalysis(conn, "800002", date, new BigDecimal("-0.4"), 3);
            insertProphetForecast(conn, "800002", date, new BigDecimal("200"), new BigDecimal("190"));
        }

        StockAnalysisResponse response = marketAnalysisService.getStockAnalysis("800002", date);

        assertThat(response.getHasAnalysis()).isTrue();
        assertThat(response.getStockName()).isEqualTo("SK테스트");
        assertThat(response.getForeignNetBuy()).isEqualTo(500_000L);
        assertThat(response.getSentimentScore()).isEqualByComparingTo("-0.4");
        // (190-200)/200*100 = -5.00%
        assertThat(response.getExpectedReturn5d()).isEqualByComparingTo("-5.00");
    }

    @Test
    @DisplayName("getStockDetailAnalysis() 실호출 - quant/sentiment/timeseries 3개 섹션이 정확히 채워진다")
    void getStockDetailAnalysisReturnsCorrectSections() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 3);
        try (Connection conn = connect()) {
            insertFilterScore(conn, "800003", "카카오테스트", date, 100_000L, 200_000L, true);
            insertNewsAnalysis(conn, "800003", date, new BigDecimal("0.1"), 5);
            insertProphetForecastFull(conn, "800003", date);
            insertStockFinancial(conn, "800003", date.minusDays(10), new BigDecimal("8"), new BigDecimal("22"), new BigDecimal("18"));
        }

        StockDetailAnalysisResponse response = marketAnalysisService.getStockDetailAnalysis("800003", date);

        assertThat(response.getHasAnalysis()).isTrue();
        assertThat(response.getQuant().getForeignNetBuy()).isEqualTo(100_000L);
        assertThat(response.getQuant().getPer()).isEqualByComparingTo("8");
        assertThat(response.getSentiment().getStockSentimentScore()).isEqualByComparingTo("0.1");
        assertThat(response.getTimeseries().getForecasts()).hasSize(5);
        assertThat(response.getTimeseries().getForecasts().get(0).getDay()).isEqualTo("D+1");
    }

    @Test
    @DisplayName("분석 이력이 없는 종목은 실제 DB에서도 hasAnalysis=false 로 안전하게 응답한다")
    void unknownStockGetsSafeEmptyResponse() {
        StockAnalysisResponse response = marketAnalysisService.getStockAnalysis("800999", LocalDate.of(2026, 6, 1));

        assertThat(response.getHasAnalysis()).isFalse();
        assertThat(response.getMetrics()).isEmpty();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void insertFilterScore(Connection conn, String stockCode, String stockName, LocalDate date,
                                    long foreignNetBuy, long institutionalNetBuy, boolean selected) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO stock_filter_score
                    (stock_code, stock_name, score_date, foreign_net_buy, institutional_net_buy,
                     vol_avg_multiple, price_volatility, scaler_score, is_selected)
                VALUES (?, ?, ?, ?, ?, 1.0, 1.0, 5.0, ?)
                """)) {
            insert.setString(1, stockCode);
            insert.setString(2, stockName);
            insert.setDate(3, Date.valueOf(date));
            insert.setLong(4, foreignNetBuy);
            insert.setLong(5, institutionalNetBuy);
            insert.setBoolean(6, selected);
            insert.executeUpdate();
        }
    }

    private void insertNewsAnalysis(Connection conn, String stockCode, LocalDate date, BigDecimal sentiment, int newsCount)
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

    private void insertProphetForecast(Connection conn, String stockCode, LocalDate date, BigDecimal yhatD1, BigDecimal yhatD5)
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

    private void insertProphetForecastFull(Connection conn, String stockCode, LocalDate date) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO prophet_forecast
                    (stock_code, stock_name, forecast_date, yhat_d1, yhat_d2, yhat_d3, yhat_d4, yhat_d5)
                VALUES (?, ?, ?, 100, 101, 102, 103, 104)
                """)) {
            insert.setString(1, stockCode);
            insert.setString(2, stockCode);
            insert.setDate(3, Date.valueOf(date));
            insert.executeUpdate();
        }
    }

    private void insertStockFinancial(Connection conn, String stockCode, LocalDate baseDate,
                                       BigDecimal per, BigDecimal roe, BigDecimal margin) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO stock_financial (stock_code, stock_name, base_date, per, roe, operating_margin)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, stockCode);
            insert.setString(2, stockCode);
            insert.setDate(3, Date.valueOf(baseDate));
            insert.setBigDecimal(4, per);
            insert.setBigDecimal(5, roe);
            insert.setBigDecimal(6, margin);
            insert.executeUpdate();
        }
    }
}

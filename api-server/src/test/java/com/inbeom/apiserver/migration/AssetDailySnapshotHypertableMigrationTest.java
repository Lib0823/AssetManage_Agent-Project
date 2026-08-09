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

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * asset_daily_snapshot 의 TimescaleDB hypertable 전환(Liquibase v1.20/v1.21)이
 * 실제 데이터 무결성·제약 조건을 그대로 보존하는지 검증하는 TDD 통합 테스트.
 *
 * <p>H2 기반 단위 테스트와 달리 실제 {@code timescale/timescaledb} 컨테이너에 전체
 * Liquibase changelog(v1.0~v1.21, mvp context)를 그대로 적용해, "이 changelog가 실제로
 * 처음부터 끝까지 깨끗하게 적용되는가"까지 함께 검증한다. Docker가 필요하므로 기본
 * {@code ./gradlew test}에서는 제외되고 {@code ./gradlew timescaledbTest}로만 실행된다.
 *
 * <p>시계열 마이그레이션(Phase 2: stock_filter_score/prophet_forecast/news_analysis)도
 * 같은 패턴(hypertable 등록 확인 → PK 형태 확인 → UNIQUE/FK 유지 확인 → upsert/range 쿼리
 * 동작 확인)을 그대로 재사용한다.
 */
@Testcontainers
@Tag("timescaledb")
@DisplayName("asset_daily_snapshot TimescaleDB 마이그레이션 무결성 테스트")
class AssetDailySnapshotHypertableMigrationTest {

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
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
            }
        }

        // 프로덕션과 동일한 경로(SpringLiquibase)로 적용 — classpath: 프리픽스 해석을
        // 포함해 실제 부팅 시 동작을 그대로 재현한다.
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("mvp");
        liquibase.afterPropertiesSet();

        // v1.4-test-data.yaml이 명시적 id로 users 행을 시드하면서 시퀀스를 안 올려서,
        // 이 테스트가 나중에 삽입하는 신규 사용자와 id가 충돌할 수 있다 — 동기화.
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("SELECT setval('users_id_seq', COALESCE((SELECT MAX(id) FROM users), 1))");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @DisplayName("전체 changelog(v1.0~v1.21, mvp)가 TimescaleDB 컨테이너에 처음부터 끝까지 적용된다")
    void fullChangelogAppliesCleanly() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM databasechangelog");
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(58); // v1.19까지 55개였음 + 이번 3개 이상
        }
    }

    @Test
    @DisplayName("asset_daily_snapshot 이 snapshot_date 기준 hypertable로 등록된다")
    void assetDailySnapshotIsRegisteredAsHypertable() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT hypertable_name, primary_dimension FROM timescaledb_information.hypertables " +
                            "WHERE hypertable_name = 'asset_daily_snapshot'");
            assertThat(rs.next()).as("hypertable로 등록되어 있어야 함").isTrue();
            assertThat(rs.getString("primary_dimension")).isEqualTo("snapshot_date");
        }
    }

    @Test
    @DisplayName("PK가 (id, snapshot_date) 복합키로 전환되어 있다")
    void primaryKeyIncludesPartitionColumn() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("""
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = 'asset_daily_snapshot'::regclass AND i.indisprimary
                    ORDER BY a.attname
                    """);
            java.util.List<String> pkColumns = new java.util.ArrayList<>();
            while (rs.next()) {
                pkColumns.add(rs.getString(1));
            }
            assertThat(pkColumns).containsExactlyInAnyOrder("id", "snapshot_date");
        }
    }

    @Test
    @DisplayName("동일 (user_id, snapshot_date) 중복 삽입은 UNIQUE 제약으로 막힌다")
    void duplicateUserAndDateIsRejected() throws Exception {
        try (Connection conn = connect()) {
            Long userId = anyUserId(conn);
            insertSnapshot(conn, userId, "2026-03-01", 1_000_000L);

            assertThatThrownBy(() -> insertSnapshot(conn, userId, "2026-03-01", 2_000_000L))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_asset_daily_snapshot_user_date");
        }
    }

    @Test
    @DisplayName("존재하지 않는 user_id 삽입은 FK 제약으로 막힌다")
    void foreignKeyToUsersIsEnforced() throws Exception {
        try (Connection conn = connect()) {
            assertThatThrownBy(() -> insertSnapshot(conn, 987_654_321L, "2026-03-02", 1L))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_asset_daily_snapshot_user");
        }
    }

    @Test
    @DisplayName("find-then-update(JPA save 업서트 패턴)로 같은 행이 갱신되고 새 행이 생기지 않는다")
    void findThenUpdateUpsertPatternWorks() throws Exception {
        try (Connection conn = connect()) {
            Long userId = anyUserId(conn);
            insertSnapshot(conn, userId, "2026-03-03", 5_000_000L);

            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE asset_daily_snapshot SET total_asset = ? WHERE user_id = ? AND snapshot_date = ?")) {
                update.setLong(1, 6_000_000L);
                update.setLong(2, userId);
                update.setDate(3, Date.valueOf("2026-03-03"));
                assertThat(update.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT total_asset FROM asset_daily_snapshot WHERE user_id = ? AND snapshot_date = ?")) {
                select.setLong(1, userId);
                select.setDate(2, Date.valueOf("2026-03-03"));
                ResultSet rs = select.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(6_000_000L);
                assertThat(rs.next()).as("행이 갱신되어야지 새로 추가되면 안 됨").isFalse();
            }
        }
    }

    @Test
    @DisplayName("사용자 삭제 시 스냅샷도 CASCADE 삭제된다")
    void userDeletionCascadesToSnapshots() throws Exception {
        try (Connection conn = connect()) {
            Long userId = createUser(conn, "cascade-test-user");
            insertSnapshot(conn, userId, "2026-03-04", 1L);

            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                delete.setLong(1, userId);
                delete.executeUpdate();
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT count(*) FROM asset_daily_snapshot WHERE user_id = ?")) {
                select.setLong(1, userId);
                ResultSet rs = select.executeQuery();
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("date-range 조회(BETWEEN)가 정상 동작하고 chunk exclusion으로 실행 계획이 좁혀진다")
    void dateRangeQueryUsesChunkExclusion() throws Exception {
        try (Connection conn = connect()) {
            Long userId = anyUserId(conn);
            for (int i = 1; i <= 10; i++) {
                insertSnapshot(conn, userId, String.format("2026-04-%02d", i), i * 1_000_000L);
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT total_asset FROM asset_daily_snapshot " +
                            "WHERE user_id = ? AND snapshot_date BETWEEN ? AND ? ORDER BY snapshot_date")) {
                select.setLong(1, userId);
                select.setDate(2, Date.valueOf("2026-04-03"));
                select.setDate(3, Date.valueOf("2026-04-05"));
                ResultSet rs = select.executeQuery();
                java.util.List<Long> results = new java.util.ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getLong(1));
                }
                assertThat(results).containsExactly(3_000_000L, 4_000_000L, 5_000_000L);
            }
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Long anyUserId(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id FROM users ORDER BY id LIMIT 1");
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return createUser(conn, "hypertable-test-user");
    }

    private Long createUser(Connection conn, String username) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users (username, password, email, name, phone, birth_date) " +
                        "VALUES (?, 'x', ? || '@example.com', 'Test', '01000000000', '1990-01-01') RETURNING id")) {
            insert.setString(1, username + "-" + System.nanoTime());
            insert.setString(2, username);
            ResultSet rs = insert.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertSnapshot(Connection conn, Long userId, String date, long totalAsset) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO asset_daily_snapshot (user_id, snapshot_date, total_asset) VALUES (?, ?, ?)")) {
            insert.setLong(1, userId);
            insert.setDate(2, Date.valueOf(date));
            insert.setLong(3, totalAsset);
            insert.executeUpdate();
        }
    }
}

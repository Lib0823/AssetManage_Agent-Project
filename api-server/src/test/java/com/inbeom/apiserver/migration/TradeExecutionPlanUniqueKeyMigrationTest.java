package com.inbeom.apiserver.migration;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * trade_execution_plan 의 UNIQUE 키 유저 스코프 교정(Liquibase v1.26) 검증.
 *
 * <p>선행 버그: 라이브 DB 에만 존재하던(= changelog 에는 없던) 스키마 드리프트 제약
 * {@code unique_execution_plan_key UNIQUE (execution_date, stock_code, trade_type)} 에
 * user_id 가 빠져 있어, 서로 다른 두 유저가 같은 날 같은 종목을 같은 방향으로 거래하면
 * 두 번째 유저의 INSERT 가 UNIQUE 위반으로 롤백됐다. Kafka Stage 6 가 발행 전에 이 테이블에
 * 먼저 INSERT 하도록 바뀌면서 이 실패가 곧 주문 유실로 이어진다.
 *
 * <p>검증 축:
 * <ol>
 *   <li>제약이 없는 신규 DB 에서 v1.26 이 (precondition MARK_RAN 으로) 깨끗하게 적용되는가</li>
 *   <li>서로 다른 유저의 동일 (execution_date, stock_code, trade_type) INSERT 가 더 이상 충돌하지 않는가</li>
 *   <li>동일 유저의 동일 조합 중복은 여전히 막히는가(제약이 무력화된 게 아닌가)</li>
 *   <li>제약이 "있는" 드리프트 환경(라이브 DB 재현)에서도 실제로 DROP 후 교체되는가</li>
 * </ol>
 *
 * <p>Docker 가 필요하므로 기본 {@code ./gradlew test} 에서는 제외되고
 * {@code ./gradlew timescaledbTest} 로만 실행된다(전체 changelog 를 실제 PostgreSQL 에
 * 적용하는 마이그레이션 통합 스위트와 동일한 태그를 공유한다. v1.20 이 timescaledb 확장을
 * 요구하므로 이미지도 동일하다).
 */
@Testcontainers
@Tag("timescaledb")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("trade_execution_plan 유저 스코프 UNIQUE 키 마이그레이션(v1.26) 검증")
class TradeExecutionPlanUniqueKeyMigrationTest {

    private static final String NARROW_CONSTRAINT = "unique_execution_plan_key";
    private static final String WIDE_CONSTRAINT = "uk_trade_exec_plan_user_date_stock_type";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("financemanage")
            .withUsername("admin")
            .withPassword("admin1234")
            .withCommand("postgres", "-c", "shared_preload_libraries=timescaledb");

    @BeforeAll
    static void applyLiquibaseChangelog() throws Exception {
        try (Connection connection = connectStatic();
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        }

        runLiquibase();

        // v1.4-test-data.yaml 이 명시적 id 로 users 를 시드하면서 시퀀스를 안 올려서,
        // 이 테스트가 새로 만드는 사용자와 id 가 충돌할 수 있다 — 동기화.
        try (Connection connection = connectStatic();
             Statement stmt = connection.createStatement()) {
            stmt.execute("SELECT setval('users_id_seq', COALESCE((SELECT MAX(id) FROM users), 1))");
        }
    }

    private static void runLiquibase() throws Exception {
        // 프로덕션과 동일한 경로(SpringLiquibase)로 적용 — classpath: 프리픽스 해석 포함.
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("mvp");
        liquibase.afterPropertiesSet();
    }

    private static Connection connectStatic() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection connect() throws SQLException {
        return connectStatic();
    }

    // ─── (a) 제약 없는 신규 DB 에서 깨끗하게 적용되는가 ────────────────────────────

    @Test
    @Order(1)
    @DisplayName("제약이 없는 신규 DB 에서도 v1.26 두 changeset 이 모두 기록된다 (drop 은 precondition 으로 MARK_RAN)")
    void changesetsApplyCleanlyOnFreshDatabase() throws Exception {
        try (Connection conn = connect()) {
            assertThat(execType(conn, "1.26.1-drop-narrow-execution-plan-unique-key"))
                    .as("제약이 없는 환경에서는 dropUniqueConstraint 를 실행하지 않고 MARK_RAN 이어야 한다")
                    .isEqualTo("MARK_RAN");
            assertThat(execType(conn, "1.26.2-add-user-scoped-execution-plan-unique-key"))
                    .as("새 제약 추가는 모든 환경에서 실제로 실행되어야 한다")
                    .isEqualTo("EXECUTED");
        }
    }

    @Test
    @Order(2)
    @DisplayName("새 UNIQUE 제약이 (user_id, execution_date, stock_code, trade_type) 순서로 존재한다")
    void wideUniqueConstraintHasUserScopedColumnsInOrder() throws Exception {
        try (Connection conn = connect()) {
            assertThat(constraintColumnsInOrder(conn, WIDE_CONSTRAINT))
                    .containsExactly("user_id", "execution_date", "stock_code", "trade_type");
        }
    }

    @Test
    @Order(3)
    @DisplayName("드리프트 제약(unique_execution_plan_key)은 마이그레이션 후 남아 있지 않다")
    void narrowConstraintIsGone() throws Exception {
        try (Connection conn = connect()) {
            assertThat(constraintExists(conn, NARROW_CONSTRAINT))
                    .as("user_id 가 빠진 좁은 제약은 어떤 환경에서도 남아 있으면 안 된다")
                    .isFalse();
        }
    }

    // ─── (b) 두 유저가 같은 날/종목/방향 → 더 이상 충돌하지 않는다 ─────────────────

    @Test
    @Order(10)
    @DisplayName("서로 다른 두 유저의 동일 (execution_date, stock_code, trade_type) INSERT 가 모두 성공한다")
    void twoUsersCanPlanTheSameStockAndSideOnTheSameDay() throws Exception {
        try (Connection conn = connect()) {
            Long userA = createUser(conn, "exec-plan-user-a");
            Long userB = createUser(conn, "exec-plan-user-b");

            insertPlan(conn, userA, "2026-05-01", "005930", "BUY");

            assertThatCode(() -> insertPlan(conn, userB, "2026-05-01", "005930", "BUY"))
                    .as("user_id 가 다르면 같은 날 같은 종목/방향이어도 충돌하면 안 된다")
                    .doesNotThrowAnyException();

            assertThat(countPlans(conn, "2026-05-01", "005930", "BUY")).isEqualTo(2);
        }
    }

    @Test
    @Order(11)
    @DisplayName("같은 유저·같은 날·같은 종목이라도 BUY/SELL 방향이 다르면 둘 다 저장된다")
    void sameUserCanPlanBothSidesForTheSameStock() throws Exception {
        try (Connection conn = connect()) {
            Long userId = createUser(conn, "exec-plan-user-both-sides");
            insertPlan(conn, userId, "2026-05-02", "000660", "BUY");

            assertThatCode(() -> insertPlan(conn, userId, "2026-05-02", "000660", "SELL"))
                    .doesNotThrowAnyException();
        }
    }

    // ─── (c) 동일 유저의 중복은 여전히 막힌다 ─────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("동일 유저가 같은 (execution_date, stock_code, trade_type) 로 두 번 INSERT 하면 여전히 막힌다")
    void sameUserDuplicateIsStillRejected() throws Exception {
        try (Connection conn = connect()) {
            Long userId = createUser(conn, "exec-plan-user-dup");
            insertPlan(conn, userId, "2026-05-03", "035720", "SELL");

            assertThatThrownBy(() -> insertPlan(conn, userId, "2026-05-03", "035720", "SELL"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(WIDE_CONSTRAINT);
        }
    }

    @Test
    @Order(21)
    @DisplayName("Stage 6 의 DELETE(user_id, execution_date) 후 배치 INSERT 흐름이 새 제약과 충돌하지 않는다")
    void deleteThenBatchInsertFlowStillWorks() throws Exception {
        // ai-agent repository.save_trade_execution_plan 은
        //   DELETE FROM trade_execution_plan WHERE user_id = ? AND execution_date = ?
        //   → 배치 INSERT
        // 로 멱등성을 확보한다. DELETE 키 (user_id, execution_date) 가 새 제약의 prefix 이므로
        // 재실행 시 충돌 가능한 행이 전부 선삭제된다 — 재실행이 반복돼도 위반이 없어야 한다.
        try (Connection conn = connect()) {
            Long userId = createUser(conn, "exec-plan-user-rerun");

            for (int run = 0; run < 3; run++) {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM trade_execution_plan WHERE user_id = ? AND execution_date = ?")) {
                    delete.setLong(1, userId);
                    delete.setDate(2, Date.valueOf("2026-05-04"));
                    delete.executeUpdate();
                }
                insertPlan(conn, userId, "2026-05-04", "005380", "BUY");
                insertPlan(conn, userId, "2026-05-04", "005380", "SELL");
                insertPlan(conn, userId, "2026-05-04", "051910", "BUY");
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT count(*) FROM trade_execution_plan WHERE user_id = ? AND execution_date = ?")) {
                select.setLong(1, userId);
                select.setDate(2, Date.valueOf("2026-05-04"));
                ResultSet rs = select.executeQuery();
                rs.next();
                assertThat(rs.getInt(1)).as("재실행해도 3행만 남아야 한다").isEqualTo(3);
            }
        }
    }

    // ─── (d) 드리프트 환경(라이브 DB 재현)에서 실제로 교체되는가 ──────────────────

    @Test
    @Order(99)
    @DisplayName("좁은 제약이 이미 있는 드리프트 DB(라이브 재현)에서는 precondition 이 통과해 실제로 DROP 된다")
    void driftedDatabaseActuallyDropsNarrowConstraint() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            // 라이브 DB 상태 재현: v1.26 이전으로 되돌린다.
            stmt.execute("DELETE FROM trade_execution_plan");
            stmt.execute("ALTER TABLE trade_execution_plan DROP CONSTRAINT " + WIDE_CONSTRAINT);
            stmt.execute("ALTER TABLE trade_execution_plan ADD CONSTRAINT " + NARROW_CONSTRAINT
                    + " UNIQUE (execution_date, stock_code, trade_type)");
            stmt.execute("DELETE FROM databasechangelog WHERE id LIKE '1.26.%'");

            assertThat(constraintExists(conn, NARROW_CONSTRAINT)).isTrue();
        }

        runLiquibase();

        try (Connection conn = connect()) {
            assertThat(execType(conn, "1.26.1-drop-narrow-execution-plan-unique-key"))
                    .as("제약이 있는 환경에서는 MARK_RAN 이 아니라 실제로 EXECUTED 되어야 한다")
                    .isEqualTo("EXECUTED");
            assertThat(constraintExists(conn, NARROW_CONSTRAINT)).isFalse();
            assertThat(constraintColumnsInOrder(conn, WIDE_CONSTRAINT))
                    .containsExactly("user_id", "execution_date", "stock_code", "trade_type");

            // 교체 후에도 두 유저 동시 저장이 가능해야 한다 (버그가 실제로 사라졌는지 최종 확인).
            Long userA = createUser(conn, "drift-user-a");
            Long userB = createUser(conn, "drift-user-b");
            insertPlan(conn, userA, "2026-06-01", "005930", "BUY");
            assertThatCode(() -> insertPlan(conn, userB, "2026-06-01", "005930", "BUY"))
                    .doesNotThrowAnyException();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String execType(Connection conn, String changesetId) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT exectype FROM databasechangelog WHERE id = ?")) {
            select.setString(1, changesetId);
            ResultSet rs = select.executeQuery();
            assertThat(rs.next()).as("changeset %s 이 databasechangelog 에 기록되어야 한다", changesetId).isTrue();
            return rs.getString(1);
        }
    }

    private boolean constraintExists(Connection conn, String constraintName) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT count(*) FROM pg_constraint WHERE conname = ? AND conrelid = 'trade_execution_plan'::regclass")) {
            select.setString(1, constraintName);
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /** UNIQUE 제약의 컬럼을 제약에 선언된 순서 그대로 반환한다(인덱스 prefix 유효성 확인용). */
    private List<String> constraintColumnsInOrder(Connection conn, String constraintName) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement("""
                SELECT a.attname
                FROM pg_constraint c
                JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE c.conname = ? AND c.conrelid = 'trade_execution_plan'::regclass
                ORDER BY k.ord
                """)) {
            select.setString(1, constraintName);
            ResultSet rs = select.executeQuery();
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            return columns;
        }
    }

    private Long createUser(Connection conn, String username) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users (username, password, email, name, phone, birth_date) " +
                        "VALUES (?, 'x', ? || '@example.com', 'Test', '01000000000', '1990-01-01') RETURNING id")) {
            String unique = username + "-" + System.nanoTime();
            insert.setString(1, unique);
            insert.setString(2, unique);
            ResultSet rs = insert.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertPlan(Connection conn, Long userId, String executionDate, String stockCode, String tradeType)
            throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO trade_execution_plan
                    (user_id, execution_date, stock_code, stock_name, trade_type,
                     planned_quantity, gemini_reason, gemini_rank, safety_filter_passed, execution_status)
                VALUES (?, ?, ?, ?, ?, 1, 'test', 1, TRUE, 'QUEUED')
                """)) {
            insert.setLong(1, userId);
            insert.setDate(2, Date.valueOf(executionDate));
            insert.setString(3, stockCode);
            insert.setString(4, stockCode);
            insert.setString(5, tradeType);
            insert.executeUpdate();
        }
    }

    private int countPlans(Connection conn, String executionDate, String stockCode, String tradeType)
            throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT count(*) FROM trade_execution_plan " +
                        "WHERE execution_date = ? AND stock_code = ? AND trade_type = ?")) {
            select.setDate(1, Date.valueOf(executionDate));
            select.setString(2, stockCode);
            select.setString(3, tradeType);
            ResultSet rs = select.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}

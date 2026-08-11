package com.inbeom.apiserver.kafka;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.TradeHistory;
import com.inbeom.apiserver.dto.kafka.TradeOrderRequestMessage;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.KisRateLimitExceededException;
import com.inbeom.apiserver.repository.TradeHistoryRepository;
import com.inbeom.apiserver.service.KisAuthService;
import com.inbeom.apiserver.service.TradeOrderIdempotencyService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 매매 주문 파이프라인 통합 테스트.
 *
 * <p>{@code _docs/Troubleshooting/1.timescaledb-migration.md} 의 검증 방식을 그대로 따른다 —
 * 실제 인프라(PostgreSQL + Kafka 컨테이너)를 띄우고 <b>프로덕션 코드 경로</b>
 * ({@code TradeOrderConsumer} → {@code TradeOrderIdempotencyService} → {@code TradingService})를
 * 그대로 태운다. 스키마도 실제 Liquibase changelog(v1.0~v1.25)를 적용해 새 멱등키 UNIQUE 제약이
 * 실제로 동작하는지까지 확인한다.
 *
 * <p>KIS 만 목(mock)이다 — 실제 주문을 낼 수는 없고, "KIS 호출 횟수"가 곧 중복 주문 방지의
 * 검증 지표이기 때문이다.
 *
 * <p>Docker 가 필요하므로 기본 {@code ./gradlew test} 에서 제외되고
 * {@code ./gradlew kafkaTest} 로만 실행된다.
 */
@Testcontainers
@Tag("kafka")
@ActiveProfiles("test")
@SpringBootTest
@DisplayName("Kafka 매매 주문 컨슈머 — 멱등성 · 재시도 · DLQ")
class TradeOrderConsumerIntegrationTest {

    /** Liquibase changelog 가 timescaledb 확장을 요구하므로 운영과 같은 이미지를 쓴다. */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("financemanage")
            .withUsername("admin")
            .withPassword("admin1234")
            .withCommand("postgres", "-c", "shared_preload_libraries=timescaledb");

    /** docker-compose 와 동일한 Apache Kafka(KRaft) 이미지. */
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // application-test.yml 의 H2 설정을 실제 PostgreSQL 컨테이너로 덮어쓴다.
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 실제 스키마를 Liquibase 로 만든다 (v1.25 멱등키 UNIQUE 제약 포함).
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.liquibase.contexts", () -> "mvp");

        registry.add("kafka.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // 재시도 백오프를 짧게 줄여야 "재시도 소진 → DLQ" 를 현실적인 시간에 검증할 수 있다.
        // 운영 기본값은 2000ms / x2 / 3회.
        registry.add("kafka.trade-order.retry-initial-interval-ms", () -> "200");
        registry.add("kafka.trade-order.retry-multiplier", () -> "2.0");
        registry.add("kafka.trade-order.retry-max-attempts", () -> "3");
    }

    /** Liquibase v1.4 가 시드하는 테스트 사용자/KIS 계좌. */
    private static final long USER_ID = 1L;
    private static final int RETRY_MAX_ATTEMPTS = 3;

    @Autowired
    private TradeHistoryRepository tradeHistoryRepository;

    @MockitoBean
    private KisApiClient kisApiClient;

    @MockitoBean
    private KisAuthService kisAuthService;

    /** PHASE 1(인프라 오류) 시뮬레이션용 — 실제 빈을 감싸서 특정 키에만 예외를 주입한다. */
    @MockitoSpyBean
    private TradeOrderIdempotencyService idempotencyService;

    private static KafkaProducer<String, String> producer;
    private static KafkaConsumer<String, String> outputConsumer;
    private static final List<ConsumerRecord<String, String>> drained = new ArrayList<>();

    @BeforeAll
    static void startClients() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        outputConsumer = new KafkaConsumer<>(consumerProps);
        outputConsumer.subscribe(List.of(TradeOrderTopics.RESULT, TradeOrderTopics.DLQ));
    }

    @AfterAll
    static void stopClients() {
        if (producer != null) {
            producer.close();
        }
        if (outputConsumer != null) {
            outputConsumer.close();
        }
    }

    @BeforeEach
    void resetKisMocks() {
        Mockito.reset(kisApiClient, kisAuthService);
        when(kisAuthService.getKisAccessToken(anyLong())).thenReturn("mock-token");
        when(kisAuthService.getKisCredentials(anyLong())).thenReturn(new KisAuthService.KisCredentials(
                "mock-app-key", "mock-app-secret", "50000000", "01",
                "https://openapivts.koreainvestment.com:29443"));
    }

    // ==================================================================
    // (a) 정상 주문
    // ==================================================================

    @Test
    @DisplayName("(a) 정상 주문 → trade_history EXECUTED 기록 + trade.order.result SUCCESS 발행")
    void successfulOrderRecordsHistoryAndPublishesResult() {
        String key = idempotencyKey("005930", "BUY");
        stubKisOrderAccepted("0000117057");

        publishOrder(key, "005930", "BUY", 10);

        TradeHistory row = awaitTradeHistory(key);
        assertThat(row.getOrderStatus()).isEqualTo(TradeOrderIdempotencyService.STATUS_EXECUTED);
        assertThat(row.getOrderNumber()).isEqualTo("0000117057");
        assertThat(row.getOrderType()).isEqualTo("buy");
        assertThat(row.getQuantity()).isEqualTo(10);
        assertThat(row.getExecutedAt()).isNotNull();

        String result = awaitMessage(TradeOrderTopics.RESULT, key);
        assertThat(result).contains("\"status\":\"SUCCESS\"");
        assertThat(result).contains("\"kisOrderNo\":\"0000117057\"");

        // 정상 건은 DLQ 로 가지 않는다.
        assertThat(findMessage(TradeOrderTopics.DLQ, key)).isEmpty();

        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("(a-2) SELL 주문도 동일하게 처리된다")
    void sellOrderIsRecorded() {
        String key = idempotencyKey("000660", "SELL");
        stubKisOrderAccepted("0000117099");

        publishOrder(key, "000660", "SELL", 3);

        TradeHistory row = awaitTradeHistory(key);
        assertThat(row.getOrderStatus()).isEqualTo(TradeOrderIdempotencyService.STATUS_EXECUTED);
        assertThat(row.getOrderType()).isEqualTo("sell");
    }

    // ==================================================================
    // (b) 멱등성 — 핵심
    // ==================================================================

    @Test
    @DisplayName("(b) 동일 idempotencyKey 를 두 번 발행해도 KIS 주문은 정확히 1번만 나간다")
    void duplicateMessageDoesNotProduceDuplicateKisOrder() {
        String key = idempotencyKey("035420", "BUY");
        stubKisOrderAccepted("0000118000");

        publishOrder(key, "035420", "BUY", 5);
        TradeHistory first = awaitTradeHistory(key);
        assertThat(first.getOrderStatus()).isEqualTo(TradeOrderIdempotencyService.STATUS_EXECUTED);

        // 같은 메시지 재발행 (ai-agent 재시도 / Kafka 재전달 시나리오)
        publishOrder(key, "035420", "BUY", 5);
        publishOrder(key, "035420", "BUY", 5);
        sleep(3000);

        // KIS 주문은 여전히 1번.
        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));

        // trade_history 행도 1개뿐 (DB UNIQUE 제약이 최종 방어선).
        assertThat(tradeHistoryRepository.findByIdempotencyKey(key)).isPresent();
        long rows = tradeHistoryRepository.findAll().stream()
                .filter(t -> key.equals(t.getIdempotencyKey()))
                .count();
        assertThat(rows).isEqualTo(1);
    }

    // ==================================================================
    // (c) KIS 타임아웃 — 재시도 금지
    // ==================================================================

    @Test
    @DisplayName("(c) KIS 타임아웃 → 재시도 없이 KIS 1회 호출로 끝나고 즉시 DLQ + FAILED 기록")
    void kisTimeoutGoesStraightToDlqWithoutRetry() {
        String key = idempotencyKey("051910", "BUY");

        // KisApiClient 가 실제로 하는 것과 동일하게 감싼다:
        // ResourceAccessException(네트워크 타임아웃) → KisApiException.networkError(4003).
        // 즉 "KIS 도달 여부 불확실" 실패도 KisApiException 으로 올라온다 —
        // 그래서 예외 타입이 아니라 '호출 단계'로 재시도 여부를 가른다.
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class)))
                .thenThrow(KisApiException.networkError(
                        "KIS API network error: Read timed out",
                        new ResourceAccessException("Read timed out")));

        publishOrder(key, "051910", "BUY", 7);

        String dlq = awaitMessage(TradeOrderTopics.DLQ, key);
        assertThat(dlq).contains("KIS 호출 단계 실패");
        assertThat(dlq).contains("\"retryCount\":0");

        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));

        // ↑ KIS 호출 1회만으로는 "재시도 안 함"을 증명하지 못한다. 설령 재전달되더라도 멱등성
        // 계층(claim)이 걸러내 KIS 호출은 어차피 1회로 유지되기 때문이다(방어의 이중화).
        // 그래서 "레코드가 애초에 재전달되지 않았다"를 직접 관측한다.
        //
        // 관측 전 정착 대기(settle)가 반드시 필요하다: DLQ 발행은 재전달보다 먼저 일어나므로,
        // 기다리지 않고 단언하면 재시도가 살아나도 테스트가 통과해 버린다(mutation 으로 실제 확인).
        // 테스트 백오프가 200ms(x2)이므로 2초면 첫 재전달을 충분히 관측할 수 있다.
        sleep(2000);

        //   (1) 리스너 진입 지표인 claim() 이 이 키에 대해 정확히 1번만 불렸는가
        //   (2) DLQ 메시지가 정확히 1건인가 (재전달됐다면 실패마다 쌓인다)
        verify(idempotencyService, times(1))
                .claim(argThat(msg -> key.equals(msg.idempotencyKey())));
        assertThat(countMessages(TradeOrderTopics.DLQ, key))
                .as("KIS 단계 실패는 재전달되지 않으므로 DLQ 는 정확히 1건이어야 한다")
                .isEqualTo(1);

        TradeHistory row = tradeHistoryRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(row.getOrderStatus()).isEqualTo(TradeOrderIdempotencyService.STATUS_FAILED);

        String result = awaitMessage(TradeOrderTopics.RESULT, key);
        assertThat(result).contains("\"status\":\"FAILED\"");
    }

    @Test
    @DisplayName("(c-2) KIS 비즈니스 오류(잔고부족 등)도 재시도 없이 FAILED + DLQ")
    void kisBusinessErrorIsNotRetried() {
        String key = idempotencyKey("068270", "BUY");
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class)))
                .thenThrow(KisApiException.clientError("KIS API error (HTTP 400): 주문가능금액이 부족합니다"));

        publishOrder(key, "068270", "BUY", 100);

        String dlq = awaitMessage(TradeOrderTopics.DLQ, key);
        assertThat(dlq).contains("\"retryCount\":0");

        verify(kisApiClient, times(1)).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));
        assertThat(tradeHistoryRepository.findByIdempotencyKey(key).orElseThrow().getOrderStatus())
                .isEqualTo(TradeOrderIdempotencyService.STATUS_FAILED);
    }

    // ==================================================================
    // (d) 인프라 오류 — 재시도 후 DLQ
    // ==================================================================

    @Test
    @DisplayName("(d) KIS 호출 전 인프라 오류 → 설정된 횟수만큼 재시도 후 DLQ (KIS 는 한 번도 호출 안 됨)")
    void infrastructureErrorIsRetriedThenDeadLettered() {
        String key = idempotencyKey("207940", "BUY");
        stubKisOrderAccepted("0000119000");

        // 멱등키 선점(=DB 접근) 단계에서 실패시킨다. KIS 는 아직 건드리지 않은 상태이므로
        // 재시도해도 중복 주문 위험이 없다 — 이 경로만 재시도 대상이다.
        AtomicInteger claimAttempts = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            TradeOrderRequestMessage msg = invocation.getArgument(0);
            if (key.equals(msg.idempotencyKey())) {
                claimAttempts.incrementAndGet();
                throw new QueryTimeoutException("simulated DB connection failure");
            }
            return invocation.callRealMethod();
        }).when(idempotencyService).claim(any(TradeOrderRequestMessage.class));

        publishOrder(key, "207940", "BUY", 2);

        String dlq = awaitMessage(TradeOrderTopics.DLQ, key, Duration.ofSeconds(60));
        assertThat(dlq).contains("재시도 소진");
        assertThat(dlq).contains("QueryTimeoutException");

        // 최초 1회 + 재시도 3회 = 총 4번 전달, DLQ 에는 재시도 횟수 3 이 기록된다.
        assertThat(claimAttempts.get()).isEqualTo(RETRY_MAX_ATTEMPTS + 1);
        assertThat(dlq).contains("\"retryCount\":" + RETRY_MAX_ATTEMPTS);

        // KIS 는 단 한 번도 호출되지 않았다.
        verify(kisApiClient, never()).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));

        // 주문이 조용히 사라지지 않도록 FAILED 결과도 발행된다.
        assertThat(awaitMessage(TradeOrderTopics.RESULT, key)).contains("\"status\":\"FAILED\"");

        Mockito.reset(idempotencyService);
    }

    // ==================================================================
    // (f) 자체 rate limit 거부 — KIS 미접촉이므로 재시도 가능(PHASE 1)
    // ==================================================================

    @Test
    @DisplayName("(f) rate limit 거부는 재시도되고, 버킷이 회복되면 주문이 정상 체결된다")
    void rateLimitRejectionIsRetriedAndEventuallySucceeds() {
        String key = idempotencyKey("005490", "BUY");

        // 1회차: 토큰 버킷이 KIS 로 요청을 보내기 전에 거부. 2회차(재시도): 버킷 회복 → 성공.
        Map<String, Object> accepted = new HashMap<>();
        accepted.put("rt_cd", "0");
        accepted.put("output", new HashMap<>(Map.of("ODNO", "0000120000")));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException(
                        "KIS 호출 한도를 초과해 요청을 보내지 않았습니다 (trId=VTTC0802U)"))
                .thenReturn(new ResponseEntity<>(accepted, HttpStatus.OK));

        publishOrder(key, "005490", "BUY", 4);

        // 재시도 끝에 성공해야 한다 — 즉시 DLQ 로 유실되면 이 단언에서 걸린다.
        TradeHistory row = awaitTradeHistory(key);
        assertThat(row.getOrderStatus()).isEqualTo(TradeOrderIdempotencyService.STATUS_EXECUTED);
        assertThat(row.getOrderNumber()).isEqualTo("0000120000");

        assertThat(awaitMessage(TradeOrderTopics.RESULT, key)).contains("\"status\":\"SUCCESS\"");

        // 재전달이 실제로 일어났음을 리스너 진입 지표(claim 호출 횟수)로 확인한다.
        verify(idempotencyService, times(2))
                .claim(argThat(msg -> key.equals(msg.idempotencyKey())));

        // 그리고 결정적으로 — DLQ 로는 가지 않았다. PHASE 2 로 오분류되면 1회차에서 즉시 실렸을 것이다.
        assertThat(findMessage(TradeOrderTopics.DLQ, key))
                .as("rate limit 거부는 확정 실패가 아니므로 DLQ 로 가면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("(f-2) rate limit 이 계속되면 '재시도 소진'으로 DLQ 에 가고, 즉시 DLQ(PHASE 2) 가 아니다")
    void persistentRateLimitExhaustsRetriesInsteadOfImmediateDlq() {
        String key = idempotencyKey("009150", "SELL");

        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), eq(Map.class)))
                .thenThrow(new KisRateLimitExceededException(
                        "KIS 호출 한도를 초과해 요청을 보내지 않았습니다 (trId=VTTC0801U)"));

        publishOrder(key, "009150", "SELL", 6);

        String dlq = awaitMessage(TradeOrderTopics.DLQ, key, Duration.ofSeconds(60));

        // PHASE 1 경로(재시도 소진 → DlqRecoverer)로 들어왔음을 사유 문구와 retryCount 로 구분한다.
        // PHASE 2 로 오분류됐다면 "KIS 호출 단계 실패" + retryCount=0 이 찍혔을 것이다.
        assertThat(dlq).contains("재시도 소진");
        assertThat(dlq).doesNotContain("KIS 호출 단계 실패");
        assertThat(dlq).contains("\"retryCount\":" + RETRY_MAX_ATTEMPTS);

        assertThat(claimCountFor(key))
                .as("최초 1회 + 재시도 %d회 만큼 재전달되어야 한다", RETRY_MAX_ATTEMPTS)
                .isEqualTo(RETRY_MAX_ATTEMPTS + 1);

        // 선점 행이 매번 반납되므로, 재시도가 "이전 시도가 PENDING" 으로 막히지 않는다.
        // 반납이 빠지면 2회차부터 claim 이 duplicate 로 끊겨 위 재전달 횟수 단언이 무의미해진다.
        assertThat(tradeHistoryRepository.findByIdempotencyKey(key))
                .as("KIS 를 건드리지 않았으므로 PENDING 잔여 행이 남아서는 안 된다")
                .isEmpty();
    }

    private long claimCountFor(String key) {
        return Mockito.mockingDetails(idempotencyService).getInvocations().stream()
                .filter(inv -> "claim".equals(inv.getMethod().getName()))
                .filter(inv -> {
                    Object arg = inv.getArgument(0);
                    return arg instanceof TradeOrderRequestMessage msg && key.equals(msg.idempotencyKey());
                })
                .count();
    }

    // ==================================================================
    // 계약 위반
    // ==================================================================

    @Test
    @DisplayName("(e) 계약 위반 메시지(side 오류)는 재시도 없이 DLQ 로 가고 KIS 를 호출하지 않는다")
    void invalidMessageGoesToDlq() {
        String key = idempotencyKey("005380", "HOLD");
        publishOrder(key, "005380", "HOLD", 1);

        String dlq = awaitMessage(TradeOrderTopics.DLQ, key);
        assertThat(dlq).contains("계약 위반");

        verify(kisApiClient, never()).post(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), eq(Map.class));
        assertThat(tradeHistoryRepository.findByIdempotencyKey(key)).isEmpty();
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private void stubKisOrderAccepted(String orderNumber) {
        Map<String, Object> body = new HashMap<>();
        body.put("rt_cd", "0");
        body.put("msg1", "주문 전송 완료 되었습니다.");
        body.put("output", new HashMap<>(Map.of("ODNO", orderNumber)));
        when(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(),
                        anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private String idempotencyKey(String stockCode, String side) {
        // 테스트 간 키 충돌을 막기 위해 날짜 자리에 랜덤 값을 넣는다(형식은 계약과 동일).
        return USER_ID + ":" + stockCode + ":2026-08-09-" + UUID.randomUUID().toString().substring(0, 8) + ":" + side;
    }

    private void publishOrder(String key, String stockCode, String side, int quantity) {
        String json = """
                {
                  "idempotencyKey": "%s",
                  "userId": %d,
                  "stockCode": "%s",
                  "side": "%s",
                  "quantity": %d,
                  "price": 0,
                  "tradeDate": "2026-08-09",
                  "requestedAt": "2026-08-09T08:55:00+09:00"
                }
                """.formatted(key, USER_ID, stockCode, side, quantity);
        producer.send(new ProducerRecord<>(TradeOrderTopics.REQUESTED, key, json));
        producer.flush();
    }

    private TradeHistory awaitTradeHistory(String key) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<TradeHistory> found = tradeHistoryRepository.findByIdempotencyKey(key);
            if (found.isPresent() && !TradeOrderIdempotencyService.STATUS_PENDING.equals(found.get().getOrderStatus())) {
                return found.get();
            }
            sleep(300);
        }
        throw new AssertionError("trade_history 행이 확정 상태로 나타나지 않았다: key=" + key);
    }

    private String awaitMessage(String topic, String key) {
        return awaitMessage(topic, key, Duration.ofSeconds(30));
    }

    private String awaitMessage(String topic, String key, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<String> found = findMessage(topic, key);
            if (found.isPresent()) {
                return found.get();
            }
            drainOutputTopics();
        }
        throw new AssertionError(topic + " 에서 key=" + key + " 메시지를 찾지 못했다");
    }

    /**
     * 특정 키로 해당 토픽에 쌓인 메시지 개수. "재전달되지 않았다"를 관측하는 지표다.
     * 세기 전에 잔여 메시지를 충분히 흡수해, 늦게 도착한 중복을 놓치지 않도록 한다.
     */
    private long countMessages(String topic, String key) {
        for (int i = 0; i < 4; i++) {
            drainOutputTopics();
        }
        return drained.stream()
                .filter(r -> r.topic().equals(topic))
                .filter(r -> key.equals(r.key()) || (r.value() != null && r.value().contains(key)))
                .count();
    }

    private Optional<String> findMessage(String topic, String key) {
        drainOutputTopics();
        return drained.stream()
                .filter(r -> r.topic().equals(topic))
                .filter(r -> key.equals(r.key()) || (r.value() != null && r.value().contains(key)))
                .map(ConsumerRecord::value)
                .reduce((first, second) -> second);
    }

    private void drainOutputTopics() {
        ConsumerRecords<String, String> records = outputConsumer.poll(Duration.ofMillis(500));
        records.forEach(drained::add);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

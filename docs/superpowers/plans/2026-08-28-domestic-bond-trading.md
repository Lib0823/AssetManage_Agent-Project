# 장내채권 거래 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비활성 상태인 채권 탭을 KIS 장내채권 API 기반의 실거래 기능(검색·시세·호가·잔고·매수·매도)으로 만든다.

**Architecture:** 기존 KIS 인프라를 최대한 재사용한다 — `KisApiClient`(공통 헤더·rate limit·캐시·실전 도메인)와 `KisAuthService`(사용자별 자격증명)를 그대로 쓰고, 채권 전용 TR_ID 상수·응답 파싱·주문 파라미터 매핑만 신규 클래스가 담당한다. 거래이력은 주식과 스키마가 맞지 않아 별도 테이블에 쌓는다.

**Tech Stack:** Spring Boot 4.1 / Java 21 / Liquibase / JUnit 5 + Mockito, Vue 3 + Vite.

**참조 스펙:** `docs/superpowers/specs/2026-08-28-domestic-bond-trading-design.md`

## Global Constraints

- **실전투자 전용.** 모의투자 TR_ID·도메인 분기를 만들지 않는다(2026-08 전환 결과 유지).
- **채권 단가는 소수점을 갖는다.** 단가·금액 관련 값은 전 구간 `BigDecimal`(Java) / `NUMERIC`(DB) / 문자열 파싱(JS)으로 다룬다. `int`/`long`/`double` 금지.
- **채권 종목코드(PDNO)는 12자리 문자열**(예: `KR1234567890`)이다. 주식의 6자리 숫자 코드 가정을 재사용하지 않는다.
- 조회 경로는 graceful degrade(200 + 빈 결과 + `notice`), 주문 경로는 예외 전파(4xx/5xx + `success=false`) — 기존 국내/해외 주식 패턴과 동일.
- 모든 응답은 `ApiResponse<T>`로 감싼다.
- Liquibase changeset은 새 파일 추가만 한다. 기존 changeset 수정 금지. 현재 최신은 `v1.28`.
- 이 계획은 **ai-agent를 건드리지 않는다.**

---

## Task 0 (팀리드): 개발 착수 전 사전 검토

**Files:** 없음(읽기 전용 조사)

**목적:** 스펙은 공개 예제 저장소와 문서 조사에 기반한다. 실제 구현 전에 "예제와 실제 응답이 다를 수 있다"는 리스크를 줄인다.

- [ ] **Step 1: KIS 채권 API 응답 스키마 확인**

공식 예제의 응답 파싱부를 읽어 실제 필드명을 확보한다:
```bash
for d in search_bond_info inquire_price inquire_asking_price inquire_balance inquire_psbl_order; do
  f=$(curl -s "https://api.github.com/repos/koreainvestment/open-trading-api/contents/examples_llm/domestic_bond/$d" | grep '"name"' | grep -v chk_ | sed 's/.*"name": "\(.*\)",/\1/')
  echo "=== $d ==="
  curl -s "https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_bond/$d/$f"
done
```
각 API의 **요청 파라미터 이름**과 **응답 output 구조**(output / output1 / output2 중 무엇인지, 배열인지 객체인지)를 정리해 `_workspace/bond_api_contract.md`에 기록한다. 이것이 Task 2~4의 DTO 설계 근거가 된다.

- [ ] **Step 2: 조회 API를 실계좌로 1회 호출해 응답 확인 (사용자 협조 필요)**

`search_bond_info`(검색)와 `inquire_price`(시세)는 **조회 전용이라 자금이 움직이지 않는다.** 사용자에게 KIS 실전 앱키가 등록된 계좌로 이 두 API를 1회 호출해줄 것을 요청하고, 실제 JSON 응답을 받아 Step 1의 추정과 대조한다.

응답을 받을 수 없으면 그 사실을 명시하고 진행한다 — 다만 이 경우 Task 2~4의 DTO는 "예제 기반 추정"이며 실계좌 검증 시 수정이 필요할 수 있음을 계획에 남긴다.

- [ ] **Step 3: 채권 종목코드·단가 실제 형식 확인**

Step 1~2의 결과에서 다음을 확정한다:
- PDNO가 정말 12자리 `KR` 접두 형식인지
- 단가 필드가 문자열인지 숫자인지, 소수점 자릿수는 몇 자리인지
- 수량 단위(액면 10,000원 단위인지 1좌 단위인지)

이 셋은 DTO 타입과 UI 입력 검증을 좌우한다.

- [ ] **Step 4: 검토 결과 보고**

`_workspace/bond_api_contract.md`를 완성하고, 스펙과 어긋나는 점이 있으면 **구현을 시작하기 전에** 팀리드에게 보고한다. 스펙 수정이 필요하면 스펙부터 고친다.

---

## Task 1 (backend-engineer): DB — `bond_trade_history` 테이블

**Files:**
- Create: `api-server/src/main/resources/db/changelog/mvp/v1.29-bond-trade-history.yaml`
- Modify: `api-server/src/main/resources/db/changelog/db.changelog-master.yaml` (include 추가)
- Create: `api-server/src/main/java/com/inbeom/apiserver/domain/BondTradeHistory.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/repository/BondTradeHistoryRepository.java`

**Interfaces:**
- Produces: `BondTradeHistory` 엔티티와 `BondTradeHistoryRepository`. Task 4가 주문 성공 시 여기에 기록한다.

- [ ] **Step 1: changeset 작성**

`v1.29-bond-trade-history.yaml`:
```yaml
databaseChangeLog:
  - changeSet:
      id: 1.29.1-create-bond-trade-history
      author: inbeom
      context: mvp
      changes:
        - createTable:
            tableName: bond_trade_history
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: user_id, type: BIGINT, constraints: { nullable: false } }
              - column: { name: bond_code, type: VARCHAR(12), constraints: { nullable: false } }
              - column: { name: bond_name, type: VARCHAR(100) }
              - column: { name: order_type, type: VARCHAR(10), constraints: { nullable: false } }
              - column: { name: order_status, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: quantity, type: BIGINT, constraints: { nullable: false } }
              - column: { name: order_unit_price, type: "NUMERIC(15,4)", constraints: { nullable: false } }
              - column: { name: order_number, type: VARCHAR(30) }
              - column: { name: ordered_at, type: TIMESTAMP, constraints: { nullable: false } }
              - column: { name: executed_at, type: TIMESTAMP }
              - column: { name: created_at, type: TIMESTAMP, defaultValueComputed: now(), constraints: { nullable: false } }
        - addForeignKeyConstraint:
            constraintName: fk_bond_trade_history_user
            baseTableName: bond_trade_history
            baseColumnNames: user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: CASCADE
        - createIndex:
            indexName: idx_bond_trade_history_user_ordered
            tableName: bond_trade_history
            columns:
              - column: { name: user_id }
              - column: { name: ordered_at }
      rollback:
        - dropTable:
            tableName: bond_trade_history
```

`db.changelog-master.yaml`의 include 목록 맨 끝(v1.28 다음)에 추가한다.

- [ ] **Step 2: 엔티티·리포지토리 작성**

`BondTradeHistory.java` — `TradeHistory.java`의 Lombok 패턴(`@Entity @Table @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`)을 그대로 따르되, `orderUnitPrice`는 `BigDecimal`, `bondCode`는 `String`.

`BondTradeHistoryRepository.java`:
```java
@Repository
public interface BondTradeHistoryRepository extends JpaRepository<BondTradeHistory, Long> {
    List<BondTradeHistory> findByUserIdOrderByOrderedAtDesc(Long userId);
}
```

- [ ] **Step 3: 서버 기동으로 changeset 적용 확인**

```bash
cd api-server && JWT_SECRET=dummy-secret-for-local-development-only-32bytes JASYPT_PASSWORD=dummy ./gradlew bootRun
```
기동 로그에서 `1.29.1-create-bond-trade-history` 적용을 확인하고, `psql`로 테이블·FK·인덱스 존재를 확인한다:
```bash
PGPASSWORD=admin1234 psql -h localhost -U admin -d financemanage -c "\d bond_trade_history"
```
확인 후 서버를 종료한다.

- [ ] **Step 4: 커밋** — `feat(db): add bond_trade_history table`

---

## Task 2 (backend-engineer): 채권 시세 조회 (검색·발행정보·현재가·호가)

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/BondQuoteService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondSearchResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondIssueInfoResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondPriceResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondOrderbookResponse.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/client/KisBondClientTest.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/BondQuoteServiceTest.java`

**Interfaces:**
- Consumes: 기존 `KisApiClient.get(baseUrl, endpoint, trId, kisToken, appKey, appSecret, queryParams, responseType)` 8-arg 오버로드, `KisQuoteService`(앱 레벨 시세 자격증명).
- Produces:
  - `KisBondClient` 상수: `TR_SEARCH_BOND_INFO="CTPF1114R"`, `TR_ISSUE_INFO="CTPF1101R"`, `TR_INQUIRE_PRICE="FHKBJ773400C0"`, `TR_INQUIRE_ASKING_PRICE="FHKBJ773401C0"`
  - `BondQuoteService.search(String keyword) -> List<BondSearchResponse>`
  - `BondQuoteService.getIssueInfo(String bondCode) -> BondIssueInfoResponse`
  - `BondQuoteService.getPrice(String bondCode) -> BondPriceResponse`
  - `BondQuoteService.getOrderbook(String bondCode) -> BondOrderbookResponse`
  - 각 응답 DTO는 실패 시를 위한 `notice` 필드(String, nullable)를 갖는다.

- [ ] **Step 1: TR_ID 계약 테스트를 먼저 작성 (실패 확인)**

`KisBondClientTest.java`:
```java
@DisplayName("KisBondClient — 채권 TR_ID·엔드포인트 계약")
class KisBondClientTest {

    @Test
    @DisplayName("채권 TR_ID 상수가 KIS 실전값으로 고정돼 있다")
    void bondTrIdsAreRealTradingValues() {
        assertThat(KisBondClient.TR_SEARCH_BOND_INFO).isEqualTo("CTPF1114R");
        assertThat(KisBondClient.TR_ISSUE_INFO).isEqualTo("CTPF1101R");
        assertThat(KisBondClient.TR_INQUIRE_PRICE).isEqualTo("FHKBJ773400C0");
        assertThat(KisBondClient.TR_INQUIRE_ASKING_PRICE).isEqualTo("FHKBJ773401C0");
    }

    @Test
    @DisplayName("채권 엔드포인트 경로가 /uapi/domestic-bond/v1/** 이다")
    void bondEndpointsUseDomesticBondPath() {
        assertThat(KisBondClient.PATH_SEARCH_BOND_INFO)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/search-bond-info");
        assertThat(KisBondClient.PATH_INQUIRE_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-price");
        assertThat(KisBondClient.PATH_INQUIRE_ASKING_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-asking-price");
    }
}
```

> 이 테스트가 존재하는 이유: 2026-08 QA에서 실시간 DTO의 필드 케이싱 드리프트가 테스트 부재로 오래 방치된 전례가 있다. 외부 계약 상수는 테스트로 고정한다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd api-server && ./gradlew test --tests "*KisBondClientTest*"`
Expected: FAIL — `KisBondClient` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: `KisBondClient` 구현**

TR_ID·경로 상수를 public static final로 두고, `KisApiClient`에 위임하는 얇은 래퍼로 만든다. 자체 HTTP 로직을 만들지 않는다(rate limit·캐시·에러 정규화를 `KisApiClient`가 이미 한다).

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd api-server && ./gradlew test --tests "*KisBondClientTest*"` → PASS

- [ ] **Step 5: `BondQuoteService` 테스트 작성 (graceful degrade 고정)**

`BondQuoteServiceTest.java` — `KisApiClient`를 `@Mock`으로 두고 최소 3개:
```java
@Test
@DisplayName("KIS 실패 시 예외 대신 notice가 담긴 빈 결과를 준다")
void kisFailure_returnsEmptyWithNotice() {
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willThrow(KisApiException.serverError("KIS down", null));

    BondPriceResponse result = bondQuoteService.getPrice("KR1234567890");

    assertThat(result.getNotice()).isNotBlank();
    assertThat(result.getCurrentPrice()).isNull();
}

@Test
@DisplayName("호가가 비어도(유동성 부족) 예외를 던지지 않는다")
void emptyOrderbook_doesNotThrow() {
    Map<String, Object> body = Map.of("rt_cd", "0", "output", Map.of());
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

    assertThatCode(() -> bondQuoteService.getOrderbook("KR1234567890"))
            .doesNotThrowAnyException();
}

@Test
@DisplayName("단가를 BigDecimal로 파싱해 소수점을 잃지 않는다")
void unitPriceKeepsDecimals() {
    Map<String, Object> body = Map.of("rt_cd", "0",
            "output", Map.of("bond_prpr", "9850.5"));
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

    BondPriceResponse result = bondQuoteService.getPrice("KR1234567890");

    assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("9850.5"));
}
```

> **주의**: `output` 안의 실제 필드명(`bond_prpr` 등)은 Task 0에서 확보한 `_workspace/bond_api_contract.md`의 값으로 교체한다. 위 이름은 자리표시가 아니라 예시이며, Task 0 결과와 다르면 **Task 0 결과가 정답**이다.

- [ ] **Step 6: 테스트 실패 확인 → 구현 → 통과 확인**

Run: `cd api-server && ./gradlew test --tests "*BondQuoteServiceTest*"`

- [ ] **Step 7: 커밋** — `feat(api-server): add bond quote lookup (search, price, orderbook)`

---

## Task 3 (backend-engineer): 채권 잔고·매수가능 조회

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java` (TR 상수 추가)
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/BondTradingService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondBalanceResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondOrderableResponse.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/BondTradingServiceTest.java`

**Interfaces:**
- Consumes: `KisAuthService.getKisAccessToken(Long kisAccountId)`, `KisAuthService.getKisCredentials(Long kisAccountId)` → `KisCredentials(appKey, appSecret, accountNumber, accountProductCode, baseUrl)`.
- Produces:
  - `KisBondClient.TR_INQUIRE_BALANCE="CTSC8407R"`, `KisBondClient.TR_INQUIRE_PSBL_ORDER="TTTC8910R"`
  - `BondTradingService.getBalance(Long userId) -> BondBalanceResponse`
  - `BondTradingService.getOrderable(Long userId, String bondCode, BigDecimal price) -> BondOrderableResponse`
  - private `requireKisAccountId(User user)` — Task 4가 재사용한다.

- [ ] **Step 1: 널 가드 테스트 먼저 작성**

```java
@Test
@DisplayName("KIS 계좌가 없으면 KisAccountNotFoundException (NPE 아님)")
void noKisAccount_throwsKisAccountNotFound() {
    User user = User.builder().id(1L).build();   // kisAccount == null
    given(userRepository.findById(1L)).willReturn(Optional.of(user));

    assertThatThrownBy(() -> bondTradingService.getBalance(1L))
            .isInstanceOf(KisAccountNotFoundException.class);
}
```

> 2026-08 QA에서 `TradingService`의 5개 지점이 이 가드가 없어 4004 대신 500을 내던 이슈가 있었다. 채권은 처음부터 가드를 갖고 시작한다.

- [ ] **Step 2: 실패 확인 → `BondTradingService` 구현 → 통과 확인**

`OverseasTradingService:474`의 가드 패턴을 따른다:
```java
private Long requireKisAccountId(User user) {
    if (user.getKisAccount() == null) {
        throw new KisAccountNotFoundException("KIS account not registered for userId: " + user.getId());
    }
    return user.getKisAccount().getId();
}
```

조회 경로이므로 KIS 실패 시 graceful degrade(빈 결과 + `notice`).

- [ ] **Step 3: 잔고·매수가능 정상 경로 테스트 추가 → 통과 확인**

Run: `cd api-server && ./gradlew test --tests "*BondTradingServiceTest*"`

- [ ] **Step 4: 커밋** — `feat(api-server): add bond balance and orderable lookup`

---

## Task 4 (backend-engineer): 채권 매수·매도 주문

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java` (TR 상수 추가)
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/BondTradingService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondOrderRequest.java`
- Modify: `api-server/src/test/java/com/inbeom/apiserver/service/BondTradingServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `BondTradeHistoryRepository`, Task 3의 `requireKisAccountId`.
- Produces:
  - `KisBondClient.TR_BUY="TTTC0952U"`, `KisBondClient.TR_SELL="TTTC0958U"`
  - `BondTradingService.buy(Long userId, BondOrderRequest request) -> Map<String, Object>`
  - `BondTradingService.sell(Long userId, BondOrderRequest request) -> Map<String, Object>`
  - `BondOrderRequest`: `bondCode`(String), `bondName`(String), `quantity`(Long), `unitPrice`(BigDecimal)

- [ ] **Step 1: 주문 파라미터 매핑 테스트 먼저 작성**

```java
@Test
@DisplayName("매수 주문이 KIS 채권 파라미터 형식으로 나간다")
void buy_sendsKisBondParameters() {
    // ... user/credentials stub, KIS 성공 응답 stub
    bondTradingService.buy(1L, new BondOrderRequest("KR1234567890", "국고채권", 10L, new BigDecimal("9850.5")));

    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(kisApiClient).post(anyString(), eq("/uapi/domestic-bond/v1/trading/buy"),
            eq("TTTC0952U"), anyString(), anyString(), anyString(), body.capture(), eq(Map.class));

    Map<String, Object> sent = body.getValue();
    assertThat(sent.get("PDNO")).isEqualTo("KR1234567890");
    assertThat(sent.get("ORD_QTY2")).isEqualTo("10");
    assertThat(sent.get("BOND_ORD_UNPR")).isEqualTo("9850.5");   // 소수점 보존
    assertThat(sent.get("SAMT_MKET_PTCI_YN")).isEqualTo("N");
    assertThat(sent.get("BOND_RTL_MKET_YN")).isEqualTo("Y");
}

@Test
@DisplayName("주문 실패는 degrade하지 않고 예외를 전파한다")
void orderFailure_propagates() {
    Map<String, Object> failBody = Map.of("rt_cd", "1", "msg1", "잔고 부족");
    given(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(failBody, HttpStatus.OK));

    assertThatThrownBy(() -> bondTradingService.buy(1L, validRequest()))
            .isInstanceOf(BusinessException.class);
}

@Test
@DisplayName("주문 성공 시 bond_trade_history에 기록한다")
void successfulOrder_recordsHistory() {
    // ... KIS 성공 응답 stub
    bondTradingService.buy(1L, validRequest());
    verify(bondTradeHistoryRepository).save(any(BondTradeHistory.class));
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd api-server && ./gradlew test --tests "*BondTradingServiceTest*"`
Expected: FAIL — `buy` 메서드 없음.

- [ ] **Step 3: `buy`/`sell` 구현**

`SAMT_MKET_PTCI_YN="N"`, `BOND_RTL_MKET_YN="Y"`를 서버가 채운다(스펙 §2). `BOND_ORD_UNPR`은 `BigDecimal.toPlainString()`으로 넣어 지수표기를 피한다. 응답 `rt_cd != "0"`이면 국내주식 `verifyKisOrderSuccess`와 동일하게 예외를 던진다. 성공 시 `bond_trade_history`에 기록한다.

- [ ] **Step 4: 통과 확인 → 커밋** — `feat(api-server): add bond buy/sell orders`

---

## Task 5 (backend-engineer): `BondController` + Security 설정 + 거래이력 조회

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/controller/BondController.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondTradeHistoryResponse.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/config/SecurityConfig.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/BondTradingService.java` (history 조회 추가)

**Interfaces:**
- Consumes: Task 2~4의 서비스 메서드, `JwtTokenProvider.resolveBearerToken` / `getUserIdFromToken`.
- Produces: 스펙 §2의 엔드포인트 9종. 프론트(Task 6~8)가 이 계약을 소비한다.

- [ ] **Step 1: 컨트롤러 작성**

`OverseasController`의 구조를 그대로 따른다 — `@RequestMapping("/bonds")`, 생성자 주입, `ApiResponse.success(message, data)` 래핑, AUTH 경로는 `@RequestHeader("Authorization")` → `resolveBearerToken` → `getUserIdFromToken`.

| 메서드 | 경로 | 인증 |
|---|---|---|
| GET | `/bonds/search?keyword=` | PUBLIC |
| GET | `/bonds/{bondCode}/issue-info` | PUBLIC |
| GET | `/bonds/{bondCode}/price` | PUBLIC |
| GET | `/bonds/{bondCode}/orderbook` | PUBLIC |
| GET | `/bonds/balance` | AUTH |
| GET | `/bonds/orderable?bondCode=&price=` | AUTH |
| POST | `/bonds/buy` | AUTH |
| POST | `/bonds/sell` | AUTH |
| GET | `/bonds/history` | AUTH |

- [ ] **Step 2: `SecurityConfig` permitAll 추가**

`SecurityConfig.java:45` 부근의 permitAll 목록에 공개 채권 조회 경로를 추가한다. **`/bonds/**` 전체를 열지 않는다** — 주문·잔고가 함께 열린다. 개별 경로(`/bonds/search`, `/bonds/*/issue-info`, `/bonds/*/price`, `/bonds/*/orderbook`)만 허용한다.

- [ ] **Step 3: 인증 경계 테스트**

```java
@Test
@DisplayName("채권 주문·잔고 엔드포인트는 인증 없이 접근할 수 없다")
void bondTradingEndpointsRequireAuth() throws Exception {
    mockMvc.perform(get("/bonds/balance")).andExpect(status().is4xxClientError());
    mockMvc.perform(post("/bonds/buy").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().is4xxClientError());
}

@Test
@DisplayName("채권 시세 조회는 공개다")
void bondQuoteEndpointsArePublic() throws Exception {
    mockMvc.perform(get("/bonds/KR1234567890/price")).andExpect(status().isOk());
}
```

- [ ] **Step 4: 전체 테스트 실행**

```bash
cd api-server && JWT_SECRET=dummy-secret-for-local-development-only-32bytes JASYPT_PASSWORD=dummy ./gradlew build test
```
전부 통과해야 한다(기존 500건 + 신규분). 회귀 0건 확인.

- [ ] **Step 5: 커밋** — `feat(api-server): expose bond REST endpoints`

- [ ] **Step 6: frontend-engineer에게 API 계약 통보**

엔드포인트 표 + 각 응답 DTO의 필드명(camelCase 직렬화)을 `_workspace/bond_api_contract_final.md`에 정리해 공유한다. **프론트는 이 문서를 계약으로 삼는다.**

---

## Task 6 (frontend-engineer): `bondApi` + 채권 검색 화면

**Files:**
- Modify: `web-app/src/services/api.js` (`bondApi` 추가)
- Create: `web-app/src/views/detail/BondSearchView.vue`
- Modify: `web-app/src/router/index.js` (`/bonds/search` 라우트)

**Interfaces:**
- Consumes: Task 5의 `_workspace/bond_api_contract_final.md`.
- Produces: `bondApi.search(keyword)`, `bondApi.getIssueInfo(code)`, `bondApi.getPrice(code)`, `bondApi.getOrderbook(code)`, `bondApi.getBalance()`, `bondApi.getOrderable(code, price)`, `bondApi.buy(payload)`, `bondApi.sell(payload)`, `bondApi.getHistory()`.

- [ ] **Step 1: `bondApi` 추가**

`services/api.js`의 기존 12개 객체 옆에 추가한다. **`api.js` 경유 원칙을 지킨다**(뷰에서 axios 직접 호출 금지).

- [ ] **Step 2: `BondSearchView.vue` 작성**

`SearchView.vue`의 구조를 따른다 — 검색어 입력, 결과 리스트, 항목 클릭 시 `/bonds/:code`로 이동. `utils/kisStatus.js`의 `isKisOutageError`와 `KisMaintenanceNotice`로 KIS 장애를 "결과 없음"과 구분해 표시한다.

- [ ] **Step 3: 라우트 추가 후 lint/build**

```bash
cd web-app && npm run lint && npm run build
```

- [ ] **Step 4: 커밋** — `feat(web-app): add bond search screen`

---

## Task 7 (frontend-engineer): 채권 상세(시세·발행정보·호가) 화면

**Files:**
- Create: `web-app/src/views/detail/BondDetailView.vue`
- Modify: `web-app/src/router/index.js` (`/bonds/:code`)

**Interfaces:**
- Consumes: Task 6의 `bondApi.getPrice/getIssueInfo/getOrderbook`.

- [ ] **Step 1: 화면 작성**

세 API를 병렬 호출(`Promise.allSettled` — 하나가 실패해도 나머지를 보여준다)해 렌더한다. 표시 항목: 현재가(소수점 그대로), 발행정보(만기일·표면금리·신용등급), 호가.

**단가 표시 규칙**: 채권 단가는 액면 10,000원 기준이므로 원화 정수 포맷터(`toLocaleString()`)를 그대로 쓰면 소수점이 잘린다. 소수 2~4자리를 유지하는 전용 포맷 함수를 이 화면에 둔다.

호가가 비면(유동성 부족) "현재 호가가 없습니다" 안내를 띄운다 — 에러가 아니다.

- [ ] **Step 2: lint/build → 커밋** — `feat(web-app): add bond detail screen`

---

## Task 8 (frontend-engineer): 채권 매매 화면 + 탭 활성화

**Files:**
- Create: `web-app/src/views/detail/BondTradingView.vue`
- Modify: `web-app/src/router/index.js` (`/bonds/:code/trade`)
- Modify: `web-app/src/components/common/AssetTabs.vue` (`bonds` disabled 해제)
- Modify: `web-app/src/components/common/InvestmentTabs.vue` (`bonds` disabled 해제)
- Modify: `web-app/src/views/main/AssetsView.vue` (채권 잔고 표시)
- Modify: `web-app/src/utils/uiSettings.js` ("채권 (추후 지원)" → "채권")

**Interfaces:**
- Consumes: Task 6의 `bondApi.buy/sell/getOrderable/getBalance`.

- [ ] **Step 1: 매매 화면 작성**

- 단가 입력은 **소수점 허용**(`type="text"` + 숫자·소수점 검증. `type="number"`의 브라우저별 소수 처리 차이를 피한다).
- 금액 미리보기: `수량 × 단가 / 100`(액면 100원 기준 환산). 이 계산식은 Task 0 Step 3에서 확정한 수량 단위에 따라 조정한다.
- 주문 전 확인 다이얼로그 — **실제 자금이 움직이므로** 종목명·수량·단가·예상금액을 보여주고 확인받는다.
- 주문 실패 시 서버 메시지를 그대로 노출한다(graceful degrade 대상 아님).

- [ ] **Step 2: 탭 활성화**

`AssetTabs.vue`·`InvestmentTabs.vue`의 `{ key: 'bonds', label: '채권', disabled: true }` → `disabled: false`.
`uiSettings.js`의 `{ key: 'bonds', label: '채권 (추후 지원)', icon: '📜' }` → `label: '채권'`.

- [ ] **Step 3: `AssetsView`에 채권 잔고 연동**

주식 잔고와 같은 자리에 `bondApi.getBalance()` 결과를 표시한다.

- [ ] **Step 4: lint/build + 브라우저 확인**

```bash
cd web-app && npm run lint && npm run build && npm run dev
```
브라우저에서 채권 탭이 활성화됐는지, 검색→상세→매매 폼 렌더까지 되는지 확인한다(api-server 미기동이면 `ERR_CONNECTION_REFUSED`만 나야 하고 JS 런타임 에러는 0건이어야 한다). **매매 폼에서 실제 주문 버튼은 누르지 않는다.**

- [ ] **Step 5: 커밋** — `feat(web-app): add bond trading screen and enable bond tab`

---

## Task 9 (팀리드): 문서 갱신

**Files:**
- Modify: `api-server/_docs/KIS_API_GUIDE.md` (채권 TR_ID 섹션 추가)
- Modify: `api-server/_docs/API_DESIGN.md` (엔드포인트 9종)
- Modify: `api-server/_docs/STATUS.md`
- Modify: `web-app/_docs/STATUS.md`, `web-app/_docs/SCREENS.md`
- Modify: `_docs/STATUS.md` (기능 현황표에 채권 행)
- Modify: `database/README.md` (`bond_trade_history`)
- Modify: `database/schema.sql` (`./database/generate-schema.sh` 재생성)

- [ ] **Step 1: 문서 갱신 후 커밋** — `docs: document bond trading feature`

---

## Task 10 (QA팀): 채권 기능 검증

별도 QA 라운드로 진행한다. **개발 담당과 다른 에이전트**가 맡는다(자기 코드를 자기가 검증하지 않는다).

- [ ] **Step 1: 코드 리뷰** — 스펙 대비 누락, Global Constraints 위반(특히 `BigDecimal` 대신 `double` 사용, 6자리 종목코드 가정), 계획 범위 밖 변경 여부.
- [ ] **Step 2: 경계면 검증** — api-server DTO 필드명 ↔ web-app 소비 코드 대조(2026-08 QA에서 실시간 DTO 케이싱 드리프트가 실제로 발생했던 유형).
- [ ] **Step 3: 전체 테스트 재실행** — `./gradlew build test redisTest kafkaTest timescaledbTest`(회귀 0건 확인) + `npm run lint && npm run build`.
- [ ] **Step 4: DB 확인** — `bond_trade_history` 스키마·FK·인덱스, changeset 적용 상태.
- [ ] **Step 5: 소수점 정밀도 검증** — 단가 `9850.5`가 UI 입력 → API → DB → 조회까지 왕복하며 값이 보존되는지 실제로 확인.
- [ ] **Step 6: 결과 보고** — `_workspace/qa_bond_trading.md`.

---

## Self-Review 메모 (계획 작성자용, 실행 시 무시)

- 스펙의 5개 범위 섹션(DB / api-server / web-app / 에러처리 / 테스트) 전부 Task 1~8에 매핑됨. 비범위 항목(장외채권·정정취소·실시간 WS·AI 연동)은 어느 Task에도 없음 — 의도적.
- Task 0을 둔 이유: 스펙의 DTO 설계가 공개 예제 기반 추정이라, 실제 응답 필드명이 다를 수 있다는 리스크를 구현 전에 해소한다.
- Task 2 Step 5의 필드명(`bond_prpr`)은 예시임을 명시하고 Task 0 결과가 우선함을 적어뒀다.
- 타입 일관성: `unitPrice`/`orderUnitPrice`/`BOND_ORD_UNPR`이 전부 `BigDecimal` 계열로 연결됨. `bondCode`는 전 구간 `String`.

# 업비트 코인 거래 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비활성 상태인 코인 탭을 업비트 Open API 기반의 실거래 기능(원화마켓 검색·시세·호가·자산·매수·매도)으로 만든다.

**Architecture:** 업비트는 KIS와 인증 방식이 완전히 달라(요청마다 JWT 생성 vs OAuth 토큰 캐시) `KisApiClient`를 재사용할 수 없다. 신규 `UpbitApiClient`를 만들되, 사용자별 키를 Jasypt로 암호화 저장하는 패턴은 `user_kis_accounts`를 그대로 따른다. 업비트 주문 타입의 비대칭성(시장가 매수=총액, 시장가 매도=수량)은 서비스 계층에서 캡슐화해 프론트가 알 필요 없게 한다.

**Tech Stack:** Spring Boot 4.1 / Java 21 / jjwt 0.12.3(이미 의존성에 있음) / Jasypt / Liquibase / JUnit 5 + Mockito, Vue 3 + Vite.

**참조 스펙:** `docs/superpowers/specs/2026-08-28-upbit-coin-trading-design.md`

**선행 조건:** 채권 계획(`2026-08-28-domestic-bond-trading.md`) 완료 후 착수한다.

## Global Constraints

- **코인 수량은 소수다**(0.00012345 BTC). 수량·가격은 전 구간 `BigDecimal`(Java) / `NUMERIC`(DB) / 문자열(JS)로 다룬다. `double`/`float` 금지 — 정밀도가 곧 자산 금액이다.
- **업비트 주문 식별자는 UUID 문자열**이다. KIS의 숫자 주문번호 가정을 재사용하지 않는다.
- **마켓 코드는 `KRW-BTC` 형식**(통화-심볼). 6자리 종목코드 가정 금지.
- **API 키는 Jasypt(`PBEWITHHMACSHA512ANDAES_256`)로 암호화 저장**하고, 복호화 실패 시 **평문 폴백 금지**(fail-closed — `KisAuthService:164-176` 패턴).
- 원화(KRW) 마켓만 다룬다. BTC/USDT 마켓 제외.
- 조회 경로는 graceful degrade, 주문 경로는 예외 전파.
- Liquibase는 새 changeset만 추가. 채권 기능은 신규 테이블이 없어 changeset을 추가하지 않았다. 현재 최신은 `v1.28`이므로 이 계획은 `v1.29`부터.
- 이 계획은 **ai-agent를 건드리지 않는다.**

---

## Task 0: 사전 검토 — **완료됨**

`_workspace/preflight_coin.md`, `_workspace/upbit_api_contract.md` 참조. 이 계획은 그 결과를 반영해 개정된 판이다. **다시 수행하지 않는다.**

확정된 것: JWT 규격(`query_hash` 포함 규칙, POST 바디는 쿼리스트링 변환본 해싱), 주문 파라미터 3종(계획서 표가 정확했음), 시세 응답 필드 실측, rate limit 실측(시세 IP당 10/s, 주문 Pocket당 12/s).

**미해소로 남은 항목** (실제 키 필요):
- HS256 수용 여부 → Task 3 Step 2-1에서 확인
- 업비트 `created_at`의 타임존 오프셋 유무 → Task 3에서 확인 후 `TIMESTAMPTZ` 정정 여부 결정
- 업비트 점검 시 응답 형태 → 재현 불가, non-JSON 정규화로 방어

**사용자 협조 필요 (실거래 검증 시)**: 업비트 API 키 발급에는 본인인증 + 2채널 인증 + **PC 웹 접속** + **서버 공인 IP 등록**이 필요하다. 권한은 **자산조회 + 주문만** 주고 **입출금 권한은 주지 않는다.** IP가 바뀌면 전 요청이 실패하므로 유동 IP 환경이면 재등록이 반복된다. 검증은 업비트 KRW 최소 주문금액(5,000원)으로 한다.

---

## Task 1 (backend-engineer): DB — 업비트 계좌 + 코인 거래이력

**Files:**
- Create: `api-server/src/main/resources/db/changelog/mvp/v1.29-upbit-account-and-coin-history.yaml`
- Modify: `api-server/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `api-server/src/main/java/com/inbeom/apiserver/domain/UserUpbitAccount.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/domain/CoinTradeHistory.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/repository/UserUpbitAccountRepository.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/repository/CoinTradeHistoryRepository.java`

**Interfaces:**
- Produces: `UserUpbitAccount`(user 1:1, `accessKey`/`secretKey` 암호화 저장), `CoinTradeHistory`, 두 리포지토리. Task 3~5가 사용한다.

- [ ] **Step 1: changeset 작성**

```yaml
databaseChangeLog:
  - changeSet:
      id: 1.29.1-create-user-upbit-accounts
      author: inbeom
      context: mvp
      changes:
        - createTable:
            tableName: user_upbit_accounts
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: user_id, type: BIGINT, constraints: { nullable: false, unique: true } }
              - column: { name: access_key, type: VARCHAR(255), constraints: { nullable: false } }
              - column: { name: secret_key, type: VARCHAR(255), constraints: { nullable: false } }
              - column: { name: is_verified, type: BOOLEAN, defaultValueBoolean: false, constraints: { nullable: false } }
              - column: { name: created_at, type: TIMESTAMP, defaultValueComputed: now(), constraints: { nullable: false } }
              - column: { name: updated_at, type: TIMESTAMP, defaultValueComputed: now(), constraints: { nullable: false } }
        - addForeignKeyConstraint:
            constraintName: fk_user_upbit_accounts_user
            baseTableName: user_upbit_accounts
            baseColumnNames: user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: CASCADE
      rollback:
        - dropTable: { tableName: user_upbit_accounts }

  - changeSet:
      id: 1.29.2-create-coin-trade-history
      author: inbeom
      context: mvp
      changes:
        - createTable:
            tableName: coin_trade_history
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: user_id, type: BIGINT, constraints: { nullable: false } }
              - column: { name: market, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: coin_name, type: VARCHAR(50) }
              - column: { name: order_side, type: VARCHAR(10), constraints: { nullable: false } }
              - column: { name: ord_type, type: VARCHAR(10), constraints: { nullable: false } }
              - column: { name: submitted_state, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: volume, type: "NUMERIC(30,8)" }
              - column: { name: price, type: "NUMERIC(30,8)" }
              - column: { name: executed_volume, type: "NUMERIC(30,8)" }
              - column: { name: paid_fee, type: "NUMERIC(30,8)" }
              - column: { name: order_uuid, type: VARCHAR(64), constraints: { nullable: false, unique: true } }
              - column: { name: identifier, type: VARCHAR(64), constraints: { unique: true } }
              - column: { name: ordered_at, type: TIMESTAMP, constraints: { nullable: false } }
              - column: { name: created_at, type: TIMESTAMP, defaultValueComputed: now(), constraints: { nullable: false } }
        - addForeignKeyConstraint:
            constraintName: fk_coin_trade_history_user
            baseTableName: coin_trade_history
            baseColumnNames: user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: CASCADE
        - createIndex:
            indexName: idx_coin_trade_history_user_ordered
            tableName: coin_trade_history
            columns:
              - column: { name: user_id }
              - column: { name: ordered_at }
      rollback:
        - dropTable: { tableName: coin_trade_history }
```

> `volume`/`price`가 `NUMERIC(30,8)`인 이유: 업비트는 소수 8자리까지 쓴다(사토시 단위). `NUMERIC(15,2)` 같은 주식용 정밀도로는 값이 잘린다.

**컬럼 설계 주의 (사전 검토 반영):**

- **`submitted_state`** — `order_state`가 아니다. 주문 응답의 `state`는 접수 직후 값이라 대개 `wait`이고, 주문 조회 API가 범위 밖이라 **영원히 갱신되지 않는다.** 이름을 정직하게 두고 UI에도 "접수 상태"로 표기한다 — "체결 안 됨"으로 오인하면 사용자가 중복 주문을 낸다.
- **`identifier`** — 업비트 `POST /v1/orders`가 지원하는 클라이언트 지정 식별자(최대 64자). 타임아웃 후 재시도 시 중복 주문을 막는다. 이 프로젝트는 KIS에서 같은 문제를 겪고 `v1.25`로 멱등키를 도입한 이력이 있다.
- **`ordered_at` 타임존** — Task 3에서 실제 키로 첫 호출할 때 업비트 `created_at` 원문에 오프셋이 붙는지 확인한다. 붙어 온다면 `TIMESTAMP`(타임존 없음) 매핑에서 **9시간 오차가 조용히 생기므로 `TIMESTAMPTZ`로 정정**한다. 실제 키 없이는 검증 불가한 항목이다.

- [ ] **Step 2: 엔티티·리포지토리 작성**

`UserKisAccount.java`의 Lombok 패턴을 따른다. `UserUpbitAccountRepository`에 `findByUserId(Long userId)`, `CoinTradeHistoryRepository`에 `findByUserIdOrderByOrderedAtDesc(Long userId)`.

- [ ] **Step 3: 서버 기동으로 적용 확인 + psql 검증 → 커밋** — `feat(db): add upbit account and coin trade history tables`

---

## Task 2 (backend-engineer): 업비트 공개 시세 조회

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/client/UpbitApiClient.java` (공개 GET만 우선)
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/CoinQuoteService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinMarketResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinTickerResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinOrderbookResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinCandleResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/exception/UpbitApiException.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/CoinQuoteServiceTest.java`

**Interfaces:**
- Produces:
  - `UpbitApiClient.BASE_URL = "https://api.upbit.com"`
  - `UpbitApiClient.<T> ResponseEntity<T> getPublic(String path, Map<String,String> queryParams, Class<T> responseType)`
  - `CoinQuoteService.getKrwMarkets() -> List<CoinMarketResponse>` (유의종목 플래그 포함)
  - `CoinQuoteService.getTickers(List<String> markets) -> List<CoinTickerResponse>` — **배치 조회.** 단건 조회 메서드를 따로 두지 않는다(Task 8의 자산 평가에서 N+1을 방지하기 위해 배치가 기본형이어야 한다)
  - `CoinQuoteService.getOrderbook(String market) -> CoinOrderbookResponse`
  - `CoinQuoteService.getCandles(String market, String unit, int count) -> List<CoinCandleResponse>`

- [ ] **Step 1: 원화마켓 필터링 테스트 먼저 작성**

```java
@Test
@DisplayName("원화(KRW) 마켓만 반환한다 — BTC/USDT 마켓은 제외")
void returnsOnlyKrwMarkets() {
    List<Map<String, Object>> body = List.of(
            Map.of("market", "KRW-BTC", "korean_name", "비트코인", "english_name", "Bitcoin"),
            Map.of("market", "BTC-ETH", "korean_name", "이더리움", "english_name", "Ethereum"),
            Map.of("market", "USDT-BTC", "korean_name", "비트코인", "english_name", "Bitcoin")
    );
    given(upbitApiClient.getPublic(eq("/v1/market/all"), any(), any()))
            .willReturn(new ResponseEntity<>(body.toArray(), HttpStatus.OK));

    List<CoinMarketResponse> result = coinQuoteService.getKrwMarkets();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getMarket()).isEqualTo("KRW-BTC");
}

@Test
@DisplayName("업비트 실패 시 예외 대신 notice가 담긴 빈 결과를 준다")
void upbitFailure_degradesGracefully() {
    given(upbitApiClient.getPublic(anyString(), any(), any()))
            .willThrow(new UpbitApiException("Upbit down"));

    CoinTickerResponse result = coinQuoteService.getTicker("KRW-BTC");

    assertThat(result.getNotice()).isNotBlank();
    assertThat(result.getTradePrice()).isNull();
}
```

- [ ] **Step 2: 실패 확인 → 구현 → 통과 확인**

`UpbitApiClient`는 `RestTemplate` 기반으로 만들고, 타임아웃은 `KisApiClient`와 같은 수준(connect 5s, read 18s)으로 둔다.

**실패 정규화 (사전 검토 반영)**:
- 실패는 전부 `UpbitApiException`으로 정규화한다.
- **`error.name`의 타입이 섞인다** — 정상 에러는 String(`"no_authorization_token"`)인데 404는 Integer(`404`)로 온다. `String name`으로 DTO를 고정하면 역직렬화가 깨지므로 `Object`/`JsonNode`로 받는다.
- **서버 점검 시 HTML이나 5xx가 올 수 있다.** non-JSON 응답과 5xx도 `UpbitApiException`으로 흘려보낸다 — 파싱 예외가 그대로 터지면 graceful degrade가 무너진다.

**마켓 목록은 `isDetails=true`로 조회한다** — `market_event.warning`(유의종목)과 `caution` 5종 플래그가 함께 온다. 실제 자금이 오가는 화면이므로 이 정보를 버리지 않는다(비용은 파라미터 한 글자).

**DTO 필드는 Task 0 Step 3에서 실측한 응답을 기준으로 만든다.** 가격·수량 필드는 `BigDecimal`.

> 실측에서 확인된 함정: `ticker`의 `change_price`는 **절대값**이고 부호 있는 값은 `signed_change_price`다. 반면 `candles`의 `change_price`는 **부호가 있다** — 같은 이름, 다른 의미다.

Run: `cd api-server && ./gradlew test --tests "*CoinQuoteServiceTest*"`

- [ ] **Step 3: rate limit — 기존 `KisRateLimiter` 재사용**

`client/KisRateLimiter.java`에 Redis Lua 기반 원자적 토큰 버킷이 **이미 있다.** 그 설계가 업비트 상황과 그대로 겹친다 — 버킷 키를 자격증명에서 유도하고, 공유 시세 키와 사용자별 매매 키의 버킷을 분리하며, Redis 장애 시 fail-open 한다.

**새로 만들지 말고 일반화하거나 같은 Lua 스크립트를 재사용한다.** 버킷 2종:
- **시세**: 고정 IP 버킷 (업비트 한도 10/s, IP 단위)
- **주문**: access_key 해시 버킷 (업비트 한도 12/s, Pocket 단위) — Task 4에서 사용

> 위험한 쪽은 주문이 아니라 시세다. 시세는 IP 단위라 **전체 사용자가 서버 공인 IP 하나의 10/s를 공유**한다.

- [ ] **Step 4: 커밋** — `feat(api-server): add upbit public market data lookup`

---

## Task 3 (backend-engineer): 업비트 JWT 인증 + 계좌 등록

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/UpbitApiClient.java` (인증 요청 추가)
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/UpbitAuthService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/user/UpbitAccountResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/user/UpdateUpbitAccountRequest.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/controller/UserController.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/UserService.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/client/UpbitJwtTest.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/UpbitAuthServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `UserUpbitAccountRepository`, 기존 `StringEncryptor`(Jasypt 빈).
- Produces:
  - `UpbitApiClient.buildJwt(String accessKey, String secretKey, Map<String,String> params) -> String`
  - `UpbitApiClient.<T> ResponseEntity<T> getAuthenticated(...)` / `postAuthenticated(...)`
  - `UpbitAuthService.getCredentials(Long userId) -> UpbitCredentials(accessKey, secretKey)`
  - `UserService.getUpbitAccount(Long userId)` / `updateUpbitAccount(Long userId, UpdateUpbitAccountRequest)`

> **이 Task가 이 계획에서 가장 위험하다.** JWT 서명이나 `query_hash`가 틀리면 이후 모든 인증 요청이 401이 되고, 원인이 응답만으로는 드러나지 않는다.

- [ ] **Step 1: JWT 생성 단위 테스트 먼저 작성**

> **테스트 secret은 반드시 32바이트 이상이어야 한다.** jjwt가 RFC 7518 최소 키 길이를 강제하므로 짧은 문자열을 쓰면 구현이 맞아도 `WeakKeyException`으로 테스트가 실행조차 안 된다(사전 검토에서 실측 확인). 실제 업비트 키와 같은 40자를 쓴다:
> ```java
> private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef01234567"; // 40 bytes = 320 bits
> ```
> 부수 효과로 이 테스트는 "업비트 실제 키 길이에서 서명이 되는가"까지 검증하게 된다.

```java
@Test
@DisplayName("파라미터 없는 요청의 JWT는 access_key와 nonce만 갖는다")
void jwtWithoutParams_hasNoQueryHash() {
    String token = UpbitApiClient.buildJwt("test-access-key", TEST_SECRET, Map.of());

    Claims claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
            .build().parseSignedClaims(token).getPayload();

    assertThat(claims.get("access_key")).isEqualTo("test-access-key");
    assertThat(claims.get("nonce")).isNotNull();
    assertThat(claims.get("query_hash")).isNull();
    assertThat(claims.get("query_hash_alg")).isNull();
}

@Test
@DisplayName("파라미터 있는 요청의 JWT는 쿼리스트링의 SHA512 해시를 담는다")
void jwtWithParams_hasSha512QueryHash() throws Exception {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("market", "KRW-BTC");
    params.put("side", "bid");

    String token = UpbitApiClient.buildJwt("test-access-key", TEST_SECRET, params);

    Claims claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
            .build().parseSignedClaims(token).getPayload();

    String queryString = "market=KRW-BTC&side=bid";
    MessageDigest md = MessageDigest.getInstance("SHA-512");
    md.update(queryString.getBytes(StandardCharsets.UTF_8));
    String expected = HexFormat.of().formatHex(md.digest());

    assertThat(claims.get("query_hash")).isEqualTo(expected);
    assertThat(claims.get("query_hash_alg")).isEqualTo("SHA512");
}

@Test
@DisplayName("nonce는 매 호출마다 달라진다")
void nonceIsUniquePerCall() {
    String a = UpbitApiClient.buildJwt("test-access-key", TEST_SECRET, Map.of());
    String b = UpbitApiClient.buildJwt("test-access-key", TEST_SECRET, Map.of());
    assertThat(a).isNotEqualTo(b);
}

@Test
@DisplayName("POST 바디는 JSON이 아니라 쿼리스트링 변환본을 해싱한다")
void postBodyHashesQueryStringFormNotJson() throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("market", "KRW-BTC");
    body.put("side", "bid");
    body.put("ord_type", "price");
    body.put("price", "100000");

    String token = UpbitApiClient.buildJwt("test-access-key", TEST_SECRET, body);

    Claims claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
            .build().parseSignedClaims(token).getPayload();

    // 쿼리스트링 형태로 변환한 뒤 해싱해야 한다 — JSON 문자열을 해싱하면 안 된다.
    String queryStringForm = "market=KRW-BTC&side=bid&ord_type=price&price=100000";
    MessageDigest md = MessageDigest.getInstance("SHA-512");
    md.update(queryStringForm.getBytes(StandardCharsets.UTF_8));
    String expected = HexFormat.of().formatHex(md.digest());

    assertThat(claims.get("query_hash")).isEqualTo(expected);
}
```

> **이 마지막 테스트가 이 계획에서 가장 중요하다.** POST 바디를 `objectMapper.writeValueAsString(body)`로 해싱하면 **조회는 전부 정상인데 주문만 401**이 되어 원인 파악에 시간을 크게 잃는다.

> Task 0 Step 1에서 확인한 규격과 위 테스트가 어긋나면 **Task 0의 실측 결과를 따르고 이 테스트를 고친다.**

- [ ] **Step 2: 실패 확인 → `buildJwt` 구현 → 통과 확인**

`jjwt 0.12.3`(`build.gradle:47-49`에 이미 있음)을 쓴다. 구현 시 반드시 지킬 것:

- **알고리즘은 HS256.** 업비트 문서는 HS512를 권장하지만 **jjwt로는 구현 불가능하다** — 업비트 Secret Key가 40자(320비트)인데 jjwt가 RFC 7518의 HS512 최소 512비트를 강제해 `SignatureException`이 난다(사전 검토 실측). 업비트 공식 Java 예제들도 HMAC256을 쓴다.
- **Secret Key는 Base64가 아니다.** 디코딩 없이 UTF-8 raw bytes를 그대로 서명 키로 쓴다.
- 쿼리스트링은 **삽입 순서 유지, 정렬하지 않음** → `LinkedHashMap`으로 받는다.
- **URL 인코딩하지 않은 원문**을 해싱한다.
- 파라미터가 없으면 `query_hash`/`query_hash_alg`를 **넣지 않는다**.

- [ ] **Step 2-1: 실제 키로 HS256 수용 여부 1회 확인 (사용자 협조)**

**구현 직후, Task 4로 넘어가기 전에** 실제 업비트 키로 `GET /v1/accounts`를 1회 호출해 **HS256이 수용되는지 확인한다.** 조회 전용이라 자금이 움직이지 않는다.

거부되면 jjwt를 버리고 `javax.crypto.Mac("HmacSHA512")` + 수동 base64url 인코딩으로 직접 구현해야 한다. **이를 Task 5에서 발견하면 Task 3~5를 되돌려야 하므로 반드시 여기서 확인한다.**

키가 아직 없으면 이 Step을 건너뛰되, **"HS256 미검증" 상태임을 팀리드에게 보고**하고 Task 10 QA의 확인 항목으로 넘긴다.

- [ ] **Step 3: Jasypt fail-closed 테스트 작성 → 구현**

```java
@Test
@DisplayName("복호화 실패 시 평문 폴백 없이 예외를 던진다")
void decryptionFailure_failsClosedWithoutPlaintextFallback() {
    UserUpbitAccount account = UserUpbitAccount.builder()
            .accessKey("NOT-ENCRYPTED").secretKey("NOT-ENCRYPTED").build();
    given(userUpbitAccountRepository.findByUserId(1L)).willReturn(Optional.of(account));
    given(stringEncryptor.decrypt(anyString())).willThrow(new EncryptionOperationNotPossibleException());

    assertThatThrownBy(() -> upbitAuthService.getCredentials(1L))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 4: 계좌 등록 엔드포인트 추가**

`UserController`에 `GET /users/upbit-account`, `PUT /users/upbit-account`를 추가한다. `UserService`는 저장 시 Jasypt로 암호화한다.

**조회 시 `secretKey`를 절대 복호화하지 않는다.** 마스킹된 형태(`"****"` 또는 등록 여부 boolean)만 반환하고, `accessKey`는 식별용이므로 앞 4자만 노출한다.

> **`KisAccountResponse`/`decryptForDisplay` 패턴을 복사하지 마라.** 사전 검토에서 확인한 결과 `UserService.java:293`의 `decryptForDisplay()`는 **복호화된 평문을 그대로 응답에 싣는다.** 이는 실수가 아니라 의도된 것으로(`UserService.java:302-307` 주석), 암호화 도입 이전의 평문 레코드 때문에 프로필 화면이 500으로 죽으면 사용자가 키를 재등록할 방법조차 없어지는 상황을 피하려는 트레이드오프다. **`user_upbit_accounts`는 신규 테이블이라 레거시 평문 레코드가 존재하지 않으므로 이 트레이드오프를 물려받을 이유가 없다.** 그대로 따라 하면 Secret Key 평문이 API 응답으로 나가고, 이 계획 자신의 Task 10 Step 5를 자동으로 실패시킨다.

**PUT 요청의 빈 값 처리 규칙**: 조회 시 실제 키를 돌려주지 않으므로 프론트는 저장된 값을 되채울 수 없다. 따라서 **`secretKey`가 빈 값이면 기존 값을 유지**하고, 값이 있을 때만 교체한다. `accessKey`도 동일. 이 규칙을 테스트로 고정한다:

```java
@Test
@DisplayName("secretKey가 빈 값이면 기존 키를 유지한다")
void blankSecretKey_keepsExistingKey() {
    // 기존 계정 stub 후 secretKey="" 로 PUT
    // → 저장된 엔티티의 secretKey가 이전 값 그대로인지 확인
}
```

- [ ] **Step 5: 전체 테스트 통과 확인 → 커밋** — `feat(api-server): add upbit JWT auth and account registration`

---

## Task 4 (backend-engineer): 코인 자산조회 + 주문(지정가/시장가)

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/CoinTradingService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinAccountResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinOrderRequest.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinOrderResponse.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/CoinTradingServiceTest.java`

**Interfaces:**
- Consumes: Task 3의 `UpbitAuthService.getCredentials`, `UpbitApiClient.getAuthenticated/postAuthenticated`, Task 1의 `CoinTradeHistoryRepository`.
- Produces:
  - `CoinOrderRequest`: `market`(String), `side`(enum BUY/SELL), `orderType`(enum LIMIT/MARKET), `quantity`(BigDecimal, nullable), `price`(BigDecimal, nullable)
  - `CoinTradingService.getAccounts(Long userId) -> List<CoinAccountResponse>`
  - `CoinTradingService.placeOrder(Long userId, CoinOrderRequest) -> CoinOrderResponse`

> **이 Task의 핵심은 주문 타입 매핑이다.** 업비트는 시장가 매수에 총액을, 시장가 매도에 수량을 받는 비대칭 구조라 실수하기 쉽다.

- [ ] **Step 1: 주문 매핑 3종 테스트를 먼저 작성**

```java
@Test
@DisplayName("지정가 매수 → ord_type=limit, volume+price 전송")
void limitBuy_sendsLimitWithVolumeAndPrice() {
    coinTradingService.placeOrder(1L, new CoinOrderRequest(
            "KRW-BTC", Side.BUY, OrderType.LIMIT,
            new BigDecimal("0.001"), new BigDecimal("50000000")));

    Map<String, String> sent = captureOrderParams();
    assertThat(sent.get("side")).isEqualTo("bid");
    assertThat(sent.get("ord_type")).isEqualTo("limit");
    assertThat(sent.get("volume")).isEqualTo("0.001");
    assertThat(sent.get("price")).isEqualTo("50000000");
}

@Test
@DisplayName("시장가 매수 → ord_type=price, price에 총액만 전송(volume 없음)")
void marketBuy_sendsPriceTypeWithTotalAmountOnly() {
    coinTradingService.placeOrder(1L, new CoinOrderRequest(
            "KRW-BTC", Side.BUY, OrderType.MARKET,
            null, new BigDecimal("100000")));

    Map<String, String> sent = captureOrderParams();
    assertThat(sent.get("side")).isEqualTo("bid");
    assertThat(sent.get("ord_type")).isEqualTo("price");
    assertThat(sent.get("price")).isEqualTo("100000");
    assertThat(sent).doesNotContainKey("volume");
}

@Test
@DisplayName("시장가 매도 → ord_type=market, volume에 수량만 전송(price 없음)")
void marketSell_sendsMarketTypeWithVolumeOnly() {
    coinTradingService.placeOrder(1L, new CoinOrderRequest(
            "KRW-BTC", Side.SELL, OrderType.MARKET,
            new BigDecimal("0.001"), null));

    Map<String, String> sent = captureOrderParams();
    assertThat(sent.get("side")).isEqualTo("ask");
    assertThat(sent.get("ord_type")).isEqualTo("market");
    assertThat(sent.get("volume")).isEqualTo("0.001");
    assertThat(sent).doesNotContainKey("price");
}

@Test
@DisplayName("소수 8자리 수량이 지수표기 없이 그대로 전송된다")
void smallVolumeIsNotSerializedAsScientificNotation() {
    coinTradingService.placeOrder(1L, new CoinOrderRequest(
            "KRW-BTC", Side.SELL, OrderType.MARKET,
            new BigDecimal("0.00000123"), null));

    assertThat(captureOrderParams().get("volume")).isEqualTo("0.00000123");
}

@Test
@DisplayName("업비트 계좌 미등록 사용자는 명확한 예외를 받는다")
void noUpbitAccount_throwsBusinessException() {
    given(upbitAuthService.getCredentials(1L))
            .willThrow(new BusinessException(ErrorCode.UPBIT_ACCOUNT_NOT_FOUND));

    assertThatThrownBy(() -> coinTradingService.placeOrder(1L, validRequest()))
            .isInstanceOf(BusinessException.class);
}

@Test
@DisplayName("주문 성공 시 coin_trade_history에 UUID와 함께 기록한다")
void successfulOrder_recordsHistoryWithUuid() {
    // 업비트 성공 응답(uuid 포함) stub
    coinTradingService.placeOrder(1L, validRequest());

    ArgumentCaptor<CoinTradeHistory> saved = ArgumentCaptor.forClass(CoinTradeHistory.class);
    verify(coinTradeHistoryRepository).save(saved.capture());
    assertThat(saved.getValue().getOrderUuid()).isNotBlank();
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd api-server && ./gradlew test --tests "*CoinTradingServiceTest*"`

- [ ] **Step 3: `ErrorCode`에 코인 대역 추가**

스펙 §4대로 6000번대를 신설한다: `UPBIT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, 6000, ...)`, `UPBIT_API_ERROR(HttpStatus.SERVICE_UNAVAILABLE, 6001, ...)`, `UPBIT_IP_NOT_ALLOWED(HttpStatus.FORBIDDEN, 6002, "서버 IP가 업비트에 등록되지 않았습니다")`.

- [ ] **Step 4: 구현 → 통과 확인**

수량·가격은 `BigDecimal.toPlainString()`으로 직렬화한다(`toString()`은 작은 값에서 지수표기를 낸다 — 위 테스트가 이를 잡는다).

업비트가 IP 미등록 에러를 반환하면 `UPBIT_IP_NOT_ALLOWED`로 변환한다(스펙 §4 — 일반 401로 뭉개지 않는다).

- [ ] **Step 5: 커밋** — `feat(api-server): add coin balance and order placement`

---

## Task 5 (backend-engineer): `CoinController` + Security + 거래이력

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/controller/CoinController.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/coin/CoinTradeHistoryResponse.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/config/SecurityConfig.java`

**Interfaces:**
- Produces: 스펙 §2의 엔드포인트. 프론트(Task 6~8)가 소비한다.

- [ ] **Step 1: 컨트롤러 작성**

| 메서드 | 경로 | 인증 |
|---|---|---|
| GET | `/coins/markets` | PUBLIC |
| GET | `/coins/tickers?markets=` | PUBLIC (배치 조회) |
| GET | `/coins/{market}/orderbook` | PUBLIC |
| GET | `/coins/{market}/candles?unit=&count=` | PUBLIC |
| GET | `/coins/accounts` | AUTH |
| POST | `/coins/buy` | AUTH |
| POST | `/coins/sell` | AUTH |
| GET | `/coins/history` | AUTH |

`OverseasController` 구조를 따른다.

> `unit` 파라미터를 업비트 경로로 매핑하는 규칙이 필요하다: `days`/`weeks`는 `/v1/candles/{unit}`, 분봉은 `/v1/candles/minutes/{1,3,5,10,15,30,60,240}`. 허용 값을 화이트리스트로 검증하고 그 외는 400으로 거절한다.

- [ ] **Step 2: `SecurityConfig`에 공개 경로만 추가**

`/coins/markets`, `/coins/tickers`, `/coins/*/orderbook`, `/coins/*/candles`만 permitAll. **`/coins/**` 전체를 열지 않는다**(자산·주문이 함께 열린다).

> `.anyRequest().authenticated()`가 마지막에 있어 명시하지 않은 경로는 자동으로 AUTH다. Spring Security의 `*`는 한 세그먼트만 매칭하고 마켓코드(`KRW-BTC`)에 `/`가 없으므로 `/coins/*/orderbook` 패턴이 정상 동작한다.

- [ ] **Step 3: 인증 경계 테스트 + 전체 테스트**

```java
@Test
@DisplayName("코인 자산·주문은 인증이 필요하다")
void coinTradingRequiresAuth() throws Exception {
    mockMvc.perform(get("/coins/accounts")).andExpect(status().is4xxClientError());
    mockMvc.perform(post("/coins/buy").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().is4xxClientError());
}
```

```bash
cd api-server && JWT_SECRET=dummy-secret-for-local-development-only-32bytes JASYPT_PASSWORD=dummy ./gradlew build test
```
회귀 0건 확인.

- [ ] **Step 4: 커밋 + 프론트에 계약 통보**

`_workspace/coin_api_contract_final.md`에 엔드포인트와 응답 필드명을 정리해 공유한다.

---

## Task 6 (frontend-engineer): `coinApi` + 업비트 계좌 등록 UI

**Files:**
- Modify: `web-app/src/services/api.js` (`coinApi` 추가, `userApi`에 upbit-account 메서드 추가)
- Modify: `web-app/src/views/settings/ProfileView.vue`

**Interfaces:**
- Produces: `coinApi.getMarkets()`, `getTicker(market)`, `getOrderbook(market)`, `getAccounts()`, `buy(payload)`, `sell(payload)`, `getHistory()`.

- [ ] **Step 1: `coinApi` 추가**

- [ ] **Step 2: `ProfileView`에 업비트 계좌 카드 추가**

기존 KIS 계좌 카드 **아래에 나란히** 추가한다(별도 화면 신설 아님 — 스펙 결정사항). 다음 안내 문구를 포함한다:
> 업비트 API 키는 PC 웹에서 본인인증·2채널 인증 후 발급할 수 있으며, 발급 시 이 서버의 IP를 허용 목록에 등록해야 합니다.

Secret Key 입력란은 `type="password"`로 두고, 저장된 키는 마스킹해서 보여준다.

- [ ] **Step 3: lint/build → 커밋** — `feat(web-app): add upbit account registration UI`

---

## Task 7 (frontend-engineer): 코인 검색 + 상세 화면

**Files:**
- Create: `web-app/src/views/detail/CoinSearchView.vue`
- Create: `web-app/src/views/detail/CoinDetailView.vue`
- Modify: `web-app/src/router/index.js`

- [ ] **Step 1: `CoinSearchView` — 원화마켓 전체 검색**

`coinApi.getMarkets()`로 전체 목록을 받아 **클라이언트에서 필터링**한다(마켓 목록은 200여 개로 작고 자주 안 바뀌므로 서버 왕복이 불필요). 한글명·영문명·심볼 모두 검색 대상.

- [ ] **Step 2: `CoinDetailView` — 시세·호가·캔들**

`Promise.allSettled`로 티커·호가를 병렬 조회. 캔들 차트는 기존 Chart.js를 재사용한다(`AssetsView`가 이미 쓰고 있다).

**가격 표시**: 코인은 자릿수 편차가 크다(BTC 5천만원대 ~ 알트코인 0.5원). 큰 값은 정수, 작은 값은 소수점을 유지하는 포맷 함수를 둔다.

- [ ] **Step 3: lint/build → 커밋** — `feat(web-app): add coin search and detail screens`

---

## Task 8 (frontend-engineer): 코인 매매 화면 + 탭 활성화

**Files:**
- Create: `web-app/src/views/detail/CoinTradingView.vue`
- Modify: `web-app/src/router/index.js`
- Modify: `web-app/src/components/common/AssetTabs.vue` (`coins` disabled 해제)
- Modify: `web-app/src/components/common/InvestmentTabs.vue` (`coins` disabled 해제)
- Modify: `web-app/src/views/main/AssetsView.vue`
- Modify: `web-app/src/utils/uiSettings.js` ("코인 (추후 지원)" → "코인")
- Modify: `web-app/src/views/detail/TransactionsView.vue`, `web-app/src/views/main/SearchView.vue`, `web-app/src/views/main/FavoritesView.vue`, `web-app/src/views/detail/AssetDetailView.vue`, `web-app/src/views/detail/NewsView.vue` (탭 공유 화면 — 코인 분기)

- [ ] **Step 1: 매매 화면 — 주문 타입에 따라 입력 필드가 바뀐다**

이 화면의 핵심 요구사항이다:

| 선택 | 입력 필드 | 라벨 |
|---|---|---|
| 지정가 매수/매도 | 수량 + 단가 | "주문 수량", "주문 단가" |
| **시장가 매수** | **총액만** | "주문 총액 (원)" |
| **시장가 매도** | **수량만** | "주문 수량" |

업비트 규칙을 UI가 정직하게 반영한다 — 사용자가 "시장가 매수인데 왜 수량을 못 넣지?"라고 헷갈리지 않도록 라벨과 안내를 명확히 쓴다.

- 수량 입력은 소수점 8자리까지 허용(`type="text"` + 검증).
- 업비트 계좌 미등록이면 매매 폼 대신 "설정에서 업비트 계좌를 등록해주세요" + 설정 화면 링크를 보여준다.
- 주문 전 확인 다이얼로그(실제 자금 이동).

- [ ] **Step 2: 탭 활성화 + 공유 화면 처리**

`AssetTabs.vue`·`InvestmentTabs.vue`의 `coins` → `disabled: false`, `uiSettings.js` 라벨 정리.

> **주의**: 이 두 탭 컴포넌트는 6개 화면이 공유한다. 코인 탭을 켜면 `AssetDetailView`(빈 화면), `NewsView`(빈 목록), 그리고 특히 **`TransactionsView`·`SearchView`·`FavoritesView`는 분기가 없어 주식 데이터를 코인 탭인 것처럼 그대로 보여준다**(조용한 오류). 채권 계획 Task 8이 같은 화면들을 이미 손봤다면 코인 분기만 추가하면 되고, 아니라면 여기서 함께 처리한다.

- [ ] **Step 3: `AssetsView` 코인 잔고 연동 — ticker 배치 조회 필수**

`GET /v1/accounts`는 보유 수량만 주므로 원화 환산 금액은 **수량 × 현재가**로 계산한다(KRW 마켓만 다루므로 환율 변환 불필요).

**보유 종목마다 ticker를 부르면 안 된다.** `/coins/tickers?markets=KRW-BTC,KRW-ETH,...`로 **1회 배치 호출**한다. 종목별 루프는 IP 10/s 한도를 즉시 소진해 다른 사용자의 시세까지 막는다.

`mockData.js`의 코인 지수 mock이 `HomeView`에서 폴백으로 쓰이는데, 실데이터 연동 후에도 장애 시 폴백으로 남길지 제거할지는 이 Task에서 판단해 보고한다(무단 삭제 금지).

- [ ] **Step 3: lint/build + 브라우저 확인**

주문 타입을 바꿀 때 입력 필드가 실제로 바뀌는지 브라우저에서 확인한다. **실제 주문 버튼은 누르지 않는다.**

- [ ] **Step 4: 커밋** — `feat(web-app): add coin trading screen and enable coin tab`

---

## Task 9 (팀리드): 문서 갱신

**Files:**
- Create: `api-server/_docs/UPBIT_API_GUIDE.md` (신규 — KIS_API_GUIDE.md와 같은 위상)
- Modify: `api-server/_docs/API_DESIGN.md`, `STATUS.md`, `ARCHITECTURE.md`
- Modify: `web-app/_docs/STATUS.md`, `SCREENS.md`, `ARCHITECTURE.md`
- Modify: `_docs/STATUS.md`, `_docs/ARCHITECTURE.md`, 루트 `CLAUDE.md`
- Modify: `database/README.md`, `database/schema.sql`(재생성)
- Modify: `.env.example` (필요 시)

`UPBIT_API_GUIDE.md`에는 JWT 인증 방식, 주문 타입 3종 매핑표, rate limit, IP 화이트리스트 주의사항을 반드시 담는다 — 이 셋이 이 연동의 함정이다.

- [ ] **Step 1: 문서 작성·갱신 → 커밋** — `docs: document upbit coin trading feature`

---

## Task 10 (QA팀): 코인 기능 검증

**개발 담당과 다른 에이전트**가 맡는다.

- [ ] **Step 1: 코드 리뷰** — 스펙 대비 누락, Global Constraints 위반(`double` 사용, 6자리 코드 가정, 평문 폴백), 계획 범위 밖 변경.
- [ ] **Step 2: JWT 인증 로직 집중 검증** — 이 계획에서 가장 위험한 부분. 서명·`query_hash`·nonce 유일성 테스트가 실제로 존재하고 통과하는지 확인.
- [ ] **Step 3: 주문 타입 매핑 3종 재검증** — 시장가 매수가 정말 총액을 보내고 volume을 안 보내는지, 반대는 반대인지 테스트 코드로 확인.
- [ ] **Step 4: 소수 정밀도 검증** — `0.00000123` 같은 값이 UI → API → 업비트 파라미터 → DB → 조회 왕복에서 보존되는지, 지수표기(`1.23E-6`)로 변질되지 않는지.
- [ ] **Step 5: 보안 검증** — Secret Key가 응답·로그에 평문으로 노출되지 않는지. `/coins/accounts`·`/coins/buy`가 인증 없이 접근되지 않는지.
- [ ] **Step 6: 경계면 검증** — api-server DTO 필드명 ↔ web-app 소비 코드 대조.
- [ ] **Step 7: 전체 테스트 재실행** — `./gradlew build test redisTest kafkaTest timescaledbTest` + `npm run lint && npm run build`. 회귀 0건.
- [ ] **Step 8: 결과 보고** — `_workspace/qa_coin_trading.md`.

---

## Self-Review 메모 (계획 작성자용, 실행 시 무시)

- 스펙의 5개 범위 섹션 전부 Task 1~8에 매핑됨. 비범위(원화 외 마켓·입출금·주문취소·실시간 WS·AI 연동)는 어느 Task에도 없음 — 의도적.
- 타입 일관성: `quantity`/`volume`이 전 구간 `BigDecimal` ↔ `NUMERIC(30,8)` ↔ 문자열로 연결됨. `market`은 전 구간 `String`(`KRW-BTC`). `orderUuid`는 `String`.
- Task 3(JWT)과 Task 4(주문 매핑)를 이 계획의 최대 리스크로 명시하고, 각각 실패를 잡는 테스트를 Step 1에 배치했다.
- Task 0을 둔 이유: 업비트 인증 규격을 실측 없이 구현하면 401 디버깅에 시간을 크게 쓴다. 시세 API는 인증이 불필요해 지금 바로 실측 가능하므로 DTO를 추정으로 만들 이유가 없다.

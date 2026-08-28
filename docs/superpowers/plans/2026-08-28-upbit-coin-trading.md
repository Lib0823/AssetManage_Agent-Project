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
- Liquibase는 새 changeset만 추가. 채권 계획이 `v1.29`를 쓰므로 이 계획은 `v1.30`부터.
- 이 계획은 **ai-agent를 건드리지 않는다.**

---

## Task 0 (팀리드): 개발 착수 전 사전 검토

**Files:** 없음(읽기 전용 조사)

- [ ] **Step 1: 업비트 JWT 인증 규격 확정**

공식 문서에서 다음을 정확히 확보해 `_workspace/upbit_api_contract.md`에 기록한다:
- JWT 페이로드 필드(`access_key`, `nonce`, 파라미터 있을 때 `query_hash`/`query_hash_alg`)
- `query_hash` 계산법(쿼리스트링 SHA512 hex)
- 서명 알고리즘(HS256, secret key)
- 파라미터가 **없는** 요청(예: `GET /v1/accounts`)에서 `query_hash`를 넣어야 하는지 **빼야 하는지** — 이걸 틀리면 전부 401이 난다

참고: `https://docs.upbit.com/kr/` 및 `https://global-docs.upbit.com/reference/`

- [ ] **Step 2: 주문 API 파라미터 3종 확정**

지정가 / 시장가 매수 / 시장가 매도의 정확한 파라미터 조합을 문서에서 확인해 표로 정리한다:

| 의도 | `side` | `ord_type` | `volume` | `price` |
|---|---|---|---|---|
| 지정가 매수 | `bid` | `limit` | 수량 | 단가 |
| 지정가 매도 | `ask` | `limit` | 수량 | 단가 |
| 시장가 매수 | `bid` | `price` | (미전송) | **총액** |
| 시장가 매도 | `ask` | `market` | 수량 | (미전송) |

문서와 다르면 **문서가 정답**이다. 이 표가 Task 4의 매핑 구현 근거가 된다.

- [ ] **Step 3: 공개 시세 API를 실제로 1회 호출해 응답 확인**

시세 API는 인증이 불필요하므로 지금 바로 확인할 수 있다:
```bash
curl -s "https://api.upbit.com/v1/market/all?isDetails=false" | head -c 500
curl -s "https://api.upbit.com/v1/ticker?markets=KRW-BTC" | head -c 500
curl -s "https://api.upbit.com/v1/orderbook?markets=KRW-BTC" | head -c 800
```
실제 필드명·타입(숫자인지 문자열인지)을 기록한다. **이것이 Task 2 DTO의 계약이다** — 추정이 아니라 실측이다.

- [ ] **Step 4: 사용자에게 확인할 것 보고**

업비트 API 키 발급에는 **본인인증 + 2채널 인증 + PC 웹 접속 + 서버 공인 IP 등록**이 필요하다. 개발 완료 후 실거래 검증을 하려면 사용자가 직접 발급해야 하므로, 이 사실과 필요한 IP를 팀리드에게 보고한다.

---

## Task 1 (backend-engineer): DB — 업비트 계좌 + 코인 거래이력

**Files:**
- Create: `api-server/src/main/resources/db/changelog/mvp/v1.30-upbit-account-and-coin-history.yaml`
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
      id: 1.30.1-create-user-upbit-accounts
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
      id: 1.30.2-create-coin-trade-history
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
              - column: { name: order_state, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: volume, type: "NUMERIC(30,8)" }
              - column: { name: price, type: "NUMERIC(30,8)" }
              - column: { name: order_uuid, type: VARCHAR(64), constraints: { nullable: false, unique: true } }
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
- Create: `api-server/src/main/java/com/inbeom/apiserver/exception/UpbitApiException.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/CoinQuoteServiceTest.java`

**Interfaces:**
- Produces:
  - `UpbitApiClient.BASE_URL = "https://api.upbit.com"`
  - `UpbitApiClient.<T> ResponseEntity<T> getPublic(String path, Map<String,String> queryParams, Class<T> responseType)`
  - `CoinQuoteService.getKrwMarkets() -> List<CoinMarketResponse>`
  - `CoinQuoteService.getTicker(String market) -> CoinTickerResponse`
  - `CoinQuoteService.getOrderbook(String market) -> CoinOrderbookResponse`

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

`UpbitApiClient`는 `RestTemplate` 기반으로 만들고, 타임아웃은 `KisApiClient`와 같은 수준(connect 5s, read 18s)으로 둔다. 실패는 전부 `UpbitApiException`으로 정규화한다.

**DTO 필드는 Task 0 Step 3에서 실측한 응답을 기준으로 만든다.** 가격·수량 필드는 `BigDecimal`.

Run: `cd api-server && ./gradlew test --tests "*CoinQuoteServiceTest*"`

- [ ] **Step 3: 커밋** — `feat(api-server): add upbit public market data lookup`

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

```java
@Test
@DisplayName("파라미터 없는 요청의 JWT는 access_key와 nonce만 갖는다")
void jwtWithoutParams_hasNoQueryHash() {
    String token = UpbitApiClient.buildJwt("test-access-key", "test-secret-key", Map.of());

    Claims claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor("test-secret-key".getBytes(StandardCharsets.UTF_8)))
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

    String token = UpbitApiClient.buildJwt("test-access-key", "test-secret-key", params);

    Claims claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor("test-secret-key".getBytes(StandardCharsets.UTF_8)))
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
    String a = UpbitApiClient.buildJwt("k", "s", Map.of());
    String b = UpbitApiClient.buildJwt("k", "s", Map.of());
    assertThat(a).isNotEqualTo(b);
}
```

> Task 0 Step 1에서 확인한 규격과 위 테스트가 어긋나면 **Task 0의 실측 결과를 따르고 이 테스트를 고친다.**

- [ ] **Step 2: 실패 확인 → `buildJwt` 구현 → 통과 확인**

`jjwt 0.12.3`(이미 의존성에 있음)을 쓴다. 쿼리스트링은 **파라미터 삽입 순서를 유지**해야 하므로 `LinkedHashMap`으로 받는다.

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

`UserController`에 `GET /users/upbit-account`, `PUT /users/upbit-account`를 추가한다. `UserService`는 저장 시 Jasypt로 암호화하고, 조회 시 `secretKey`를 **마스킹해서** 반환한다(전체 노출 금지 — `KisAccountResponse`가 appSecret을 다루는 방식 확인 후 동일하게).

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
| GET | `/coins/{market}/ticker` | PUBLIC |
| GET | `/coins/{market}/orderbook` | PUBLIC |
| GET | `/coins/accounts` | AUTH |
| POST | `/coins/buy` | AUTH |
| POST | `/coins/sell` | AUTH |
| GET | `/coins/history` | AUTH |

`OverseasController` 구조를 따른다.

- [ ] **Step 2: `SecurityConfig`에 공개 경로만 추가**

`/coins/markets`, `/coins/*/ticker`, `/coins/*/orderbook`만 permitAll. **`/coins/**` 전체를 열지 않는다**(자산·주문이 함께 열린다).

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

- [ ] **Step 2: 탭 활성화 + `AssetsView` 코인 잔고 연동**

`AssetTabs.vue`·`InvestmentTabs.vue`의 `coins` → `disabled: false`, `uiSettings.js` 라벨 정리.

`mockData.js`의 코인 지수 mock이 `HomeView`에서 폴백으로 쓰이는데, 실데이터 연동 후에도 KIS/업비트 장애 시 폴백으로 남길지 제거할지는 이 Task에서 판단해 보고한다(무단 삭제 금지).

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

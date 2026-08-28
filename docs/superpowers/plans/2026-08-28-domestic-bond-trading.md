# 장내채권 보유·매도 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비활성 상태인 채권 탭을, 보유 채권을 확인하고 팔 수 있는 기능으로 만든다.

**Architecture:** 기존 KIS 인프라를 재사용한다 — `KisApiClient`(공통 헤더·rate limit·캐시·실전 도메인)와 `KisAuthService`(사용자별 자격증명)를 그대로 쓰고, 채권 TR_ID 상수·응답 파싱·매도 파라미터 매핑만 신규 클래스가 담당한다. 거래내역은 국내주식과 동일하게 KIS에서 직접 조회하므로 **신규 DB 테이블이 없다.**

**Tech Stack:** Spring Boot 4.1 / Java 21 / JUnit 5 + Mockito, Vue 3 + Vite.

**참조:** 스펙 `docs/superpowers/specs/2026-08-28-domestic-bond-trading-design.md`, 사전 검토 `_workspace/preflight_bond.md`, API 실측 `_workspace/bond_api_contract.md`

## Global Constraints

- **실전투자 전용.** 모의 TR_ID·도메인 분기를 만들지 않는다.
- **단가·금액은 전 구간 `BigDecimal`**(Java) / 문자열(JS). `double`/`float` 금지.
- **종목코드(`PDNO`)는 12자리 영숫자 혼합 문자열**(`KR2033022D33`). 숫자 전용 검증·6자리 가정 금지.
- **매도는 로트 단위다.** `PDNO`만으로 팔 수 없고 `BUY_DT`(매수일자) + `BUY_SEQ`(매수순번)가 함께 필요하다. 잔고 응답의 대응 필드명은 `buy_dt` / **`buy_sqno`**(이름이 다름 — 매핑 주의).
- **`SPRX_YN`(분리과세여부)을 임의값으로 고정하지 않는다.** 잔고의 `sprx_qty`/`agrx_qty` 또는 `issue_info.sprx_psbl_yn`에서 유도한다.
- **금액 환산 계수를 코드에 상수로 박지 않는다.** 수량 단위가 미확정이므로 설정값(`application.yml`)으로 분리한다.
- 조회 경로는 graceful degrade(200 + 빈 결과 + `notice`), 매도 경로는 예외 전파(4xx/5xx).
- 모든 응답은 `ApiResponse<T>`로 감싼다.
- **신규 DB 테이블·changeset 없음.**
- 이 계획은 **ai-agent를 건드리지 않는다.**
- **코인 계획과 프론트 파일 6개가 겹친다**(`AssetTabs.vue`, `InvestmentTabs.vue`, `AssetsView.vue`, `uiSettings.js`, `services/api.js`, `router/index.js`). **두 계획을 병렬 실행하지 않는다.**

---

## Task 0: 사전 검토 — **완료됨**

`_workspace/preflight_bond.md`, `_workspace/bond_api_contract.md` 참조. 이 계획은 그 결과를 이미 반영해 개정된 판이다. **다시 수행하지 않는다.**

미해소로 남은 항목(실계좌 필요, 사용자가 "나중에 확인"으로 결정):
- 수량 단위(`ORD_QTY2`가 액면금액인지 좌수인지) → 환산 계수를 설정값으로 분리해 우회
- quote appKey의 채권 API 권한 여부
- `PRDT_TYPE_CD="302"` 하드코딩 가능 여부

---

## Task 1 (backend-engineer): `KisBondClient` + 채권 시세 조회

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/BondQuoteService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondInfoResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondIssueInfoResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondPriceResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondOrderbookResponse.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/config/KisResilienceProperties.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/client/KisBondClientTest.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/BondQuoteServiceTest.java`

**Interfaces:**
- Consumes: `KisApiClient.get(baseUrl, endpoint, trId, kisToken, appKey, appSecret, queryParams, responseType)` (8-arg, `client/KisApiClient.java:257`), `KisQuoteService`(앱 레벨 시세 자격증명).
- Produces:
  - 상수: `TR_SEARCH_BOND_INFO="CTPF1114R"`, `TR_ISSUE_INFO="CTPF1101R"`, `TR_INQUIRE_PRICE="FHKBJ773400C0"`, `TR_INQUIRE_ASKING_PRICE="FHKBJ773401C0"`
  - `BondQuoteService.getBondInfo(String bondCode) -> BondInfoResponse`
  - `BondQuoteService.getIssueInfo(String bondCode) -> BondIssueInfoResponse`
  - `BondQuoteService.getPrice(String bondCode) -> BondPriceResponse`
  - `BondQuoteService.getOrderbook(String bondCode) -> BondOrderbookResponse`
  - 각 DTO는 `notice`(String, nullable) 필드를 갖는다.

- [ ] **Step 1: TR_ID·경로 계약 테스트 작성**

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
    @DisplayName("채권 엔드포인트가 /uapi/domestic-bond/v1/** 경로다")
    void bondEndpointsUseDomesticBondPath() {
        assertThat(KisBondClient.PATH_SEARCH_BOND_INFO)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/search-bond-info");
        assertThat(KisBondClient.PATH_ISSUE_INFO)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/issue-info");
        assertThat(KisBondClient.PATH_INQUIRE_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-price");
        assertThat(KisBondClient.PATH_INQUIRE_ASKING_PRICE)
                .isEqualTo("/uapi/domestic-bond/v1/quotations/inquire-asking-price");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd api-server && ./gradlew test --tests "*KisBondClientTest*"`
Expected: FAIL — `KisBondClient` 없음(컴파일 에러).

- [ ] **Step 3: `KisBondClient` 구현**

TR_ID·경로를 `public static final String` 상수로 두고 `KisApiClient`에 위임하는 얇은 래퍼로 만든다. **자체 HTTP 로직을 만들지 않는다.**

`search_bond_info`/`issue_info`는 `PRDT_TYPE_CD`가 필수다. 지금은 `"302"`를 쓰되 **상수로 분리해 주석에 "채권 종류별로 다를 수 있음, 실계좌 검증 필요"를 남긴다.**

> `KisApiClient.get()`은 쿼리스트링을 URL 인코딩 없이 이어붙인다. 빈 값 파라미터를 넘길 때 `Map.of()`는 null을 못 받으므로 **빈 문자열**을 쓴다.

- [ ] **Step 4: 통과 확인**

Run: `cd api-server && ./gradlew test --tests "*KisBondClientTest*"` → PASS

- [ ] **Step 5: `BondQuoteService` 테스트 작성**

```java
@Test
@DisplayName("KIS 실패 시 예외 대신 notice가 담긴 빈 결과를 준다")
void kisFailure_returnsEmptyWithNotice() {
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willThrow(KisApiException.serverError("KIS down", null));

    BondPriceResponse result = bondQuoteService.getPrice("KR2033022D33");

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

    assertThatCode(() -> bondQuoteService.getOrderbook("KR2033022D33"))
            .doesNotThrowAnyException();
}

@Test
@DisplayName("현재가를 BigDecimal로 파싱해 소수점을 잃지 않는다")
void unitPriceKeepsDecimals() {
    Map<String, Object> body = Map.of("rt_cd", "0",
            "output", Map.of("bond_prpr", "9850.5"));
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

    BondPriceResponse result = bondQuoteService.getPrice("KR2033022D33");

    assertThat(result.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("9850.5"));
}

@Test
@DisplayName("신용등급은 평가사별 4개를 모두 보존한다")
void creditGradesFromAllAgencies() {
    Map<String, Object> body = Map.of("rt_cd", "0", "output", Map.of(
            "kis_crdt_grad_text", "AA+", "kbp_crdt_grad_text", "AA",
            "nice_crdt_grad_text", "AA+", "fnp_crdt_grad_text", ""));
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

    BondIssueInfoResponse result = bondQuoteService.getIssueInfo("KR2033022D33");

    assertThat(result.getKisCreditGrade()).isEqualTo("AA+");
    assertThat(result.getKbpCreditGrade()).isEqualTo("AA");
}
```

> 필드명(`bond_prpr`, `kis_crdt_grad_text`)은 `_workspace/bond_api_contract.md`의 실측값이다. 실계좌 응답이 다르면 그쪽이 정답이다.

- [ ] **Step 6: 실패 확인 → 구현 → 통과 확인**

Run: `cd api-server && ./gradlew test --tests "*BondQuoteServiceTest*"`

- [ ] **Step 7: 채권 시세 TR을 캐시 allowlist에 추가**

`config/KisResilienceProperties.java`의 `policyFor(trId)` allowlist에 `FHKBJ773400C0`(현재가), `FHKBJ773401C0`(호가) **2개만** 추가한다.

**절대 추가하지 말 것**: `CTSC8407R`(잔고), `TTTC0958U`(매도), `CTSC8013R`(체결). 주문·잔고를 캐시하면 "주문이 나간 것처럼 보이지만 실제로는 안 나간" 상태가 만들어진다.

이 조치가 두 문제를 동시에 푼다: (a) 채권 시세에 stale-if-error 폴백이 생겨 KIS 장애 시에도 화면이 유지되고, (b) 캐시 히트는 rate limit 토큰을 소비하지 않으므로 채권 조회가 주식 시세 여력을 잠식하지 않는다.

- [ ] **Step 8: 커밋** — `feat(api-server): add bond quote lookup with cache allowlist`

---

## Task 2 (backend-engineer): 보유 채권 잔고 조회 (연속조회 포함)

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/service/BondTradingService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondBalanceResponse.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondHoldingResponse.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/BondTradingServiceTest.java`

**Interfaces:**
- Consumes: `KisAuthService.getKisAccessToken(Long kisAccountId)`, `KisAuthService.getKisCredentials(Long kisAccountId)` → `KisCredentials(appKey, appSecret, accountNumber, accountProductCode, baseUrl)`.
- Produces:
  - `KisBondClient.TR_INQUIRE_BALANCE="CTSC8407R"`, `PATH_INQUIRE_BALANCE="/uapi/domestic-bond/v1/trading/inquire-balance"`
  - `BondTradingService.getBalance(Long userId) -> BondBalanceResponse`
  - `BondHoldingResponse`: `bondCode`, `bondName`, `quantity`(BigDecimal), `buyAmount`(BigDecimal), **`buyDate`(String, `buy_dt`)**, **`buySeq`(String, `buy_sqno`)**, `sprxQty`, `agrxQty` — **뒤 4개가 매도에 필요하다**
  - private `requireKisAccountId(User user)` — Task 3이 재사용

- [ ] **Step 1: 널 가드 + 연속조회 테스트 작성**

```java
@Test
@DisplayName("KIS 계좌가 없으면 KisAccountNotFoundException (NPE 아님)")
void noKisAccount_throwsKisAccountNotFound() {
    User user = User.builder().id(1L).build();   // kisAccount == null
    given(userRepository.findById(1L)).willReturn(Optional.of(user));

    assertThatThrownBy(() -> bondTradingService.getBalance(1L))
            .isInstanceOf(KisAccountNotFoundException.class);
}

@Test
@DisplayName("tr_cont=M이면 다음 페이지를 이어서 조회한다")
void continuationHeader_fetchesNextPage() {
    HttpHeaders more = new HttpHeaders();
    more.set("tr_cont", "M");
    Map<String, Object> page1 = Map.of("rt_cd", "0",
            "ctx_area_fk200", "FK", "ctx_area_nk200", "NK",
            "output", List.of(Map.of("pdno", "KR1111111111", "buy_dt", "20260101", "buy_sqno", "1")));
    Map<String, Object> page2 = Map.of("rt_cd", "0",
            "output", List.of(Map.of("pdno", "KR2222222222", "buy_dt", "20260201", "buy_sqno", "2")));

    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(page1, more, HttpStatus.OK))
            .willReturn(new ResponseEntity<>(page2, HttpStatus.OK));

    BondBalanceResponse result = bondTradingService.getBalance(1L);

    assertThat(result.getHoldings()).hasSize(2);   // 두 페이지가 합쳐진다
}

@Test
@DisplayName("매도에 필요한 로트 정보(buy_dt, buy_sqno)를 보존한다")
void holdingKeepsLotIdentifiers() {
    Map<String, Object> body = Map.of("rt_cd", "0", "output", List.of(
            Map.of("pdno", "KR2033022D33", "buy_dt", "20260315", "buy_sqno", "3")));
    given(kisApiClient.get(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

    BondHoldingResponse holding = bondTradingService.getBalance(1L).getHoldings().get(0);

    assertThat(holding.getBuyDate()).isEqualTo("20260315");
    assertThat(holding.getBuySeq()).isEqualTo("3");   // 응답은 buy_sqno, 요청은 BUY_SEQ
}

@Test
@DisplayName("원화(KRW)가 아닌 채권은 합산에서 제외한다")
void nonKrwBondsExcludedFromTotal() {
    // iso_crcy_cd != "KRW"인 행이 총액에 더해지지 않는지 확인
}
```

- [ ] **Step 2: 실패 확인 → 구현 → 통과 확인**

`requireKisAccountId`는 `OverseasTradingService.java:474` 패턴을 따른다:
```java
private Long requireKisAccountId(User user) {
    if (user.getKisAccount() == null) {
        throw new KisAccountNotFoundException("KIS account not registered for userId: " + user.getId());
    }
    return user.getKisAccount().getId();
}
```

잔고 요청 파라미터: `CANO`, `ACNT_PRDT_CD`, `INQR_CNDT="00"`(전체), `PDNO=""`, `BUY_DT=""`, `CTX_AREA_FK200`, `CTX_AREA_NK200`. 연속조회는 `tr_cont` 응답 헤더가 `"M"`인 동안 반복하되 **무한 루프 방지 상한(예: 20페이지)**을 둔다.

조회 경로이므로 KIS 실패 시 graceful degrade.

- [ ] **Step 3: 커밋** — `feat(api-server): add bond balance lookup with pagination`

---

## Task 3 (backend-engineer): 채권 매도 주문 + 거래내역

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/KisBondClient.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/BondTradingService.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondSellRequest.java`
- Create: `api-server/src/main/java/com/inbeom/apiserver/dto/bond/BondTradeHistoryResponse.java`
- Modify: `api-server/src/main/resources/application.yml` (환산 계수 설정)
- Modify: `api-server/src/test/java/com/inbeom/apiserver/service/BondTradingServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 `requireKisAccountId`.
- Produces:
  - `KisBondClient.TR_SELL="TTTC0958U"`, `PATH_SELL="/uapi/domestic-bond/v1/trading/sell"`
  - `KisBondClient.TR_INQUIRE_DAILY_CCLD="CTSC8013R"`, `PATH_INQUIRE_DAILY_CCLD="/uapi/domestic-bond/v1/trading/inquire-daily-ccld"`
  - `BondSellRequest`: `bondCode`(String), `bondName`(String), `quantity`(BigDecimal), `unitPrice`(BigDecimal), **`buyDate`(String)**, **`buySeq`(String)**, `separateTaxation`(Boolean, nullable)
  - `BondTradingService.sell(Long userId, BondSellRequest request) -> Map<String, Object>`
  - `BondTradingService.getHistory(Long userId, String startDate, String endDate) -> List<BondTradeHistoryResponse>`

- [ ] **Step 1: 매도 파라미터 테스트 작성**

```java
@Test
@DisplayName("매도 주문이 로트 식별자를 포함한 KIS 파라미터로 나간다")
void sell_sendsLotIdentifiersAndRequiredFields() {
    BondSellRequest req = new BondSellRequest(
            "KR2033022D33", "국고채권", new BigDecimal("10"), new BigDecimal("9850.5"),
            "20260315", "3", Boolean.TRUE);

    bondTradingService.sell(1L, req);

    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(kisApiClient).post(anyString(), eq("/uapi/domestic-bond/v1/trading/sell"),
            eq("TTTC0958U"), anyString(), anyString(), anyString(), body.capture(), eq(Map.class));

    Map<String, Object> sent = body.getValue();
    assertThat(sent.get("PDNO")).isEqualTo("KR2033022D33");
    assertThat(sent.get("ORD_QTY2")).isEqualTo("10");
    assertThat(sent.get("BOND_ORD_UNPR")).isEqualTo("9850.5");   // 소수점·비지수 표기 보존
    assertThat(sent.get("BUY_DT")).isEqualTo("20260315");
    assertThat(sent.get("BUY_SEQ")).isEqualTo("3");              // 요청은 BUY_SEQ (응답은 buy_sqno)
    assertThat(sent).containsKeys("ORD_DVSN", "SPRX_YN", "SLL_AGCO_OPPS_SLL_YN");
}

@Test
@DisplayName("분리과세여부는 요청값을 반영한다 — 임의 고정이 아니다")
void separateTaxationFlagIsNotHardcoded() {
    bondTradingService.sell(1L, sellRequestWithSeparateTaxation(true));
    assertThat(captureSellBody().get("SPRX_YN")).isEqualTo("Y");

    clearInvocations(kisApiClient);

    bondTradingService.sell(1L, sellRequestWithSeparateTaxation(false));
    assertThat(captureSellBody().get("SPRX_YN")).isEqualTo("N");
}

@Test
@DisplayName("주문 실패는 degrade하지 않고 예외를 전파한다")
void orderFailure_propagates() {
    Map<String, Object> failBody = Map.of("rt_cd", "1", "msg1", "보유수량 부족");
    given(kisApiClient.post(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(Map.class)))
            .willReturn(new ResponseEntity<>(failBody, HttpStatus.OK));

    assertThatThrownBy(() -> bondTradingService.sell(1L, validSellRequest()))
            .isInstanceOf(BusinessException.class);
}

@Test
@DisplayName("소수 단가가 지수표기로 변질되지 않는다")
void smallUnitPriceNotSerializedAsScientificNotation() {
    bondTradingService.sell(1L, sellRequestWithUnitPrice(new BigDecimal("0.0001")));
    assertThat(captureSellBody().get("BOND_ORD_UNPR")).isEqualTo("0.0001");
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd api-server && ./gradlew test --tests "*BondTradingServiceTest*"`

- [ ] **Step 3: `sell` 구현**

- `BOND_ORD_UNPR`은 `BigDecimal.toPlainString()`으로 넣는다(`toString()`은 작은 값에서 지수표기를 낸다).
- `SPRX_YN`은 요청의 `separateTaxation`을 반영한다. 값이 없으면 **임의 기본값을 쓰지 말고** 잔고의 `sprx_qty`/`agrx_qty`에서 유도하거나, 유도 불가 시 명확한 예외를 던진다.
- `SAMT_MKET_PTCI_YN`/`BOND_RTL_MKET_YN`은 서버가 채운다. **매수 예제는 `N`/`Y`인데 매도 예제는 `bond_rtl_mket_yn="N"`이므로 매도는 `N`을 쓴다**(실계좌 검증 시 재확인 대상으로 주석에 남긴다).
- `rt_cd != "0"`이면 국내주식 `verifyKisOrderSuccess`와 동일하게 예외를 던진다.
- **DB에 기록하지 않는다** — 거래내역은 KIS 조회 방식이다.

- [ ] **Step 4: `getHistory` 구현 (`CTSC8013R`)**

국내주식 `getTradeHistory`(`TradingService`)의 구조를 따른다. 조회 경로이므로 실패 시 빈 목록 + `notice`.

- [ ] **Step 5: 환산 계수 설정 분리**

`application.yml`에 추가:
```yaml
kis:
  bond:
    # 채권 주문 수량 단위 (ORD_QTY2)가 액면금액인지 좌수인지 미확정.
    # 예상금액 = 수량 × 단가 / face-value-divisor.
    # 실계좌 inquire-psbl-order 로 buy_psbl_amt ÷ buy_psbl_qty 비율 확인 후 확정할 것.
    face-value-divisor: ${KIS_BOND_FACE_VALUE_DIVISOR:100}
```
서비스는 이 값을 주입받아 쓰고, **코드에 `100`을 리터럴로 쓰지 않는다.**

- [ ] **Step 6: 통과 확인 → 커밋** — `feat(api-server): add bond sell order and trade history`

---

## Task 4 (backend-engineer): `BondController` + Security 설정

**Files:**
- Create: `api-server/src/main/java/com/inbeom/apiserver/controller/BondController.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/config/SecurityConfig.java`

**Interfaces:**
- Consumes: Task 1~3의 서비스 메서드, `JwtTokenProvider.resolveBearerToken` / `getUserIdFromToken`.
- Produces: 아래 엔드포인트 7종. 프론트(Task 5~8)가 이 계약을 소비한다.

- [ ] **Step 1: 컨트롤러 작성**

`OverseasController` 구조를 따른다(`@RequestMapping("/bonds")`, 생성자 주입, `ApiResponse.success(message, data)`).

| 메서드 | 경로 | 인증 |
|---|---|---|
| GET | `/bonds/balance` | AUTH |
| GET | `/bonds/{bondCode}` | PUBLIC |
| GET | `/bonds/{bondCode}/issue-info` | PUBLIC |
| GET | `/bonds/{bondCode}/price` | PUBLIC |
| GET | `/bonds/{bondCode}/orderbook` | PUBLIC |
| POST | `/bonds/sell` | AUTH |
| GET | `/bonds/history` | AUTH |

> `/bonds/balance`(고정 1세그먼트)와 `/bonds/{bondCode}`(경로변수)가 같은 깊이다. **`balance`가 `bondCode`로 잡히지 않도록 매핑 순서·정규식을 확인**한다. 채권코드는 12자리 영숫자이므로 `@GetMapping("/{bondCode:[A-Za-z0-9]{12}}")` 같은 제약을 두는 편이 안전하다.

- [ ] **Step 2: `SecurityConfig`에 공개 경로만 추가**

`SecurityConfig.java:45` permitAll 목록에 `/bonds/*/issue-info`, `/bonds/*/price`, `/bonds/*/orderbook`, `/bonds/{12자리}` 를 개별 추가한다. **`/bonds/**` 전체를 열지 않는다** — 잔고·매도가 함께 열린다.

`.anyRequest().authenticated()`가 마지막에 있으므로 명시하지 않은 채권 경로는 자동으로 AUTH다.

- [ ] **Step 3: 인증 경계 테스트**

```java
@Test
@DisplayName("채권 잔고·매도는 인증이 필요하다")
void bondTradingEndpointsRequireAuth() throws Exception {
    mockMvc.perform(get("/bonds/balance")).andExpect(status().is4xxClientError());
    mockMvc.perform(post("/bonds/sell").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().is4xxClientError());
    mockMvc.perform(get("/bonds/history")).andExpect(status().is4xxClientError());
}

@Test
@DisplayName("채권 시세 조회는 공개다")
void bondQuoteEndpointsArePublic() throws Exception {
    mockMvc.perform(get("/bonds/KR2033022D33/price")).andExpect(status().isOk());
}
```

- [ ] **Step 4: 전체 테스트 실행**

```bash
cd api-server && JWT_SECRET=dummy-secret-for-local-development-only-32bytes JASYPT_PASSWORD=dummy ./gradlew build test
```
기존 500건 + 신규분 전부 통과, 회귀 0건.

- [ ] **Step 5: 커밋 + 프론트에 계약 통보**

엔드포인트 표와 응답 DTO 필드명(camelCase 직렬화)을 `_workspace/bond_api_contract_final.md`에 정리해 공유한다. **프론트는 이 문서를 계약으로 삼는다.**

---

## Task 5 (frontend-engineer): `bondApi` + `AssetsView` 채권 카드 (진입점)

**Files:**
- Modify: `web-app/src/services/api.js`
- Modify: `web-app/src/views/main/AssetsView.vue`
- Modify: `web-app/src/utils/uiSettings.js`

**Interfaces:**
- Consumes: `_workspace/bond_api_contract_final.md`.
- Produces: `bondApi.getBalance()`, `getBondInfo(code)`, `getIssueInfo(code)`, `getPrice(code)`, `getOrderbook(code)`, `sell(payload)`, `getHistory(params)`.

- [ ] **Step 1: `bondApi` 추가**

`services/api.js`의 기존 12개 객체 옆에 추가. **뷰에서 axios 직접 호출 금지**(api.js 경유 원칙).

- [ ] **Step 2: `AssetsView`의 채권 카드를 실제 카드로 교체**

현재 `AssetsView.vue:510` 부근의 `<section class="asset-card disabled">채권 (추후 지원)</section>`을 `bondApi.getBalance()` 결과를 보여주는 카드로 바꾼다. **이 카드가 채권 기능의 유일한 진입점**이다(검색이 없으므로).

- 보유 채권이 없으면 "보유 중인 채권이 없습니다" 안내.
- 각 항목은 종목명 + 매수일 + 수량 + **매수금액**을 보여주고, 누르면 `/bonds/:code`로 이동한다. **로트 정보(`buyDate`/`buySeq`)를 라우트 state나 쿼리로 함께 넘긴다** — 매도에 필요하다.
- 카드 상단에 **"매수금액 기준"**을 명시한다(평가금액이 아님).

- [ ] **Step 3: 총자산 합산 처리**

`AssetsView.vue:141` `const totalAsset = cashAmount + stocksTotal`에 채권을 더한다.

**주의**: 바로 아래 `AssetsView.vue:163`이 `assetApi.recordSnapshot(totalAsset)`으로 `asset_daily_snapshot`에 쓴다. 채권을 더하는 순간부터 **총자산 시계열이 불연속**이 된다(도입일에 계단처럼 뛴다). 이는 데이터 성격상 불가피하므로, **자산 추이 그래프에 이 시점을 표시하거나 최소한 이 사실을 `_workspace`에 기록**한다.

`utils/uiSettings.js`의 `{ key: 'bonds', label: '채권 (추후 지원)' }` → `label: '채권'`. (`asset_order`에 `bonds` 키가 이미 있어 마이그레이션 불필요.)

- [ ] **Step 4: lint/build → 커밋** — `feat(web-app): add bond holdings card to assets view`

---

## Task 6 (frontend-engineer): 채권 상세 화면

**Files:**
- Create: `web-app/src/views/detail/BondDetailView.vue`
- Modify: `web-app/src/router/index.js` (`/bonds/:code`)

- [ ] **Step 1: 화면 작성**

`Promise.allSettled`로 기본정보·발행정보·시세·호가를 병렬 조회한다(하나가 실패해도 나머지를 보여준다).

표시 항목:
- **보유 로트 정보**(라우트로 받은 매수일·수량·매수금액)
- 현재가 — **소수점 유지**. 원화 정수 포맷터(`toLocaleString()`)를 그대로 쓰면 잘리므로 소수 2~4자리를 유지하는 전용 포맷 함수를 둔다.
- 발행정보 — 만기일·표면금리·**평가사별 신용등급 4개**(단일 등급이 아님).
- 호가 — 비면 "현재 호가가 없습니다" 안내(에러 아님).

하단에 **"매도" 버튼** → `/bonds/:code/sell`로 로트 정보와 함께 이동.

- [ ] **Step 2: lint/build → 커밋** — `feat(web-app): add bond detail screen`

---

## Task 7 (frontend-engineer): 채권 매도 화면

**Files:**
- Create: `web-app/src/views/detail/BondSellView.vue`
- Modify: `web-app/src/router/index.js` (`/bonds/:code/sell`)

- [ ] **Step 1: 매도 폼 작성**

- **로트 정보(`buyDate`/`buySeq`)를 상세 화면에서 받아 그대로 전송한다.** 사용자가 입력하는 값이 아니다.
- 수량은 보유 수량을 상한으로 검증한다.
- 단가 입력은 **소수점 허용**(`type="text"` + 숫자·소수점 검증. `type="number"`는 브라우저별 소수 처리가 다르다).
- 예상 금액은 서버 설정(`face-value-divisor`)과 같은 규칙으로 계산하되, **"예상 금액은 참고용입니다"를 반드시 표시**한다(수량 단위 미확정 — 스펙 참조).
- 분리과세여부는 보유 정보(`sprxQty`/`agrxQty`)에서 유도해 표시하고, 사용자가 확인할 수 있게 한다.
- **주문 전 확인 다이얼로그** — 종목명·로트(매수일)·수량·단가·예상금액. 실제 자금이 움직인다.
- 주문 실패 시 서버 메시지를 그대로 노출한다(degrade 대상 아님).

- [ ] **Step 2: lint/build → 커밋** — `feat(web-app): add bond sell screen`

---

## Task 8 (frontend-engineer): 탭 활성화 + 공유 화면 5개 처리

**Files:**
- Modify: `web-app/src/components/common/AssetTabs.vue`
- Modify: `web-app/src/components/common/InvestmentTabs.vue`
- Modify: `web-app/src/views/detail/AssetDetailView.vue`
- Modify: `web-app/src/views/detail/NewsView.vue`
- Modify: `web-app/src/views/detail/TransactionsView.vue`
- Modify: `web-app/src/views/main/SearchView.vue`
- Modify: `web-app/src/views/main/FavoritesView.vue`

> **이 Task가 초판 계획에서 누락됐던 부분이다.** 탭 두 개만 켜면 5개 화면이 조용히 깨진다.

- [ ] **Step 1: 탭 활성화**

`AssetTabs.vue`·`InvestmentTabs.vue`의 `{ key: 'bonds', label: '채권', disabled: true }` → `disabled: false`.

- [ ] **Step 2: 공유 화면 5개 처리**

| 화면 | 조치 |
|---|---|
| `AssetDetailView` | `cash`/`stocks`만 분기하던 `v-if`에 `bonds` 분기 추가 — 보유 채권 목록 표시 |
| `NewsView` | 채권 탭에서 "채권 뉴스는 지원하지 않습니다" 안내(현재는 빈 목록만 나옴) |
| `TransactionsView` | **채권 탭이면 `bondApi.getHistory()`로 전환.** 지금은 분기가 없어 주식 거래내역이 그대로 보인다 |
| `SearchView` | 채권 탭에서 "채권 검색은 지원하지 않습니다. 보유 채권은 자산 화면에서 확인하세요" 안내 |
| `FavoritesView` | 채권 탭에서 "채권 관심종목은 지원하지 않습니다" 안내 |

**`TransactionsView`/`SearchView`/`FavoritesView` 3개는 에러 없이 주식 데이터를 채권인 것처럼 보여주므로 반드시 처리한다.**

- [ ] **Step 3: lint/build + 브라우저 확인**

```bash
cd web-app && npm run lint && npm run build && npm run dev
```
브라우저에서 **채권 탭을 켠 뒤 6개 화면을 모두 눌러본다.** 각 화면이 채권 탭에서 무엇을 보여주는지 확인하고, 주식 데이터가 잘못 보이는 곳이 없는지 본다(api-server 미기동이면 `ERR_CONNECTION_REFUSED`만 나야 하고 JS 런타임 에러는 0건).

**매도 버튼은 누르지 않는다.**

- [ ] **Step 4: 커밋** — `feat(web-app): enable bond tab and handle shared tab screens`

---

## Task 9 (팀리드): 문서 갱신

**Files:**
- Modify: `api-server/_docs/KIS_API_GUIDE.md` (채권 TR_ID 섹션)
- Modify: `api-server/_docs/API_DESIGN.md`, `STATUS.md`
- Modify: `web-app/_docs/STATUS.md`, `SCREENS.md`
- Modify: `_docs/STATUS.md`
- Modify: `_docs/dev_note.txt` (채권 항목 진행 표시)

문서에 **범위가 "보유 조회 + 매도"이며 매수·검색은 KIS API 부재로 제외됐다**는 사실과 그 이유를 남긴다. 나중에 "왜 매수가 없지?"를 다시 조사하지 않도록.

- [ ] **Step 1: 문서 갱신 → 커밋** — `docs: document bond holdings and sell feature`

---

## Task 10 (QA팀): 채권 기능 검증

**개발 담당과 다른 에이전트**가 맡는다.

- [ ] **Step 1: 코드 리뷰** — 스펙 대비 누락, Global Constraints 위반(`double` 사용, 6자리 코드 가정, 환산 계수 하드코딩, `SPRX_YN` 임의 고정), 계획 범위 밖 변경.
- [ ] **Step 2: 로트 식별 검증** — 매도 요청에 `BUY_DT`/`BUY_SEQ`가 실제로 실리는지, 잔고 응답의 `buy_sqno` → 요청 `BUY_SEQ` 매핑이 맞는지(이름이 달라 조용히 빈 값이 나갈 수 있다).
- [ ] **Step 3: 캐시 allowlist 검증** — 시세 2개 TR만 추가됐고 **잔고·매도·체결 TR이 캐시되지 않는지** 확인. 주문 캐시는 심각한 사고다.
- [ ] **Step 4: 연속조회 검증** — `tr_cont=M` 처리와 무한 루프 상한.
- [ ] **Step 5: 탭 공유 화면 6개 확인** — 채권 탭에서 각 화면이 주식 데이터를 잘못 보여주지 않는지. 특히 `TransactionsView`/`SearchView`/`FavoritesView`.
- [ ] **Step 6: 경계면 검증** — api-server DTO 필드명 ↔ web-app 소비 코드 대조.
- [ ] **Step 7: 소수점 정밀도 검증** — 단가 `9850.5`, `0.0001`이 UI → API → KIS 파라미터까지 지수표기 없이 보존되는지.
- [ ] **Step 8: 전체 테스트 재실행** — `./gradlew build test redisTest kafkaTest timescaledbTest` + `npm run lint && npm run build`. 회귀 0건.
- [ ] **Step 9: 결과 보고** — `_workspace/qa_bond_trading.md`.

---

## Self-Review 메모 (계획 작성자용, 실행 시 무시)

- 개정판은 사전 검토의 🔴 5건을 모두 반영했다: F-1(검색 부재 → 범위 축소), F-2(매도 파라미터·로트 → Task 3), F-4(환산식 → 설정값 분리), R-1(평가금액 → 매수금액 표시), R-2(탭 공유 화면 → Task 8 신설).
- 🟠 항목도 반영: F-3(잔고 필수 파라미터·연속조회 → Task 2), C-4/C-5(캐시 allowlist → Task 1 Step 7), R-3(체결 확인 → KIS 조회로 전환, DB 테이블 제거), R-8(프론트 파일 충돌 → Global Constraints에 순차 실행 명시).
- **DB Task가 사라졌다** — 거래내역을 KIS 조회로 정한 결과. changeset·엔티티·리포지토리가 전부 불필요해졌다.
- 타입 일관성: `unitPrice`/`BOND_ORD_UNPR`이 `BigDecimal` ↔ `toPlainString()`으로 연결. `buyDate`/`buySeq`는 전 구간 `String`(응답 `buy_sqno` ↔ 요청 `BUY_SEQ` 이름 차이를 Global Constraints와 테스트로 고정).
- 미해소 항목(수량 단위·quote 키 권한·`PRDT_TYPE_CD`)은 실계좌가 필요해 Task 0에서 해소 불가. 각각 설정값 분리·주석으로 우회했고 스펙 리스크표에 남겼다.

# 장내채권 거래 기능 설계

- 날짜: 2026-08-28
- 상태: 승인됨 (사용자 확인 완료)
- 관련 스펙: [`2026-08-28-upbit-coin-trading-design.md`](2026-08-28-upbit-coin-trading-design.md) — 같은 "비활성 탭 활성화" 목표의 독립 서브프로젝트

## 배경

`AssetTabs.vue` / `InvestmentTabs.vue`의 채권 탭은 `disabled: true`로 막혀 있고, 백엔드에는 채권 관련 코드가 전혀 없다(`user_settings.asset_order` 기본값에 `bonds` 키가 있는 것과 `uiSettings.js`의 "채권 (추후 지원)" 라벨이 전부다). 이 스펙은 그 탭을 실제 기능으로 만든다.

## 조사 결과 (KIS Open API)

KIS는 **장내채권** 전용 API를 제공한다. 공식 예제 저장소(`koreainvestment/open-trading-api`)의 `examples_llm/domestic_bond/`에서 확인했다.

| 기능 | TR_ID | 엔드포인트 |
|---|---|---|
| 채권 종목 검색 | `CTPF1114R` | `/uapi/domestic-bond/v1/quotations/search-bond-info` |
| 발행 정보 | `CTPF1101R` | `/uapi/domestic-bond/v1/quotations/issue-info` |
| 현재가(시세) | `FHKBJ773400C0` | `/uapi/domestic-bond/v1/quotations/inquire-price` |
| 호가 | `FHKBJ773401C0` | `/uapi/domestic-bond/v1/quotations/inquire-asking-price` |
| 매수 주문 | `TTTC0952U` | `/uapi/domestic-bond/v1/trading/buy` |
| 매도 주문 | `TTTC0958U` | `/uapi/domestic-bond/v1/trading/sell` |
| 잔고 조회 | `CTSC8407R` | `/uapi/domestic-bond/v1/trading/inquire-balance` |
| 매수가능 조회 | `TTTC8910R` | `/uapi/domestic-bond/v1/trading/inquire-psbl-order` |

추가로 존재하는 것(이번 범위 밖): `order_rvsecncl`(정정/취소), `inquire_psbl_rvsecncl`(정정취소가능조회), `inquire_daily_ccld`(일별 체결), `avg_unit`(평균단가), `bond_ccnl`·`bond_index_ccnl`·`bond_asking_price`(실시간 WebSocket), `inquire_daily_itemchartprice`(차트).

**매수 주문 파라미터**(`buy.py` 확인):
`CANO`, `ACNT_PRDT_CD`, `PDNO`(12자리 채권 종목코드, 예 `KR1234567890`), `ORD_QTY2`(주문수량), `BOND_ORD_UNPR`(채권주문단가), `SAMT_MKET_PTCI_YN`(소액시장참여여부 Y/N), `BOND_RTL_MKET_YN`(채권소매시장여부 Y/N), 선택: `IDCR_STFNO`, `MGCO_APTM_ODNO`, `ORD_SVR_DVSN_CD`, `CTAC_TLNO`.

### 중요한 제약

1. **장내채권만 가능하다.** 증권사 앱에서 파는 소액 맞춤형 **장외채권은 공개 API가 없다.** 사용자 확인 완료 — 장내채권 범위로 진행한다.
2. **모의투자 TR_ID가 없다.** 예제의 TR_ID가 전부 실전값이다(`TTTC*`/`CTSC*`/`CTPF*`/`FHKBJ*`). 2026-08 모의투자 제거 이후의 실전 전용 구조와 정확히 맞는다.
3. **호가 체계가 주식과 다르다.** 채권 단가는 액면 10,000원 기준 가격(예: 9,850.5)이며 소수점을 가진다. 주식의 정수 원화 가격을 전제한 기존 UI/DTO를 그대로 재사용할 수 없다.
4. **유동성이 낮다.** 장내채권은 종목에 따라 호가가 비어 있거나 체결이 지연될 수 있다. 이는 KIS/시장 특성이며 우리가 제어할 수 없다 — graceful degrade로 대응한다.

## 결정 사항 (사용자 확인 완료)

| 항목 | 결정 |
|---|---|
| 채권 범위 | 장내채권 (장외채권은 API 부재로 불가) |
| 개발 수준 | 실거래까지 (시세·잔고·매수·매도 전부) |
| AI 자동매매 파이프라인 포함 | **아니오** — 이번은 수동 매매만. ai-agent는 손대지 않는다 |
| 실시간 시세(WebSocket) | **아니오** — REST 폴링만. `bond_ccnl` 등 실시간 TR은 후속 과제 |
| 거래이력 저장 | **별도 테이블**(`bond_trade_history`) — 아래 근거 참조 |

## 범위

### 1. Database

신규 changeset `v1.29-bond-trade-history.yaml`:

- `bond_trade_history` 테이블 신규. 컬럼: `id`, `user_id`(FK→users, ON DELETE CASCADE), `bond_code`(PDNO 12자리), `bond_name`, `order_type`(buy/sell), `order_status`, `quantity`, `order_unit_price`(NUMERIC — 소수점 단가), `order_number`, `ordered_at`, `executed_at`, `created_at`.

**`trade_history` 재사용 대신 별도 테이블을 쓰는 이유**: (a) 채권 단가는 소수점을 갖는데 `trade_history.order_price`는 주식 정수가 전제다, (b) 종목코드가 6자리 숫자가 아니라 12자리 `KR...` 형식이라 `stock_code` 컬럼 의미와 어긋난다, (c) `trade_history`는 Kafka 자동매매의 멱등키(`idempotency_key` UNIQUE)와 결합돼 있는데 채권은 수동 주문만이라 그 제약이 불필요하다. 억지로 한 테이블에 밀어넣으면 두 도메인이 서로의 제약에 묶인다.

### 2. api-server

- `client/KisBondClient.java` — 채권 전용 호출 래퍼. **`KisApiClient`를 그대로 재사용**하되(공통 헤더·rate limit·캐시·실전 도메인 그대로), 채권 TR_ID 상수와 응답 파싱만 이 클래스가 담당한다.
- `service/BondQuoteService.java` — 검색/발행정보/시세/호가(조회 전용, graceful degrade + `notice`).
- `service/BondTradingService.java` — 매수/매도/잔고/매수가능. **국내주식 `TradingService`의 널 가드·예외 전파 패턴을 그대로 따른다**(`requireKisAccountId`, 실패 시 예외 전파 → 4xx/5xx).
- `controller/BondController.java` — `/bonds/**`.
- DTO: `dto/bond/` 하위에 `BondSearchResponse`, `BondPriceResponse`, `BondOrderbookResponse`, `BondBalanceResponse`, `BondOrderableResponse`, `BondOrderRequest`, `BondTradeHistoryResponse`.
- `SecurityConfig`: `/bonds/quotations/**`는 permitAll(시세는 공개, 주식 `/stocks/**`와 동일), 나머지는 AUTH.

**엔드포인트**

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/bonds/search?keyword=` | PUBLIC | 채권 종목 검색 (`CTPF1114R`) |
| GET | `/bonds/{bondCode}/issue-info` | PUBLIC | 발행 정보 (`CTPF1101R`) |
| GET | `/bonds/{bondCode}/price` | PUBLIC | 현재가 (`FHKBJ773400C0`) |
| GET | `/bonds/{bondCode}/orderbook` | PUBLIC | 호가 (`FHKBJ773401C0`) |
| GET | `/bonds/balance` | AUTH | 보유 채권 (`CTSC8407R`) |
| GET | `/bonds/orderable?bondCode=&price=` | AUTH | 매수가능 (`TTTC8910R`) |
| POST | `/bonds/buy` | AUTH | 매수 (`TTTC0952U`) |
| POST | `/bonds/sell` | AUTH | 매도 (`TTTC0958U`) |
| GET | `/bonds/history` | AUTH | `bond_trade_history` 조회 |

주문 요청 시 `SAMT_MKET_PTCI_YN`/`BOND_RTL_MKET_YN`은 소매시장 참여를 기본값(`N`/`Y`)으로 서버가 채운다 — 사용자에게 노출하지 않는다(일반 개인 투자자의 장내채권 매매는 소매시장 경유가 정상 경로).

### 3. web-app

- `services/api.js`에 `bondApi` 추가(기존 12개 객체 옆에 13번째).
- `views/detail/BondSearchView.vue` — 채권 검색(`SearchView`의 패턴 재사용).
- `views/detail/BondDetailView.vue` — 시세·발행정보(만기일·표면금리·신용등급)·호가.
- `views/detail/BondTradingView.vue` — 매수/매도 폼. **단가 입력은 소수점 허용**, 금액 미리보기는 `수량 × 단가 / 100`(액면 100원 기준 환산)로 표시.
- `AssetsView`/`AssetTabs`/`InvestmentTabs`의 `bonds` 탭 `disabled: false`로 해제, 채권 잔고 표시 연동.
- 라우트 추가: `/bonds/search`, `/bonds/:code`, `/bonds/:code/trade`.

### 4. 에러 처리

기존 프로젝트 패턴을 그대로 따른다:
- **조회 경로**(시세/호가/잔고/매수가능): graceful degrade — KIS 실패·유동성 부재로 호가가 비어도 200 + 빈 결과 + `notice`. `KisMaintenanceNotice` 컴포넌트 재사용.
- **주문 경로**(매수/매도): degrade하지 않는다 — 실패 시 예외 전파 → 4xx/5xx + `success=false`. 국내주식 `/trading/buy`와 동일.
- `ErrorCode`: 채권 전용 코드가 필요하면 5000번대(거래) 대역에 추가.

### 5. 테스트

- `BondQuoteServiceTest`, `BondTradingServiceTest` — JUnit 5 + Mockito, `KisApiClient` mock. 기존 `TradingServiceTest` 패턴.
- `KisBondClientTest` — TR_ID·엔드포인트가 위 표와 일치하는지 고정하는 계약 테스트(2026-08 QA에서 실시간 DTO 케이싱 드리프트가 테스트 부재로 오래 방치됐던 전례를 반복하지 않기 위함).
- 채권 단가 소수점 처리(`NUMERIC` 왕복, 금액 환산 계산) 단위 테스트.

## 비범위 (Out of scope)

- 장외채권 (공개 API 없음)
- 정정/취소 주문(`order_rvsecncl`), 일별 체결내역, 평균단가, 차트
- 실시간 WebSocket 시세(`bond_ccnl`/`bond_asking_price`/`bond_index_ccnl`)
- AI 자동매매 파이프라인 연동 (ai-agent 무변경)
- 해외채권

## 리스크

| 리스크 | 완화 |
|---|---|
| 채권 단가 소수점을 주식용 정수 경로에 흘리면 값이 잘린다 | DTO·DB 컬럼을 `BigDecimal`/`NUMERIC`으로 두고, 계산 왕복 단위 테스트로 고정 |
| 장내채권 유동성 부족으로 호가가 비어 화면이 깨져 보임 | 조회 경로 graceful degrade + `notice` 안내 |
| 실전 주문이므로 실제 자금이 움직인다 | 기존 주식과 동일한 수준(수동 확인 후 주문). 실계좌 검증은 구현 후 사용자가 소액으로 확인 |
| KIS 응답 필드명이 예제와 다를 수 있음 | 구현 시 실제 응답을 로그로 확인하고 DTO를 맞춘다. 예제 저장소는 참고 자료이지 계약서가 아니다 |

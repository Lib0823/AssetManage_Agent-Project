# 업비트 코인 거래 기능 설계

- 날짜: 2026-08-28
- 상태: 승인됨 (사용자 확인 완료)
- 관련 스펙: [`2026-08-28-domestic-bond-trading-design.md`](2026-08-28-domestic-bond-trading-design.md) — 같은 "비활성 탭 활성화" 목표의 독립 서브프로젝트
- **실행 순서: 채권 다음.** 채권은 기존 KIS 인프라를 그대로 쓰지만 코인은 신규 거래소 연동이라 리스크가 크다.

## 배경

`AssetTabs.vue` / `InvestmentTabs.vue`의 코인 탭은 `disabled: true`로 막혀 있다. `mockData.js`에 코인 지수 mock이 남아 있고(`HomeView` 폴백), `user_settings.asset_order`·`notifications` 기본값에 `coins` 키가 있지만 실제 기능은 없다.

## 거래소 선정

| 거래소 | 인증 | 문서 | 판단 |
|---|---|---|---|
| **업비트** | JWT (HS256) | 개발자 센터 + 글로벌 문서, 예제 풍부 | **채택** — 국내 최대 거래량, 문서 품질 최상 |
| 빗썸 | JWT (2024-07 Open API 2.0에서 Sign→JWT 전환) | 있음 | 대안. 업비트와 구조 유사 |
| 코인원 | API Key + IP 화이트리스트 필수 | 있음 | IP 화이트리스트가 개발/배포 환경 변경 시 마찰 |

사용자가 업비트를 지정했고, 조사 결과도 업비트가 가장 적합해 그대로 채택한다.

## 조사 결과 (업비트 Open API)

- **Base URL**: `https://api.upbit.com` (한국). 글로벌은 `https://{sg|id|th}-api.upbit.com`.
- **시세(Quotation) API는 인증 불필요** — 마켓 목록, 캔들, 호가, 체결, 티커.
- **자산조회·주문은 인증 필요** — `Authorization: Bearer {JWT}`.
- **API 키 발급 조건**: 회원가입 + 고객확인(본인인증) + 2채널 인증 완료, **PC 웹 환경에서만 발급 가능**, 기능 권한 선택 + **허용 IP 등록 필수**.
- **마켓 코드 형식**: `KRW-BTC` (통화-암호화폐). 원화마켓은 **288개**(2026-08 실측).

### JWT 서명 규격 (사전 검토 실측)

| 항목 | 값 |
|---|---|
| 페이로드 | `access_key`, `nonce`, (파라미터 있을 때) `query_hash`, `query_hash_alg` |
| `query_hash` 대상 | 쿼리스트링(`k=v&k=v`) — **URL 인코딩하지 않은 원문**, **정렬하지 않고 삽입 순서 유지** |
| 해시 | SHA512 → hex 문자열 |
| Secret Key | **Base64 아님.** 디코딩 없이 UTF-8 raw bytes를 그대로 서명 키로 사용 |

**`query_hash` 포함 규칙:**

| 요청 | `query_hash` |
|---|---|
| 파라미터 없는 REST (`GET /v1/accounts`) | **생략** |
| 쿼리 파라미터 있는 GET/DELETE | 포함 |
| **JSON 바디 POST** (`POST /v1/orders`) | **포함 — 단, JSON 문자열이 아니라 바디를 쿼리스트링 형태로 변환한 뒤 해싱** |

> **가장 값비싼 함정**: POST 바디를 `objectMapper.writeValueAsString(body)`로 해싱하면 **주문만 전부 401**이 되고 조회는 멀쩡해서 원인 파악이 늦어진다.

**서명 알고리즘 — HS256을 쓴다(의식적 결정):**

업비트 문서는 HS512를 권장하지만, **jjwt로는 구현할 수 없다.** 사전 검토에서 실측한 결과:
- 업비트 Secret Key는 40자 = 320비트
- jjwt는 RFC 7518 §3.2의 최소 키 길이를 강제한다 → HS512는 512비트 요구 → `SignatureException`
- HS256은 256비트 요구 → 320비트로 충족, 정상 서명됨

업비트 공식 Java 예제들도 HMAC256을 쓴다. **다만 Task 3 착수 시 실제 키로 `GET /v1/accounts`를 1회 호출해 HS256 수용 여부를 먼저 확인한다** — 거부되면 jjwt를 버리고 `javax.crypto.Mac("HmacSHA512")` + 수동 base64url 인코딩으로 직접 구현해야 하며, 이를 나중에 발견하면 여러 Task를 되돌려야 한다.

### Rate limit (사전 검토 실측 — 헤더 확인)

| 대상 | 한도 | 적용 단위 |
|---|---|---|
| 시세(quotation) | 10회/초 | **IP** (헤더 `limit-by-ip: Yes`) |
| 주문 | 12회/초 | **Pocket(계정)** |
| 거래 기본 | 30회/초 | Pocket |
| 초과 시 | **429**, 누적되면 **418**(일시 차단) | — |

> 응답 헤더의 `min=600`은 **Deprecated 고정값이므로 참조하지 않는다.**

**주의 — 위험한 쪽은 주문이 아니라 시세다.** 시세는 IP 단위라 **전체 사용자가 서버 공인 IP 하나의 10/s를 공유**한다. 코인 검색이 288개 마켓을 부르고 상세 화면이 ticker+orderbook을 폴링하면 사용자 수에 선형으로 늘어나 **한 사용자의 화면 조작이 다른 모든 사용자의 시세를 굶긴다.** 반면 주문은 Pocket(사용자별 키) 단위라 사용자끼리 간섭하지 않는다.

### 에러 응답 형태 주의

`error.name`의 타입이 섞인다:
```jsonc
{"error":{"message":"Please check Authorization Header","name":"no_authorization_token"}}  // String
{"error":{"name":404,"message":"Code not found"}}                                          // Integer
```
**`String name`으로 DTO를 고정하면 404/400 응답 역직렬화가 깨진다.** `Object`/`JsonNode`로 받는다.

또한 서버 점검 시 HTML이나 5xx가 올 수 있으므로 **non-JSON 응답과 5xx도 반드시 `UpbitApiException`으로 정규화**한다(파싱 실패를 그대로 터뜨리지 않는다).
- **주문 파라미터**: `market`, `side`(`bid` 매수 / `ask` 매도), `ord_type`(`limit`/`price`/`market`/`best`), `volume`, `price`. 응답은 `uuid`, `state`(`wait`/`watch`/`done`/`cancel`), `executed_volume`, `remaining_volume` 등.

### 주식과 구조가 다른 지점 (주의)

1. **주문 타입 매핑이 비대칭이다.**
   - 지정가: `ord_type=limit` + `volume` + `price` (매수/매도 공통)
   - 시장가 **매수**: `ord_type=price` + `price`(총액) — 수량이 아니라 **금액**을 지정
   - 시장가 **매도**: `ord_type=market` + `volume`(수량)
   주식의 "시장가 = ORD_DVSN 01" 같은 단일 플래그와 다르므로 별도 매핑 계층이 필요하다.
2. **주문 식별자가 UUID다.** KIS의 숫자 주문번호(`ODNO`)와 형식이 다르다.
3. **수량이 소수다.** 0.00012345 BTC 같은 값 — `BigDecimal` 필수.
4. **인증 방식이 완전히 다르다.** KIS는 OAuth 토큰(24h 캐시), 업비트는 요청마다 JWT 생성. `KisApiClient`를 재사용할 수 없고 신규 클라이언트가 필요하다.
5. **IP 화이트리스트.** 서버 공인 IP가 바뀌면 인증이 실패한다 — 운영 시 인지 필요.

## 결정 사항 (사용자 확인 완료)

| 항목 | 결정 |
|---|---|
| 거래소 | 업비트 |
| 개발 수준 | 실거래까지 (시세·자산·매수·매도) |
| 주문 방식 | **지정가 + 시장가 둘 다** |
| 거래 대상 | **원화(KRW) 마켓 전체 검색** — 상위 N개 큐레이션 아님 |
| AI 자동매매 파이프라인 포함 | **아니오** — 수동 매매만. ai-agent 무변경 |
| 실시간 시세(WebSocket) | **아니오** — REST 폴링만 |
| 업비트 계좌 등록 위치 | **설정 화면에서만** — 회원가입 흐름은 건드리지 않는다 |

## 범위

### 1. Database

신규 changeset `v1.30-upbit-account-and-coin-history.yaml`:

- `user_upbit_accounts` — `id`, `user_id`(FK→users, UNIQUE, ON DELETE CASCADE), `access_key`(Jasypt 암호화), `secret_key`(Jasypt 암호화), `is_verified`, `created_at`, `updated_at`. **`user_kis_accounts`와 동일한 패턴**(1:1, Jasypt `PBEWITHHMACSHA512ANDAES_256`).
- `coin_trade_history` — `id`, `user_id`(FK), `market`(예 `KRW-BTC`), `coin_name`, `order_side`(bid/ask), `ord_type`(limit/price/market), **`submitted_state`**, `volume`(NUMERIC(30,8)), `price`(NUMERIC(30,8)), **`executed_volume`**, **`paid_fee`**, `order_uuid`(업비트 UUID, UNIQUE), **`identifier`**(클라이언트 지정 식별자, UNIQUE), `ordered_at`, `created_at`.

별도 테이블을 쓰는 이유는 주문 식별자 형식(UUID), 소수 수량(8자리), 멱등키 제약 불일치 때문이다.

**컬럼 설계 근거 (사전 검토 반영):**

- **`submitted_state`** — `order_state`가 아니다. `POST /v1/orders` 응답의 `state`는 **주문 접수 직후** 값이라 대개 `wait`이며 체결 결과가 아니다. 주문 조회 API가 범위 밖이므로 이 값은 갱신되지 않는다. 컬럼명을 정직하게 두고 **UI에도 "접수 상태"로 표기**한다 — "체결 안 됨"으로 오인하면 사용자가 중복 주문을 낸다.
- **`executed_volume` / `paid_fee`** — 업비트 주문 응답이 주는 값. 나중에 추가하려면 changeset이 하나 더 드므로 지금 넣는다.
- **`identifier`** — `POST /v1/orders`가 지원하는 클라이언트 지정 식별자(최대 64자). 네트워크 타임아웃 후 재시도 시 중복 주문을 막는다. 이 프로젝트는 이미 KIS에서 같은 문제를 겪고 `v1.25`로 멱등키를 도입한 이력이 있다.
- **`ordered_at` 타임존** — 업비트 응답의 `created_at`에 오프셋이 붙어 오면 `TIMESTAMP`(타임존 없음) 매핑에서 9시간 오차가 조용히 생긴다. **Task 3에서 실제 키로 첫 호출할 때 원문을 눈으로 확인하고, 오프셋이 있으면 `TIMESTAMPTZ`로 정정**한다(실제 키 없이는 검증 불가).

### 2. api-server

- `client/UpbitApiClient.java` — **신규 HTTP 클라이언트**. JWT 생성(HS256, `access_key`/`nonce`/필요 시 `query_hash` SHA512), 공통 에러 정규화(`UpbitApiException`), rate limit 대응. `KisApiClient`와 별개다.
- `service/UpbitAuthService.java` — `user_upbit_accounts`에서 키 조회 + Jasypt 복호화. **`KisAuthService`의 fail-closed 패턴을 따른다**(복호화 실패 시 평문 폴백 금지).
- `service/CoinQuoteService.java` — 마켓 목록/티커/호가/캔들. 인증 불필요하므로 앱 레벨에서 공유 호출.
- `service/CoinTradingService.java` — 자산조회/매수/매도. 주문 타입 매핑(위 "비대칭" 처리)을 여기서 캡슐화한다.
- `controller/CoinController.java` — `/coins/**`.
- `controller/UserController` 확장 — `GET/PUT /users/upbit-account`(등록·수정). **키 유효성 검증은 저장 시 겸한다.**
- DTO: `dto/coin/` 하위.
- `SecurityConfig`: 공개 시세 경로만 **개별 지정** permitAll, 나머지 AUTH.

> **`POST /auth/validate-upbit-account`를 만들지 않는다.** `SecurityConfig`가 `/auth/**`를 permitAll로 열어 두므로, 그 경로에 두면 **미인증 공개 엔드포인트**가 되어 외부인이 이 서버를 업비트 키 유효성 검사기로 쓸 수 있다. 검증은 `PUT /users/upbit-account` 저장 시 함께 수행한다.

**엔드포인트**

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/coins/markets` | PUBLIC | 원화마켓 목록 (`/v1/market/all?isDetails=true` 필터) |
| GET | `/coins/tickers?markets=A,B,C` | PUBLIC | 현재가 **배치** (`/v1/ticker`) — 아래 정정 참고 |
| GET | `/coins/{market}/orderbook` | PUBLIC | 호가 (`/v1/orderbook`) |
| GET | `/coins/{market}/candles?unit=` | PUBLIC | 캔들 (`/v1/candles/*`) |
| GET | `/coins/accounts` | AUTH | 보유 자산 (`/v1/accounts`) |
| POST | `/coins/buy` | AUTH | 매수 (`/v1/orders`, side=bid) |
| POST | `/coins/sell` | AUTH | 매도 (`/v1/orders`, side=ask) |
| GET | `/coins/history` | AUTH | `coin_trade_history` 조회 |
| GET/PUT | `/users/upbit-account` | AUTH | 업비트 키 등록·수정 |

> **정정 (2026-08-29, 구현 후)**: 이 표의 초판은 단건 `GET /coins/{market}/ticker`를 적었으나, 구현은 **배치 `GET /coins/tickers?markets=A,B,C`** 하나만 제공한다. 자산 화면은 보유 종목 전체의 평가금액을 구해야 하는데, 단건 엔드포인트가 있으면 종목마다 루프를 돌게 되고 **업비트 시세 한도(IP당 10 req/s)를 즉시 소진해 전체 사용자의 시세가 막힌다.** rate limit 관점에서 구현이 옳으므로 스펙을 구현에 맞춘다. Liquibase changeset 번호도 초판의 `v1.30`이 아니라 **`v1.29`**로 확정됐다.

**주문 요청 DTO**는 사용자 의도(`orderType: LIMIT | MARKET`, `side: BUY | SELL`, `quantity`, `price`)를 받고, `CoinTradingService`가 업비트의 `ord_type` 3종으로 변환한다. 프론트가 업비트 내부 규칙을 알 필요 없게 한다.

> `ord_type=price`는 **매수 전용**, `market`은 **매도 전용**이다. 서비스 계층에서 반대 조합이 만들어지지 않도록 방어한다.

**rate limit 대응**: 이미 있는 `client/KisRateLimiter.java`(Redis Lua 원자적 토큰 버킷)를 재사용한다. 그 클래스는 (a) 버킷 키를 자격증명에서 유도하고, (b) 공유 시세 키와 사용자별 매매 키의 버킷을 분리하며, (c) Redis 장애 시 fail-open 하는데 — **업비트의 IP 단위 시세 / Pocket 단위 주문 구조와 정확히 같다.** 새로 만들지 않고 일반화하거나 같은 Lua 스크립트를 쓴다. 버킷은 2종: **시세=고정 IP 버킷**, **주문=access_key 해시 버킷**.

**코인 자산 평가금액**: `GET /v1/accounts`는 보유 수량만 준다. 원화 환산 총자산은 **수량 × 현재가**로 계산하며(KRW 마켓만 다루므로 환율 변환은 불필요), **`/v1/ticker?markets=`의 콤마 구분 다중 조회로 반드시 1회 배치 호출**한다. 종목별 루프를 돌면 IP 10/s를 즉시 소진한다.

### 3. web-app

- `services/api.js`에 `coinApi` 추가.
- `views/settings/` — 업비트 계좌 등록 UI. **`ProfileView`의 KIS 계좌 카드 옆에 업비트 계좌 카드를 추가**하는 형태(별도 화면 신설 아님). 발급 조건(PC 웹, 본인인증, IP 등록)을 안내 문구로 명시.
- `views/detail/CoinSearchView.vue` — 원화마켓 전체 검색. **유의종목 배지 표시** — `isDetails=true`로 받으면 `market_event.warning`(유의종목)과 `caution` 5종(가격급등락·거래량급등·입금량급등·글로벌가격차이·소수계정집중) 플래그가 온다. 2026-08 실측 기준 KRW 288개 중 `warning` 8개, `caution` 20개. 실제 자금이 오가는 화면이므로 업비트 앱과 같이 표시한다(비용은 파라미터 한 글자).
- `views/detail/CoinDetailView.vue` — 시세·호가·캔들 차트(Chart.js 재사용).
- `views/detail/CoinTradingView.vue` — 매수/매도. 시장가 매수는 "총액 입력", 시장가 매도는 "수량 입력"으로 **입력 필드 자체가 바뀐다** — 업비트 규칙을 UI가 정직하게 반영한다.
- `AssetsView`/`AssetTabs`/`InvestmentTabs`의 `coins` 탭 `disabled: false` 해제.
- `mockData.js`의 코인 지수 mock — 실데이터 연동 후 제거 검토.
- 라우트: `/coins/search`, `/coins/:market`, `/coins/:market/trade`.

### 4. 에러 처리

- **업비트 계좌 미등록 사용자**: 시세는 보이되 자산/주문은 "업비트 계좌를 등록해주세요" 안내로 degrade. `KisMaintenanceNotice`와 같은 패턴의 안내 컴포넌트 재사용 또는 확장.
- **조회 경로**: graceful degrade + `notice`.
- **주문 경로**: 실패 시 예외 전파 → 4xx/5xx (주식·채권과 동일).
- **IP 화이트리스트 위반**: 업비트가 특정 에러를 반환하므로 이를 식별해 "서버 IP가 업비트에 등록되지 않았습니다"라는 구체적 안내로 변환한다(일반 401로 뭉개지 않는다).
- `ErrorCode`: 6000번대(코인/외부거래소) 대역 신설.

### 5. 테스트

- `UpbitApiClientTest` — **JWT 생성 로직 단위 테스트가 핵심**(HS256 서명, `query_hash` SHA512). 여기가 틀리면 전부 401이 된다.
- `CoinTradingServiceTest` — 주문 타입 매핑 3종(지정가 / 시장가 매수 = `price`+총액 / 시장가 매도 = `market`+수량)을 각각 고정하는 테스트. 비대칭 매핑이라 회귀 위험이 높다.
- `UpbitAuthServiceTest` — Jasypt 복호화 fail-closed 확인.
- 소수 수량 `BigDecimal` 왕복 테스트.

## 비범위 (Out of scope)

- 원화 외 마켓(BTC 마켓, USDT 마켓)
- 입출금 API
- 주문 취소·정정
- 실시간 WebSocket 시세
- AI 자동매매 파이프라인 연동 (ai-agent 무변경)
- 빗썸·코인원 등 타 거래소

## 리스크

| 리스크 | 완화 |
|---|---|
| JWT 서명·`query_hash` 구현 실수 → 전부 401 | 단위 테스트로 고정. 업비트 공식 문서의 예제 값과 대조 |
| 시장가 매수/매도 파라미터 비대칭을 혼동해 잘못된 주문 전송 | 서비스 계층에서 매핑을 캡슐화 + 3종 매핑 테스트. UI도 입력 필드를 다르게 노출 |
| 서버 IP 변경 시 인증 실패 | 전용 에러 메시지로 원인을 즉시 알 수 있게 함. 운영 문서에 명시 |
| 소수 수량을 double로 다루면 정밀도 손실 | DTO·DB 전 구간 `BigDecimal`/`NUMERIC(30,8)`. 직렬화는 `toPlainString()`(지수표기 방지) |
| 실거래이므로 실제 자금이 움직인다 | 수동 확인 후 주문. 구현 후 소액으로 사용자 검증(업비트 KRW 최소 주문금액 5,000원) |
| **시세 rate limit(IP당 10/s)을 전체 사용자가 공유** | `KisRateLimiter` 재사용해 IP 버킷 관리 + ticker 배치 조회. 주문은 Pocket 단위라 사용자 간 간섭 없음 |
| POST 바디 `query_hash`를 JSON 문자열로 해싱 → 주문만 401 | 쿼리스트링 변환본 해싱을 테스트로 고정 |
| 업비트 점검 시 HTML/5xx 응답으로 파싱 예외 | non-JSON·5xx도 `UpbitApiException`으로 정규화 |
| `submitted_state`를 체결 상태로 오인 → 중복 주문 | 컬럼명·UI 표기를 "접수 상태"로 정직하게, `identifier` 멱등키 병행 |

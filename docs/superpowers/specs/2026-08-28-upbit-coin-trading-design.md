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
- **자산조회·주문은 인증 필요** — `Authorization: Bearer {JWT}`. JWT는 HS256 서명, 파라미터가 있는 요청은 쿼리스트링의 SHA512 해시(`query_hash`)를 페이로드에 포함.
- **API 키 발급 조건**: 회원가입 + 고객확인(본인인증) + 2채널 인증 완료, **PC 웹 환경에서만 발급 가능**, 기능 권한 선택 + **허용 IP 등록 필수**.
- **Rate limit**: 시세 IP당 초당 10회, 주문 초당 8회, 일괄취소 2초당 1회. 분당 제한은 없음.
- **마켓 코드 형식**: `KRW-BTC` (통화-암호화폐).
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
- `coin_trade_history` — `id`, `user_id`(FK), `market`(예 `KRW-BTC`), `coin_name`, `order_type`(bid/ask), `ord_type`(limit/price/market), `order_state`, `volume`(NUMERIC), `price`(NUMERIC), `order_uuid`(업비트 UUID, UNIQUE), `ordered_at`, `created_at`.

별도 테이블을 쓰는 이유는 채권과 동일하다 — 주문 식별자 형식(UUID), 소수 수량, 멱등키 제약 불일치.

### 2. api-server

- `client/UpbitApiClient.java` — **신규 HTTP 클라이언트**. JWT 생성(HS256, `access_key`/`nonce`/필요 시 `query_hash` SHA512), 공통 에러 정규화(`UpbitApiException`), rate limit 대응. `KisApiClient`와 별개다.
- `service/UpbitAuthService.java` — `user_upbit_accounts`에서 키 조회 + Jasypt 복호화. **`KisAuthService`의 fail-closed 패턴을 따른다**(복호화 실패 시 평문 폴백 금지).
- `service/CoinQuoteService.java` — 마켓 목록/티커/호가/캔들. 인증 불필요하므로 앱 레벨에서 공유 호출.
- `service/CoinTradingService.java` — 자산조회/매수/매도. 주문 타입 매핑(위 "비대칭" 처리)을 여기서 캡슐화한다.
- `controller/CoinController.java` — `/coins/**`.
- `controller/UserController` 확장 — `GET/PUT /users/upbit-account`(등록·수정), `POST /auth/validate-upbit-account`(키 유효성 검증).
- DTO: `dto/coin/` 하위.
- `SecurityConfig`: `/coins/quotations/**` permitAll, 나머지 AUTH.

**엔드포인트**

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/coins/markets` | PUBLIC | 원화마켓 목록 (`/v1/market/all` 필터) |
| GET | `/coins/{market}/ticker` | PUBLIC | 현재가 (`/v1/ticker`) |
| GET | `/coins/{market}/orderbook` | PUBLIC | 호가 (`/v1/orderbook`) |
| GET | `/coins/{market}/candles?unit=` | PUBLIC | 캔들 (`/v1/candles/*`) |
| GET | `/coins/accounts` | AUTH | 보유 자산 (`/v1/accounts`) |
| POST | `/coins/buy` | AUTH | 매수 (`/v1/orders`, side=bid) |
| POST | `/coins/sell` | AUTH | 매도 (`/v1/orders`, side=ask) |
| GET | `/coins/history` | AUTH | `coin_trade_history` 조회 |
| GET/PUT | `/users/upbit-account` | AUTH | 업비트 키 등록·수정 |

**주문 요청 DTO**는 사용자 의도(`orderType: LIMIT | MARKET`, `side: BUY | SELL`, `quantity`, `price`)를 받고, `CoinTradingService`가 업비트의 `ord_type` 3종으로 변환한다. 프론트가 업비트 내부 규칙을 알 필요 없게 한다.

### 3. web-app

- `services/api.js`에 `coinApi` 추가.
- `views/settings/` — 업비트 계좌 등록 UI. **`ProfileView`의 KIS 계좌 카드 옆에 업비트 계좌 카드를 추가**하는 형태(별도 화면 신설 아님). 발급 조건(PC 웹, 본인인증, IP 등록)을 안내 문구로 명시.
- `views/detail/CoinSearchView.vue` — 원화마켓 전체 검색.
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
| 소수 수량을 double로 다루면 정밀도 손실 | DTO·DB 전 구간 `BigDecimal`/`NUMERIC` |
| 실거래이므로 실제 자금이 움직인다 | 수동 확인 후 주문. 구현 후 소액으로 사용자 검증 |
| 업비트 rate limit(주문 8/s) 초과 | 수동 매매라 도달 가능성 낮으나, 초과 시 명확한 안내로 degrade |

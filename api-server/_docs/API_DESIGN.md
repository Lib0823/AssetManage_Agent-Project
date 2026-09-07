# API 설계서

> Spring Boot API Server가 제공하는 REST API 명세입니다. 코드에 실제로 존재하는 것만 기술합니다.
>
> 현재 컨트롤러는 **16개(매핑 88개)**이며 이 문서는 그중 **13개**를 다룹니다. 나머지 3개(WebAuthn·StockNews·Internal)는 [미수록 컨트롤러](#미수록-컨트롤러) 절에 참조 문서를 적어 뒀습니다 — **이 문서만으로 전체를 안다고 가정하지 마세요.**

## 목차
1. [공통 규칙](#1-공통-규칙)
2. [응답 포맷 (ApiResponse)](#2-응답-포맷-apiresponse)
3. [에러 처리](#3-에러-처리)
4. [인증 구분](#4-인증-구분)
5. [엔드포인트 목록](#5-엔드포인트-목록)
   - [AuthController (/auth)](#51-authcontroller-auth)
   - [UserController (/users)](#52-usercontroller-users)
   - [AssetController (/assets)](#53-assetcontroller-assets)
   - [TradingController (/trading)](#54-tradingcontroller-trading)
   - [CompanyController (/company)](#55-companycontroller-company)
   - [StockController (/stocks)](#56-stockcontroller-stocks)
   - [FavoriteController (/favorites)](#57-favoritecontroller-favorites)
   - [OverseasController (/overseas)](#58-overseascontroller-overseas)
   - [MarketAnalysisController (/market)](#59-marketanalysiscontroller-market)
   - [MarketDataController (/market)](#510-marketdatacontroller-market)
   - [BondController (/bonds)](#511-bondcontroller-bonds)
   - [CoinController (/coins)](#512-coincontroller-coins)
   - [HealthController (/health)](#513-healthcontroller-health)
6. [관련 문서](#6-관련-문서)

---

## 1. 공통 규칙

| 항목 | 값 |
|------|-----|
| 포트 | `7070` |
| context-path | `/api` |
| 전체 URL 형식 | `http://localhost:7070/api/...` |
| 세션 정책 | STATELESS (서버 세션 없음) |
| 인증 헤더 | `Authorization: Bearer {JWT}` |
| CSRF | 비활성화 |
| CORS 허용 Origin | `localhost:5173`, `localhost:5174`, `localhost:3000` (WebConfig) |

아래 모든 경로는 context-path `/api`를 제외한 상대 경로로 표기합니다. 예: `POST /auth/login` → 실제 호출은 `http://localhost:7070/api/auth/login`.

---

## 2. 응답 포맷 (ApiResponse)

모든 엔드포인트 응답은 `ApiResponse<T>`로 감싸집니다.

```json
{
  "success": true,
  "message": "처리 결과 메시지",
  "data": { }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `success` | boolean | 성공 여부 |
| `message` | String | 처리 결과 메시지 |
| `data` | T | 실제 응답 데이터 (제네릭) |
| `code` | Integer | **실패 응답 전용, 선택.** `ErrorCode`의 숫자 대역(§3). 성공 응답과 코드 미상 실패 응답에는 **키 자체가 나타나지 않음** |

`data` 컬럼의 타입(T)은 각 엔드포인트 표의 "응답 data(T)" 항목을 참고하세요.

> `code`는 `@JsonInclude(NON_NULL)`이 **필드 단위로만** 걸려 있어, 값이 없으면 직렬화에서 생략됩니다. 성공 응답과 `data: null` 실패 응답의 JSON은 종전과 동일하므로 기존 클라이언트는 영향을 받지 않습니다.

---

## 3. 에러 처리

에러도 동일한 `ApiResponse` 구조를 사용하며, `GlobalExceptionHandler`가 처리합니다. 실패 시 `success=false`, `message`에 에러 메시지가 담기고, `ErrorCode`를 아는 경우 `code`에 숫자 대역이 실립니다.

```json
{
  "success": false,
  "message": "주문가능금액이 부족합니다 (요청 100주 / 최대매수 12주, 주문가능현금 840000원)",
  "data": null,
  "code": 5001
}
```

`code`가 필요한 이유: HTTP 상태만으로는 같은 400인 `INSUFFICIENT_BALANCE`(5001)와 `INVALID_TRADE_QUANTITY`(5002)를 구분할 수 없어 클라이언트가 분기할 수 없습니다. 다만 `code`는 **선택 필드**이므로, 클라이언트는 여전히 `success`/HTTP 상태를 1차 판단 기준으로 쓰고 `code`는 세분화가 필요할 때만 보면 됩니다.

ErrorCode 대역 구분:

| 대역 | 도메인 | 예시 |
|------|--------|------|
| 1000s | 공통(common) | `INTERNAL_SERVER_ERROR=1000`, `INVALID_INPUT_VALUE=1001`(validation 실패) |
| 2000s | 인증(auth) | `INVALID_CREDENTIALS=2001`, `INVALID_TOKEN=2002`, `REFRESH_TOKEN_REVOKED=2004` |
| 3000s | 사용자(user) | `USERNAME_DUPLICATE=3002`, `EMAIL_DUPLICATE=3003`, `PASSWORD_MISMATCH=3004`, `PHONE_MISMATCH=3005` |
| 4000s | KIS | `KIS_ACCOUNT_NOT_FOUND=4000`, `KIS_API_SERVER_ERROR=4002`, `KIS_OAUTH_FAILED=4004` |
| 5000s | 거래(trade) | `INSUFFICIENT_BALANCE=5001`, `INVALID_TRADE_QUANTITY=5002`, `INVALID_TRADE_PRICE=5003` |

### 3.1 거래 사전 검증 (5000s)

주문은 부작용이 있으므로 KIS로 보내기 **전에** 서비스 계층에서 막습니다. `@Valid` DTO 검증은 web-app 경로만 커버하고, ai-agent가 태우는 경로(Kafka 컨슈머, 그리고 Deprecated된 `POST /api/internal/users/{userId}/trades/buy`)는 우회하기 때문에 서비스 계층 검증이 필요합니다.

| 검증 | 적용 대상 | ErrorCode | HTTP |
|------|-----------|-----------|------|
| 주문 수량 < 1 또는 null | `/trading/buy`·`/trading/sell`·`/overseas/buy`·`/overseas/sell`·내부 매매 API | `INVALID_TRADE_QUANTITY`(5002) | 400 |
| 매수여력 부족 (KIS `TTTC8908R`의 `max_buy_qty` < 요청 수량, **지정가 주문만**) | `/trading/buy` | `INSUFFICIENT_BALANCE`(5001) | 400 |
| 단가 누락/0 이하 (해외는 지정가 전용) | `/overseas/buy`·`/overseas/sell` | `INVALID_TRADE_PRICE`(5003) | 400 |

> **매수여력 검증은 지정가 주문에만 적용됩니다.** `orderPrice`가 null/0(=시장가 — 실제 주문도 `ORD_DVSN="01"`+`ORD_UNPR="0"`으로 나감)이면 검증 자체를 건너뜁니다. 매수가능조회(`TTTC8908R`)는 항상 `ORD_DVSN="00"`(지정가) 기준으로 조회하므로, price=0으로 그대로 호출하면 "0원 지정가"라는 실제 주문과 무관한 조합을 KIS에 묻게 되고 `max_buy_qty=0`이 정상 응답으로 돌아와 모든 시장가 매수를 차단할 수 있습니다. ai-agent Stage 6는 항상 시장가(price=0)로 주문하므로 이 검증의 실질 적용 대상이 아닙니다 — 최종 판정은 KIS 주문 시점에 이루어집니다.
>
> **Stage 6 경로 주의**: 현재 정규 경로는 Kafka 토픽 `trade.order.requested`이며, `TradeOrderConsumer`가 소비합니다. HTTP 내부 매매 API의 실제 경로는 `POST /api/internal/users/{userId}/trades/buy`·`.../sell`이고 **둘 다 `@Deprecated`** 입니다(동기 HTTP는 실패 시 주문이 영구 유실되어 큐로 대체됨). 문서 이전 판에 있던 `POST /api/internal/trade/buy`는 실재한 적 없는 경로입니다.
>
> **지정가 주문 매수여력 검증은 fail-open**입니다. 매수가능조회가 degrade된 경우(`notice != null` — KIS 장애 등)에는 검증을 건너뜁니다. 조회 실패를 잔고 부족으로 오인해 정상 주문을 막으면 안 되기 때문이며, 최종 판정은 언제나 KIS가 합니다.
>
> **매도 수량**은 보유수량 초과 여부를 KIS가 판정합니다(`rt_cd != 0` → `KIS_API_SERVER_ERROR`).

---

## 4. 인증 구분

`SecurityConfig`의 `permitAll` 경로는 다음과 같으며, 그 외 모든 경로는 인증이 필요합니다.

| 구분 | 경로 |
|------|------|
| PUBLIC (permitAll) | `/health`, `/health/**`, `/auth/**`, `/actuator/**`, `/market/**`, `/company/**`, `/stocks/**`, `/overseas/stocks/**`, `/news/**`, `/ws/**`, `/internal/**` |
| PUBLIC — 채권 시세 (**GET만**) | `/bonds/{bondCode:[A-Za-z0-9]{12}}`, `.../issue-info`, `.../price`, `.../orderbook` |
| PUBLIC — 코인 시세 (**GET만**) | `/coins/markets`, `/coins/tickers`, `/coins/{market:KRW-[A-Z0-9]{1,20}}/orderbook`, `.../candles` |
| AUTH 필요 | `/auth/webauthn/register/**`, `/users/**`, `/assets/**`, `/trading/**`, `/favorites/**`, `/overseas/**`(`/overseas/stocks/**` 제외) 등 그 외 전부 |

`/auth/**`는 광범위한 permitAll이지만 `/auth/webauthn/register/**`가 **그보다 먼저** `.authenticated()`로 매칭되므로 패스키 등록은 로그인 상태에서만 가능합니다(`/auth/webauthn/login/**`는 permitAll).

> ### ⚠️ 채권·코인은 `/**` 로 열지 않았다
>
> `/bonds/**`·`/coins/**` 를 통째로 permitAll 하면 `/bonds/balance`(보유 채권)와
> `/coins/accounts`·`/coins/buy`·`/coins/sell`·`/coins/history` 가 **함께 열린다.** 그래서 공개
> 경로를 개별 패턴으로 지정하고, 종목/마켓 코드 자리에 정규식 제약(`[A-Za-z0-9]{12}`,
> `KRW-[A-Z0-9]{1,20}`)을 걸었다 — 제약이 없으면 와일드카드가 고정 경로까지 매칭할 수 있다.
> 패턴 상수는 `SecurityConfig.PUBLIC_BOND_QUOTE_PATTERNS`/`PUBLIC_COIN_QUOTE_PATTERNS` 이며,
> `SecurityConfigBondPathsTest` 가 `/bonds/balance` 가 공개되지 않음을 고정한다.
> **이 패턴을 느슨하게 고치면 자산·주문 경로가 인증 없이 열린다.**

> ### ⚠️ `/internal/**`과 `/ws/**`는 "인증 없음"이 아니다
>
> - **`/internal/**`** — Spring Security에서는 permitAll이지만, 그 앞단의 **`InternalAuthFilter`가 `X-Internal-Api-Key` 헤더를 fail-closed로 검증**합니다(키가 설정되지 않았으면 전체 거부). 호출자가 사람이 아니라 ai-agent 프로세스라 JWT 대신 공유 키를 쓰는 것이며, permitAll은 "JWT 필터를 태우지 않는다"는 뜻입니다.
> - **`/ws/**`** — WebSocket 핸드셰이크는 헤더를 붙일 수 없어 permitAll로 두고, `JwtHandshakeInterceptor`가 쿼리스트링 `?token={JWT}`를 검증합니다(`type=access`인 토큰만 허용).

AUTH가 필요한 엔드포인트는 `Authorization: Bearer {JWT}` 헤더를 요구하며, 토큰 claims의 `userId` / `kisAccountId`를 서버에서 추출해 사용합니다. `JwtAuthenticationFilter`는 `type=access`인 토큰만 인증 컨텍스트로 승격하므로 리프레시 토큰으로는 보호 리소스에 접근할 수 없습니다. (상세는 [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md))

---

## 5. 엔드포인트 목록

### 5.1 AuthController (/auth)

전체 PUBLIC. 인증 토큰 발급/관리 및 가입/계정 확인을 담당합니다.

| Method | Path | 요청 Body / Param | 응답 data(T) | 설명 |
|--------|------|-------------------|--------------|------|
| POST | `/auth/login` | `LoginRequest{username, password}` | `LoginResponse{accessToken, refreshToken, tokenType="Bearer", expiresIn=3600000, user{id, username, email, name}}` | 로그인. KIS 계정 보유가 필수 |
| POST | `/auth/register` | `RegisterRequest{username(4-20), password(min8), passwordConfirm, email, name, phone, birthDate, kisAccount?{accountNumber, appKey, appSecret}}` | `RegisterResponse{userId, username, email}` (201) | 회원가입. 토큰은 발급하지 않으며 가입 후 별도 로그인 필요 |
| POST | `/auth/reset-password` | `ResetPasswordRequest{username, phone, newPassword, passwordConfirm}` | `Void` | phone이 저장된 값과 일치해야 하며, newPassword는 영문+숫자+특수문자 포함 min8 |
| GET | `/auth/check-username?username=` | query `username` | `CheckAvailabilityResponse{available}` | 아이디 중복 확인 |
| GET | `/auth/check-email?email=` | query `email` | `CheckAvailabilityResponse{available}` | 이메일 중복 확인 |
| POST | `/auth/refresh` | `RefreshTokenRequest{refreshToken}` | `RefreshTokenResponse{accessToken, tokenType, expiresIn}` | 새 access token만 발급, refresh token은 동일 토큰 재사용 |
| POST | `/auth/logout` | `RefreshTokenRequest{refreshToken}` | `Void` | refresh token 폐기(revoke) |
| POST | `/auth/validate-kis-account` | `ValidateKisAccountRequest{appKey, appSecret}` | `ValidateKisAccountResponse{valid, message, errorCode}` | KIS `POST /oauth2/tokenP` 호출로 자격증명 검증 |

### 5.2 UserController (/users)

전체 AUTH 필요. 토큰의 `userId`를 사용합니다.

| Method | Path | 요청 Body | 응답 data(T) | 설명 |
|--------|------|-----------|--------------|------|
| GET | `/users/me` | - | `UserProfileResponse` | 내 프로필 조회 |
| PUT | `/users/me` | `UpdateUserProfileRequest` | `UserProfileResponse` | 내 프로필 수정 |
| DELETE | `/users/me` | - | `Void` | 계정 삭제. `RefreshToken` / `UserKisAccount` / `UserTradeConfig` / `UserSettings` cascade 삭제 |
| GET | `/users/settings` | - | `UserSettingsResponse{assetOrder(JSON), darkMode, autoLogin, notifications(JSON)}` | 사용자 설정 조회 |
| PUT | `/users/settings` | `UpdateUserSettingsRequest` | `UserSettingsResponse` | 사용자 설정 수정 |
| GET | `/users/kis-account` | - | `KisAccountResponse{accountNumber, productCode, isVerified}` | KIS 계정 정보 조회 |
| PUT | `/users/kis-account` | `UpdateKisAccountRequest` | `KisAccountResponse` | KIS 계정 수정. 자격증명 변경 시 `isVerified=false`로 초기화 |
| GET | `/users/trade-config` | - | `TradeConfigResponse{orderAmount, maxHoldings, orderType, isActive}` | 자동매매 설정 조회 |
| PUT | `/users/trade-config` | `UpdateTradeConfigRequest` | `TradeConfigResponse` | 자동매매 설정 수정 |

### 5.3 AssetController (/assets)

전체 AUTH 필요. 토큰의 `kisAccountId`를 사용해 KIS 잔고를 조회합니다.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/assets/holdings` | `Map` | 보유 종목. KIS `TTTC8434R` inquire-balance |
| GET | `/assets/balance` | `Map` | 현금 잔고. holdings 응답의 subset |

### 5.4 TradingController (/trading)

전체 AUTH 필요. 토큰의 `userId` + `kisAccountId`를 사용합니다.

| Method | Path | 요청 Body | 응답 data(T) | 설명 |
|--------|------|-----------|--------------|------|
| POST | `/trading/buy` | `TradeRequest` | `Map` | 매수. KIS `TTTC0802U` order-cash |
| POST | `/trading/sell` | `TradeRequest` | `Map` | 매도. KIS `TTTC0801U` order-cash |
| GET | `/trading/history` | - | `List<TradeHistoryResponse>` | KIS `TTTC0081R` inquire-daily-ccld 최근 3개월. status `PENDING`/`PARTIAL`/`COMPLETED`/`CANCELLED` |
| GET | `/trading/pending-orders` | - | `List<PendingOrderResponse>` | 미체결 주문. `inquire-daily-ccld`(TTTC0081R) 결과에서 PENDING/PARTIAL(잔량>0) 행만 필터링 — 신규 KIS TR 미사용. 예외/빈결과 시 빈 리스트 |
| GET | `/trading/recent` | - | `List<RecentTradeResponse>` | DB `trade_history` 최신 8건. 홈 화면 알림용 |
| GET | `/trading/holdings` | - | `BalanceSummaryResponse` | KIS `TTTC8434R` inquire-balance |
| GET | `/trading/orderable?stockCode=&price=` | query `stockCode`, `price` | `OrderableResponse{stockCode, maxBuyQuantity, orderableCash, notice}` | 매수가능 수량/금액. KIS `TTTC8908R` inquire-psbl-order |

> `PendingOrderResponse{orderNumber, stockCode, stockName, orderType(BUY/SELL), orderQuantity, remainQuantity, orderPrice, orderedAt}`.

> **주문 실패 계약**: `/trading/buy`·`/trading/sell`은 KIS `rt_cd != 0`(주문 거부)이나 빈 응답 시 `KisApiException`을 던지므로 실패가 최상위 `success=false` + 4xx/5xx로 내려갑니다. 클라이언트는 **최상위 `success`/HTTP 상태**로 판단하면 되고, `data` 안의 성공 플래그를 볼 필요가 없습니다. 해외 주문(§5.8)도 동일한 계약입니다. (예외: 예약주문 `/trading/reserved-orders` POST/DELETE는 KIS `rt_cd != 0`도 200 + `data.success=false`로 graceful 반환합니다.)

### 5.5 CompanyController (/company)

전체 PUBLIC, 읽기 전용. KIS 시세(real domain) + DART 데이터를 사용합니다.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/company/{stockCode}/basic-info` | `BasicInfoResponse` | 현재가/시가총액/PER/PBR/52주/개요 |
| GET | `/company/{stockCode}/financials` | `FinancialsResponse` | 연간 재무 + 비율 |
| GET | `/company/{stockCode}/disclosures` | `DisclosuresResponse` | 약 6개월, 최대 20건 (DART) |

### 5.6 StockController (/stocks)

전체 PUBLIC. `stock_master` 카탈로그 검색 + 현재가/호가 조회. 현재가는 공용 quote 헬퍼(KIS `FHKST01010100` inquire-price), 호가는 KIS `FHKST01010200` inquire-asking-price-exp-ccn을 사용하며, quote 비활성 시 가격/호가 필드 null + `notice` 반환(크래시 없음).

| Method | Path | 요청 Param | 응답 data(T) | 설명 |
|--------|------|-----------|--------------|------|
| GET | `/stocks/search?q=&market=` | query `q`, `market`(opt) | `List<StockSearchResponse>{stockCode, stockName, market}` | code prefix OR name contains(ignore-case), 최대 30건. `market=US`면 해외(USD) 종목, 그 외/미지정은 국내(KRW) 종목 검색 |
| GET | `/stocks/{stockCode}/price` | - | `StockPriceResponse{stockCode, currentPrice, changeAmount, changeRate, notice?}` | KIS output 매핑: `stck_prpr`→currentPrice, `prdy_vrss`→changeAmount, `prdy_ctrt`→changeRate |
| GET | `/stocks/{stockCode}/orderbook` | - | `OrderbookResponse{stockCode, currentPrice, asks:[{price,quantity}], bids:[{price,quantity}], notice}` | 실시간 호가(10단계 매도/매수 + 잔량) + 현재가. KIS `FHKST01010200` inquire-asking-price-exp-ccn (quote 도메인). quote 비활성 시 가격/호가 null + notice |

### 5.7 FavoriteController (/favorites)

전체 AUTH 필요. 토큰의 `userId`를 사용합니다. 종목별 현재가는 StockController와 동일한 공용 quote 헬퍼를 재사용합니다.

| Method | Path | 요청 Body | 응답 data(T) | 설명 |
|--------|------|-----------|--------------|------|
| GET | `/favorites` | - | `List<FavoriteResponse>{stockCode, stockName, currentPrice, changeRate, notice?}` | 관심종목 목록 + 종목별 현재가(quote 비활성 시 가격 null + notice) |
| POST | `/favorites` | `AddFavoriteRequest{stockCode}` | `FavoriteResponse` | `stock_master`에서 종목명 해석, unique 충돌 시 멱등 처리 |
| DELETE | `/favorites/{stockCode}` | - | `Void` | 관심종목 삭제 |

### 5.8 OverseasController (/overseas)

해외주식(미국) 현재가/잔고/매수/매도. `/overseas/stocks/**`(현재가)는 PUBLIC, 나머지는 AUTH 필요(토큰의 `userId`). 지정가 전용, 해외 호가는 1호가만, 미국 외 타국가 미지원. 현재가는 quote 도메인 사용.

**조회 경로는 graceful degrade** — 미연동/실패 시에도 HTTP 200 + `success=true` + 빈 결과 + `data.notice`. **주문 경로(`/overseas/buy`, `/overseas/sell`)는 graceful degrade 대상이 아니다** — 국내 `/trading/buy`·`/trading/sell`과 동일하게 실패 시 예외가 전파되어 `success=false` + 4xx/5xx로 내려간다(§3 에러 처리 참고).

| Method | Path | 요청 Body / Param | 응답 data(T) | 설명 | 인증 |
|--------|------|-------------------|--------------|------|------|
| GET | `/overseas/stocks/{symbol}/price?exchange=` | path `symbol`, query `exchange`(opt, 예 `NASD`) | `OverseasPriceResponse` | 해외 현재가상세. KIS `HHDFS76200200`(현재가 `HHDFS00000300`), quote real 도메인. 미연동 시 가격 null + notice | PUBLIC |
| GET | `/overseas/stocks/{symbol}/orderbook?exchange=` | path `symbol`, query `exchange`(opt) | `OverseasOrderbookResponse` | 해외 1호가(asks/bids 각 1단계) + 현재가. 미연동/실패 시 빈 호가 + notice | PUBLIC |
| GET | `/overseas/balance` | - | `OverseasBalanceResponse` | 해외 잔고/보유. KIS `TTTS3012R`. 미지원/실패 시 빈 목록 + notice | AUTH |
| GET | `/overseas/history?exchange=` | query `exchange`(opt) | `OverseasTradeHistoryResponse` | 해외 주문체결내역. KIS `TTTS3035R`. 미지원/실패 시 빈 목록 + notice | AUTH |
| GET | `/overseas/pending-orders?exchange=` | query `exchange`(opt) | `OverseasPendingOrderResponse` | 해외 미체결. KIS `TTTS3018R`. 미지원/실패 시 빈 목록 + notice | AUTH |
| GET | `/overseas/orderable?symbol=&exchange=&price=` | query `symbol`, `exchange`(opt), `price`(opt) | `OverseasOrderableResponse` | 해외 매수가능금액. KIS `TTTS3007R`. 미지원/실패 시 0 + notice | AUTH |
| POST | `/overseas/buy` | `OverseasOrderRequest{symbol, exchange, quantity, price}` | `Map{success:true, orderNumber, symbol, exchange, quantity, price, orderType:"BUY"}` | 미국 매수(지정가 전용). KIS `TTTT1002U`. **실패 시 `success=false` + 에러 상태** | AUTH |
| POST | `/overseas/sell` | `OverseasOrderRequest{symbol, exchange, quantity, price}` | `Map{success:true, orderNumber, symbol, exchange, quantity, price, orderType:"SELL"}` | 미국 매도(지정가 전용). KIS `TTTT1006U`. **실패 시 `success=false` + 에러 상태** | AUTH |

> 해외 TR(`TTTS3012R`/`TTTT1002U`/`TTTT1006U` 등)은 도메인 변환 없이 직접 사용합니다. 상세는 [KIS_API_GUIDE.md](KIS_API_GUIDE.md).

#### 주문 실패 응답 (buy/sell 공통)

클라이언트는 **최상위 `success`**(및 HTTP 상태)만 보면 되고, `data`는 실패 시 `null`이므로 `data.success`를 볼 필요가 없다.

```json
{
  "success": false,
  "message": "해외 주문에 실패했습니다 (msg1..., rt_cd=1)",
  "data": null,
  "code": 4002
}
```

| 실패 원인 | ErrorCode | HTTP |
|-----------|-----------|------|
| `quantity` 누락/1주 미만 | `INVALID_TRADE_QUANTITY`(5002) | 400 |
| `price` 누락/0 이하 (해외는 지정가 전용) | `INVALID_TRADE_PRICE`(5003) | 400 |
| 요청 바디 validation 실패 | - (`MethodArgumentNotValidException`) | 400 |
| 사용자 없음 | `USER_NOT_FOUND` | 404 |
| KIS 계정 미등록 | `KIS_ACCOUNT_NOT_FOUND`(4000) | 404 |
| KIS 4xx (자격증명/파라미터 오류) | `KIS_API_CLIENT_ERROR`(4001) | 400 |
| KIS `rt_cd != 0`, 빈 응답, KIS 5xx | `KIS_API_SERVER_ERROR`(4002) | 503 |
| KIS 네트워크 오류/타임아웃 | `KIS_API_NETWORK_ERROR`(4003) | 503 |

### 5.9 MarketAnalysisController (/market)

전체 PUBLIC. DB에 적재된 AI 분석 결과를 제공합니다. `date`는 선택적 `LocalDate` 파라미터입니다.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/market/summary?date=` | `MarketSummaryResponse` | KOSPI 지수, 수급, 상승/하락 종목 수 |
| GET | `/market/sentiment?date=` | `MarketSentimentResponse` | 감성 점수 + 분포 |
| GET | `/market/decisions?date=` | `MarketDecisionsResponse` | AI 매수/매도 TOP3 |
| GET | `/market/latest-date` | `LatestDateResponse` | 4개 파이프라인 단계가 모두 완료된 가장 최근 날짜 |
| GET | `/market/heatmap?date=` | `MarketHeatmapResponse` | 30종목 × 11 ML feature + 요약 |
| GET | `/market/stock-analysis/{stockCode}?date=` | `StockAnalysisResponse` | 단일 종목 미니 분석 (Bot 카드용) |
| GET | `/market/stock-detail/{stockCode}?date=` | `StockDetailAnalysisResponse` | 3섹션 상세(정량/감성/시계열 D+1~D+5) |

### 5.10 MarketDataController (/market)

전체 PUBLIC. 외부 소스를 사용하며 실패 시 graceful degrade(부분 응답)합니다.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/market/indices` | `IndicesResponse` | 국내 지수. KIS 시세 `FHKUP03500100` |
| GET | `/market/exchange-rates` | `List<ExchangeRateResponse>` | USD/JPY/EUR/CNY. frankfurter.app/ECB, 60초 캐시 |
| GET | `/market/news` | `List<NewsItemResponse>` | 약 8건. RSS(한국경제/매일경제/연합뉴스), 제목 기준 중복 제거 |

> `/market` base 경로는 `MarketAnalysisController`와 `MarketDataController`가 분담합니다. 둘 다 PUBLIC입니다.

### 5.11 BondController (/bonds)

국내 장내채권. **시세 계열 4개는 PUBLIC, 잔고·매도·거래내역은 AUTH**(토큰의 `userId`).

`bondCode`는 **12자리 영숫자**(`KR2033022D33`)로 주식의 6자리 숫자와 다르다. 경로 패턴이 `[A-Za-z0-9]{12}`로 제한돼 있다.

> **검색·매수 엔드포인트가 없다.** KIS 채권 API 18개 중 종목명·키워드로 찾는 API가 하나도 없어서다(2026-08 전수 확인). 검색이 없으면 상세 화면 진입 경로가 없어 매수도 불가능하므로, **진입점을 잔고로 삼는 "보유 조회 + 매도"**로 범위를 잡았다. 상세: [KIS_API_GUIDE.md](KIS_API_GUIDE.md) §4

| Method | Path | 인증 | 요청 | 응답 data(T) | 설명 |
|--------|------|------|------|--------------|------|
| GET | `/bonds/balance` | AUTH | - | `BondBalanceResponse{holdings[], totalBuyAmount, currency, faceValueDivisor, notice?}` | 보유 채권. **종목이 아니라 '매수 로트' 목록**이며 각 행에 매도에 필요한 `buyDate`/`buySeq`/`separateTaxation`이 들어 있다. KIS `CTSC8407R` |
| GET | `/bonds/history` | AUTH | query `startDate`,`endDate`(yyyyMMdd, opt) | `BondTradeHistoryResponse{list[], currency, notice?}` | 생략 시 최근 90일. KIS `CTSC8013R` |
| POST | `/bonds/sell` | AUTH | `BondSellRequest{bondCode, bondName, quantity, unitPrice, buyDate, buySeq, separateTaxation}` | `Map{...}` | **매수 로트 단위 매도.** `buyDate`/`buySeq`/`separateTaxation`은 잔고 응답을 그대로 되돌려 보내는 값이며(사용자 입력 아님) 빠지면 400. KIS `TTTC0958U` |
| GET | `/bonds/{bondCode}` | PUBLIC | - | `BondInfoResponse` | 종목 기본조회. KIS `CTPF1114R` |
| GET | `/bonds/{bondCode}/issue-info` | PUBLIC | - | `BondIssueInfoResponse` | 발행정보. KIS `CTPF1101R` |
| GET | `/bonds/{bondCode}/price` | PUBLIC | - | `BondPriceResponse` | 현재가. KIS `FHKBJ773400C0` |
| GET | `/bonds/{bondCode}/orderbook` | PUBLIC | - | `BondOrderbookResponse` | 호가. KIS `FHKBJ773401C0` |

> 자산 금액은 **매수금액 기준**이다 — KIS 잔고가 평가금액을 주지 않는다. 수량 단위가 미확정이라 예상 금액은 참고용이며, 환산 계수는 `kis.bond.face-value-divisor`(기본 100) 설정값으로 분리돼 있다.

### 5.12 CoinController (/coins)

업비트 원화 마켓 코인. **시세 4개는 PUBLIC, 자산·주문·이력은 AUTH.**

`market`은 `KRW-BTC` 형식이며 경로 패턴이 **`KRW-[A-Z0-9]{1,20}`**로 제한된다 — `BTC-ETH` 같은 비원화 마켓은 403이다. 상세는 [UPBIT_API_GUIDE.md](UPBIT_API_GUIDE.md).

| Method | Path | 인증 | 요청 | 응답 data(T) | 설명 |
|--------|------|------|------|--------------|------|
| GET | `/coins/markets` | PUBLIC | - | `CoinMarketListResponse{markets[], notice?}` | 원화마켓 전체(288개 안팎). `warning`/`cautions` 유의종목 플래그 포함 |
| GET | `/coins/tickers` | PUBLIC | query `markets`(콤마 구분) | `List<CoinTickerResponse>` | **배치 전용. 단건 엔드포인트는 의도적으로 없다** — 종목마다 호출하면 IP당 10 req/s 한도를 즉시 소진해 전체 사용자의 시세가 막힌다 |
| GET | `/coins/{market}/orderbook` | PUBLIC | - | `CoinOrderbookResponse` | 호가 |
| GET | `/coins/{market}/candles` | PUBLIC | query `unit`(days/weeks/months 또는 분봉 1·3·5·10·15·30·60·240), `count` | `CoinCandleListResponse{market, unit, candles[], notice?}` | 그 외 `unit`은 400 |
| GET | `/coins/accounts` | AUTH | - | `List<CoinAccountResponse>` | 보유 자산. **평가금액 필드가 없다** — `/coins/tickers` 배치 1회로 `balance × tradePrice` 환산 |
| POST | `/coins/buy` | AUTH | `CoinOrderRequest` | `CoinOrderResponse` | side=bid |
| POST | `/coins/sell` | AUTH | `CoinOrderRequest` | `CoinOrderResponse` | side=ask |
| GET | `/coins/history` | AUTH | - | `List<CoinTradeHistoryResponse>` | `coin_trade_history` 조회 |

**`CoinOrderRequest`** — `{market, orderType: "LIMIT"|"MARKET", quantity?, price?, idempotencyKey?}`

**⚠️ 시장가는 매수/매도의 입력 필드가 다르다**(업비트 규격). 잘못 보내면 400(`6005 INVALID_COIN_ORDER`):

| 주문 | 업비트 `ord_type` | `quantity` | `price` |
|------|------------------|-----------|---------|
| 지정가(매수·매도) | `limit` | 수량 | 단가 |
| **시장가 매수** | `price` | 미사용 | **총액(원)** |
| **시장가 매도** | `market` | **수량** | 미사용 |

**`CoinOrderResponse`** — `{orderUuid, market, side, ordType, submittedState, volume, price, executedVolume, remainingVolume, paidFee, identifier, orderedAt, duplicate}`

> **`submittedState`는 "접수 상태"이지 체결 상태가 아니며 갱신되지 않는다.** 주문 조회 API가 이 기능 범위 밖이라 영원히 그대로다 — "체결됨"으로 표시하면 사용자가 중복 주문을 낸다.
>
> `idempotencyKey`(≤64자)는 업비트 `identifier`로 전송된다. 생략하면 서버가 UUID를 만드는데, **매번 새 값이라 재시도를 막지 못한다** — 실제 방어는 클라이언트가 재시도 때 같은 키를 다시 보낼 때만 작동한다. 중복이 억제되면 `duplicate: true`.

**업비트 키 등록**은 UserController에 있다: `GET`/`PUT /users/upbit-account` → `UpbitAccountResponse{id, registered, accessKeyMasked, secretKeyRegistered, isVerified, verificationNotice, createdAt, updatedAt}`. **Secret Key는 어떤 응답에도 실리지 않는다**(등록 여부 boolean만).

### 5.13 HealthController (/health)

전체 PUBLIC.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/health` | `Map{status, timestamp, version}` | 헬스 체크 |
| GET | `/health/db` | `Map` | PostgreSQL 연결 테스트 |

### 미수록 컨트롤러

아래 3개는 이 문서에 아직 명세가 없다. 이 문서를 "전체 명세"로 신뢰하기 전에 알고 있어야 한다:

| 컨트롤러 | 경로 | 참고 |
|---------|------|------|
| `WebAuthnController` | `/auth/webauthn/**` | [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md) WebAuthn 섹션 |
| `StockNewsController` | `/news`, `/news/{id}` | `stock_news` 테이블 읽기 전용 중계 |
| `InternalController` | `/internal/**` | ai-agent ↔ api-server 내부 호출 |

---

## 6. 관련 문서

- [../README.md](../README.md) — 프로젝트 개요 및 실행 방법
- [ARCHITECTURE.md](ARCHITECTURE.md) — 시스템 아키텍처
- [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md) — JWT 인증/토큰 흐름
- [KIS_API_GUIDE.md](KIS_API_GUIDE.md) — KIS Open API 연동 가이드 (주식·채권)
- [UPBIT_API_GUIDE.md](UPBIT_API_GUIDE.md) — 업비트 Open API 연동 가이드 (코인)
- [STATUS.md](STATUS.md) — 구현 현황

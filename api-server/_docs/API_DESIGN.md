# API 설계서

> Spring Boot API Server가 제공하는 REST API의 전체 명세입니다. 코드에 실제로 존재하는 12개 컨트롤러, 52개 엔드포인트만 기술합니다. (OverseasController 포함)

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
   - [HealthController (/health)](#511-healthcontroller-health)
   - [TestController (/test)](#512-testcontroller-test)
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

`data` 컬럼의 타입(T)은 각 엔드포인트 표의 "응답 data(T)" 항목을 참고하세요.

---

## 3. 에러 처리

에러도 동일한 `ApiResponse` 구조를 사용하며, `GlobalExceptionHandler`가 처리합니다. 실패 시 `success=false`, `message`에 에러 메시지가 담깁니다.

```json
{
  "success": false,
  "message": "에러 메시지",
  "data": null
}
```

ErrorCode 대역 구분:

| 대역 | 도메인 | 예시 |
|------|--------|------|
| 1000s | 공통(common) | - |
| 2000s | 인증(auth) | `INVALID_TOKEN=2002`, `REFRESH_TOKEN_REVOKED=2004` |
| 3000s | 사용자(user) | `USERNAME_DUPLICATE=3002`, `EMAIL_DUPLICATE=3003`, `PASSWORD_MISMATCH=3004`, `PHONE_MISMATCH=3005` |
| 4000s | KIS | `KIS_ACCOUNT_NOT_FOUND=4000`, `KIS_OAUTH_FAILED=4004` |
| 5000s | 거래(trade) | - |

---

## 4. 인증 구분

`SecurityConfig`의 `permitAll` 경로는 다음과 같으며, 그 외 모든 경로는 인증이 필요합니다.

| 구분 | 경로 |
|------|------|
| PUBLIC (permitAll) | `/health`, `/health/**`, `/auth/**`, `/actuator/**`, `/test/**`, `/market/**`, `/company/**`, `/stocks/**`, `/overseas/stocks/**` |
| AUTH 필요 | `/users/**`, `/assets/**`, `/trading/**`, `/favorites/**`, `/overseas/**`(`/overseas/stocks/**` 제외) 등 그 외 전부 |

AUTH가 필요한 엔드포인트는 `Authorization: Bearer {JWT}` 헤더를 요구하며, 토큰 claims의 `userId` / `kisAccountId`를 서버에서 추출해 사용합니다. (상세는 [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md))

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
| GET | `/assets/holdings` | `Map` | 보유 종목. KIS `VTTC8434R` inquire-balance |
| GET | `/assets/balance` | `Map` | 현금 잔고. holdings 응답의 subset |

### 5.4 TradingController (/trading)

전체 AUTH 필요. 토큰의 `userId` + `kisAccountId`를 사용합니다.

| Method | Path | 요청 Body | 응답 data(T) | 설명 |
|--------|------|-----------|--------------|------|
| POST | `/trading/buy` | `TradeRequest` | `Map` | 매수. KIS `VTTC0802U` order-cash |
| POST | `/trading/sell` | `TradeRequest` | `Map` | 매도. KIS `VTTC0801U` order-cash |
| GET | `/trading/history` | - | `List<TradeHistoryResponse>` | KIS `VTTC0081R` inquire-daily-ccld 최근 3개월. status `PENDING`/`PARTIAL`/`COMPLETED`/`CANCELLED` |
| GET | `/trading/pending-orders` | - | `List<PendingOrderResponse>` | 미체결 주문. `inquire-daily-ccld`(VTTC0081R) 결과에서 PENDING/PARTIAL(잔량>0) 행만 필터링 — 신규 KIS TR 미사용. 예외/빈결과 시 빈 리스트 |
| GET | `/trading/recent` | - | `List<RecentTradeResponse>` | DB `trade_history` 최신 8건. 홈 화면 알림용 |
| GET | `/trading/holdings` | - | `BalanceSummaryResponse` | KIS `VTTC8434R` inquire-balance |
| GET | `/trading/orderable?stockCode=&price=` | query `stockCode`, `price` | `OrderableResponse{stockCode, maxBuyQuantity, orderableCash, notice}` | 매수가능 수량/금액. KIS `VTTC8908R` inquire-psbl-order (모의 trading 도메인) |

> `PendingOrderResponse{orderNumber, stockCode, stockName, orderType(BUY/SELL), orderQuantity, remainQuantity, orderPrice, orderedAt}`.

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

해외주식(미국) 현재가/잔고/매수/매도. `/overseas/stocks/**`(현재가)는 PUBLIC, 나머지는 AUTH 필요(토큰의 `userId`). 모든 경로는 graceful degrade — 미연동/실패 시에도 200 + `notice`(주문은 `data.success=false`). 모의 지정가 전용, 해외 호가·실시간 시세 미지원, 미국 외 타국가 미지원. 현재가는 real quote 도메인 사용.

| Method | Path | 요청 Body / Param | 응답 data(T) | 설명 | 인증 |
|--------|------|-------------------|--------------|------|------|
| GET | `/overseas/stocks/{symbol}/price?exchange=` | path `symbol`, query `exchange`(opt, 예 `NASD`) | `OverseasPriceResponse` | 해외 현재가상세. KIS `HHDFS76200200`(현재가 `HHDFS00000300`), quote real 도메인. 미연동 시 가격 null + notice | PUBLIC |
| GET | `/overseas/balance` | - | `OverseasBalanceResponse` | 해외 잔고/보유. KIS `VTTS3012R`(모의 trading 도메인). 미지원/실패 시 빈 목록 + notice | AUTH |
| POST | `/overseas/buy` | `OverseasOrderRequest` | `Map` | 미국 매수(지정가 전용). KIS `VTTT1002U`. 실패 시 `data.success=false` + notice | AUTH |
| POST | `/overseas/sell` | `OverseasOrderRequest` | `Map` | 미국 매도(지정가 전용). KIS `VTTT1006U`. 실패 시 `data.success=false` + notice | AUTH |

> `convertTrId`는 국내 `VTTC`↔`TTTC`만 교체하므로 해외 모의 TR은 V 변형(`VTTS3012R`/`VTTT1002U`/`VTTT1006U`)을 직접 사용합니다. 상세는 [KIS_API_GUIDE.md](KIS_API_GUIDE.md).

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

### 5.11 HealthController (/health)

전체 PUBLIC.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/health` | `Map{status, timestamp, version}` | 헬스 체크 |
| GET | `/health/db` | `Map` | PostgreSQL 연결 테스트 |

### 5.12 TestController (/test)

전체 PUBLIC, 개발 전용.

| Method | Path | 응답 data(T) | 설명 |
|--------|------|--------------|------|
| GET | `/test/bcrypt/{password}` | `Map` | BCrypt 해시 생성 + 검증. 개발 유틸리티 |

---

## 6. 관련 문서

- [../README.md](../README.md) — 프로젝트 개요 및 실행 방법
- [ARCHITECTURE.md](ARCHITECTURE.md) — 시스템 아키텍처
- [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md) — JWT 인증/토큰 흐름
- [KIS_API_GUIDE.md](KIS_API_GUIDE.md) — KIS Open API 연동 가이드
- [STATUS.md](STATUS.md) — 구현 현황

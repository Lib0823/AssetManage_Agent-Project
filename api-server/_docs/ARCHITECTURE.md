# ARCHITECTURE

api-server(Spring Boot, Java 21)의 패키지 구조, 레이어 흐름, 도메인/JPA 매핑, 외부 연동, 보안 구성을 코드 기준으로 정리한다. 본 문서는 이전의 `SYSTEM_ARCHITECTURE.md`를 대체한다.

## 목차
1. [모듈 책임](#1-모듈-책임)
2. [패키지 구조](#2-패키지-구조)
3. [레이어 흐름](#3-레이어-흐름)
4. [도메인 및 JPA 매핑](#4-도메인-및-jpa-매핑)
5. [외부 연동 (KIS / DART)](#5-외부-연동-kis--dart)
6. [보안 구성](#6-보안-구성)
7. [설정 및 환경 로딩](#7-설정-및-환경-로딩)
8. [예외 처리](#8-예외-처리)

---

## 1. 모듈 책임

api-server는 BFF(Backend For Frontend) 역할을 한다. 프론트엔드(Vue3)는 KIS API를 직접 호출하지 않고 api-server를 경유한다(CORS 제약 + 자격증명 보호).

| 담당 영역 | 내용 |
|-----------|------|
| 사용자 인증/인가 | JWT 발급·검증, 회원가입, 비밀번호 재설정, 리프레시 토큰 관리 |
| 사용자별 KIS 계정 | appKey/appSecret 암호화 저장, KIS OAuth 토큰 캐싱 |
| 매매 실행 | 매수/매도 주문, 잔고·거래내역 조회(KIS 모의투자) |
| 시세·재무 조회 | 종목 기본정보·재무제표·공시(KIS 실전 시세 + DART) |
| 시장/AI 분석 조회 | DB에 적재된 AI 분석 결과(요약·감성·결정·히트맵·예측) 제공 |
| 시장 위젯 | 지수·환율·뉴스(외부 소스 + graceful degrade) |

AI 분석 데이터의 **생성**은 별도 모듈(`ai-agent`, FastAPI 스케줄러)이 담당하며, api-server는 적재된 결과를 **조회**만 한다.

---

## 2. 패키지 구조

루트 패키지: `com.inbeom.apiserver`

```
com.inbeom.apiserver
├── ApiServerApplication.java        # main 진입점
├── controller/   (12)  # REST 엔드포인트
│   ├── AuthController          /auth        (public)
│   ├── UserController          /users       (auth)
│   ├── AssetController         /assets      (auth)
│   ├── TradingController       /trading     (auth)
│   ├── CompanyController       /company     (public)
│   ├── StockController         /stocks      (public)
│   ├── FavoriteController      /favorites   (auth)
│   ├── OverseasController      /overseas    (price public, 매매/잔고 auth) # 해외주식(US) 지정가
│   ├── MarketAnalysisController /market     (public)
│   ├── MarketDataController    /market      (public)
│   ├── HealthController        /health      (public)
│   └── TestController          /test        (public, dev-only)
├── service/      (9)  # 비즈니스 로직
│   ├── AuthService             # 회원가입/로그인/토큰 lifecycle
│   ├── UserService             # 프로필·설정·KIS계정·매매설정 CRUD
│   ├── AssetService            # 보유종목·예수금 (KIS)
│   ├── TradingService          # 매수/매도/거래내역/잔고 (KIS)
│   ├── KisAuthService          # 사용자별 KIS OAuth 토큰 캐시
│   ├── KisQuoteService         # 앱 단위 시세/재무 KIS 토큰 캐시
│   ├── CompanyInfoService      # 종목 기본정보·재무·공시 (KIS 시세 + DART)
│   ├── MarketAnalysisService   # AI 분석 결과 조회 (DB)
│   └── MarketDataService       # 지수·환율·뉴스 (외부 소스)
├── domain/       (6)  # JPA 엔티티
│   ├── User, UserKisAccount, UserTradeConfig
│   ├── UserSettings, TradeHistory, RefreshToken
├── repository/   (7)
│   ├── UserRepository, UserKisAccountRepository, UserTradeConfigRepository
│   ├── UserSettingsRepository, TradeHistoryRepository, RefreshTokenRepository  # JPA
│   └── MarketAnalysisRepository                                                # JdbcTemplate
├── dto/
│   ├── common/   # ApiResponse<T>
│   ├── auth/, user/, trade/, kis/, market/, company/
├── client/       (2)  # 외부 API 래퍼
│   ├── KisApiClient            # KIS Open API
│   └── DartApiClient           # DART (전자공시)
├── config/       (4)
│   ├── SecurityConfig, WebConfig, JasyptConfig, DotenvEnvironmentPostProcessor
├── security/     (3)
│   ├── JwtAuthenticationFilter, CustomUserDetails, CustomUserDetailsService
├── util/
│   └── JwtTokenProvider
└── exception/    (6)
    ├── ErrorCode, BusinessException, GlobalExceptionHandler
    └── KisAccountNotFoundException, KisApiException, UserNotFoundException
```

---

## 3. 레이어 흐름

표준 흐름은 `Controller → Service → (Repository | Client) → 응답`이다. 모든 응답은 `ApiResponse<T>` 봉투(`success`, `message`, `data`)로 감싼다.

**DB 조회 흐름** (예: `GET /users/me`)
```
Vue3 → Controller (JWT에서 userId 추출)
     → UserService → UserRepository → PostgreSQL
     → ApiResponse<UserProfileResponse>
```

**KIS 매매 흐름** (예: `GET /assets/holdings`)
```
Vue3 → AssetController (JWT 검증 → kisAccountId 추출)
     → AssetService → KisAuthService.getKisAccessToken(kisAccountId)
         ├ 캐시 히트: 즉시 토큰 반환 (~50ms)
         └ 캐시 미스: user_kis_accounts 조회 → Jasypt 복호화
                      → KIS POST /oauth2/tokenP → 토큰 캐시 저장 (~500ms)
     → KisApiClient (TR_ID VTTC8434R) → KIS API
     → ApiResponse<Map>
```

**AI 분석 조회 흐름** (예: `GET /market/heatmap`)
```
Vue3 → MarketAnalysisController
     → MarketAnalysisService → MarketAnalysisRepository (JdbcTemplate, multi-JOIN)
         → PostgreSQL (stock_filter_score, ai_trade_decision, news_analysis, prophet_forecast)
     → ApiResponse<MarketHeatmapResponse>   # 부분 실패 시 null/빈 응답으로 degrade
```

`open-in-view: false`로 설정되어 있으므로 지연 로딩 접근은 서비스 트랜잭션 경계 안에서 수행한다.

---

## 4. 도메인 및 JPA 매핑

JPA 설정은 `ddl-auto: validate`이며 스키마는 Liquibase가 관리한다. 컬럼 네이밍 전략은 `CamelCaseToUnderscoresNamingStrategy`(camelCase 필드 → snake_case 컬럼)다.

| 엔티티 | 테이블 | 핵심 필드 | 관계 |
|--------|--------|-----------|------|
| `User` | `users` | `username`(uniq), `email`(uniq), `password`(BCrypt), `name`, `phone`, `birthDate` | `@OneToOne` ← UserKisAccount |
| `UserKisAccount` | `user_kis_accounts` | `accountNumber`(uniq), `accountProductCode`(기본 `01`), `appKey`/`appSecret`(Jasypt 암호화), `isVerified` | `@OneToOne` → User (`user_id`) |
| `UserTradeConfig` | `user_trade_config` | `orderAmount`(기본 1000000), `maxHoldings`(기본 10), `orderType`(기본 `market`), `isActive`(기본 false, 자동매매 ON/OFF) | `@OneToOne` → User |
| `UserSettings` | `user_settings` | `userId`(uniq, FK 아님), `assetOrder`(JSON), `darkMode`, `autoLogin`, `notifications`(JSON) | 수동 join |
| `TradeHistory` | `trade_history` | `orderNumber`(KIS ODNO), `stockCode`, `stockName`, `orderType`(BUY/SELL), `orderStatus`, `quantity`, `executedQuantity`, `orderPrice`, `executedPrice`, `orderedAt`, `executedAt` | `@ManyToOne` → User |
| `RefreshToken` | `refresh_tokens` | `token`(uniq, 500자), `expiresAt`, `revokedAt`(nullable) | `@ManyToOne` → User |

**암호화 필드**: `UserKisAccount.appKey`, `UserKisAccount.appSecret`는 `.env`/DB에 `ENC(...)` 형태로 저장된다. 복호화는 KIS API 호출 직전 `KisAuthService`에서 수행하며, 복호화 실패 시 평문으로 fallback한다(MVP 임시 동작).

**`TradeHistory` 메모**: 실시간 거래내역(`GET /trading/history`)은 KIS API에서 직접 조회한다. DB의 `trade_history` 테이블은 홈 화면 알림용 최근 거래(`GET /trading/recent`)와 이력 참조 용도다.

리포지토리 중 `MarketAnalysisRepository`만 `JpaRepository`가 아닌 `JdbcTemplate` 기반이며, AI 분석 4개 테이블을 JOIN하는 복합 조회 쿼리를 담는다.

---

## 5. 외부 연동 (KIS / DART)

KIS 연동은 **두 개의 분리된 자격증명 경로**를 쓴다. 상세는 [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) 참고.

| 경로 | 도메인 | 자격증명 | 관리 서비스 | 토큰 캐시 |
|------|--------|----------|-------------|-----------|
| (A) 매매/잔고 | 모의투자 `openapivts...:29443` (`kis.base-url`) | `user_kis_accounts`(사용자별, Jasypt 암호화) | `KisAuthService` | `Map<Long, KisTokenCache>` (사용자별 in-memory, 24h TTL) |
| (B) 시세/재무 | 실전 `openapi...:9443` (`kis.quote-base-url`) | env `KIS_QUOTE_APP_KEY`/`KIS_QUOTE_APP_SECRET`(앱 단위) | `KisQuoteService` | `AtomicReference<QuoteTokenCache>` (앱 단위, 24h TTL) |

분리 이유: 시세/재무 API는 모의투자 도메인에서 제공되지 않으므로 실전 도메인 + 실전 키로 호출해야 한다. 경로 (B)의 키가 비어 있으면 `isQuoteEnabled()=false`로 시세/재무 필드는 null + notice로 graceful degrade한다.

- **`KisApiClient`**: 공통 HTTP 래퍼. `convertTrId`로 도메인(실전/모의)에 따라 `VTTC↔TTTC` 접두사를 변환하고, 시세용 `FHKST*`/`FHKUP*` TR_ID는 변환하지 않는다. 헤더: `authorization`(Bearer KIS 토큰), `appkey`, `appsecret`, `tr_id`, `custtype=P`. RestTemplate timeout: connect 5s, read 18s.
- **`DartApiClient`**: DART(금융감독원 전자공시). base `https://opendart.fss.or.kr/api`, key는 env `DART_API_KEY`(비우면 `isEnabled()=false` → DART 필드 null). `getCorpCode`(6자리 종목코드 → 8자리 corp 코드, `corpCode.xml` ZIP을 StAX 파싱 후 `ConcurrentHashMap` 캐시), `getCompanyProfile`, `getDisclosureList` 제공.
- **`MarketDataService`** 환율: `frankfurter.app`(ECB, 무키), 뉴스: RSS(한국경제·매일경제·연합뉴스). 외부 실패 시 부분/빈 데이터로 degrade.

주요 TR_ID 매핑:

| 기능 | TR_ID | 도메인 |
|------|-------|--------|
| 잔고/보유종목 조회 | `VTTC8434R` | 모의 |
| 매수 주문 | `VTTC0802U` | 모의 |
| 매도 주문 | `VTTC0801U` | 모의 |
| 거래내역(일별 체결) | `VTTC0081R` | 모의 |
| 현재가/지표 | `FHKST01010100` | 실전 |
| 손익계산서/재무비율/안정성 | `FHKST66430200` / `FHKST66430300` / `FHKST66430600` | 실전 |
| 지수 조회 | `FHKUP03500100` | 실전 |

### 실시간 시세 WebSocket 브리지 (Phase 1)

REST 폴링과 별개로 실시간 호가·체결가는 KIS WebSocket으로 푸시된다. 브라우저는 KIS 소켓을 직접 연결하지 않고 api-server가 브리지(BFF)로 중계한다.

```
Browser ⇄ Spring /ws/realtime ⇄ KIS upstream (ws://ops.koreainvestment.com:21000 real | :31000 mock)
```

- 접속키는 REST 토큰이 아닌 `approval_key`(`POST /oauth2/Approval`)를 사용하며, 시세용 앱 단위 자격증명(경로 (B))으로 발급한다.
- 업스트림 구독은 JSON 프레임(`header.tr_type` 1=등록/2=해제, `body.input.tr_id`/`tr_key`)으로 전송하고, KIS `PINGPONG` 제어 프레임은 그대로 echo해 연결을 유지한다.
- 브라우저는 핸드셰이크 시 JWT를 쿼리 파라미터로 전달: `/ws/realtime?token={JWT}`(WebSocket 핸드셰이크가 `Authorization` 헤더를 싣기 어려워 `?token=` 사용). `approval_key`/appkey/appsecret은 서버에만 존재.
- **Phase 1 범위**: 호가(국내 `H0STASP0` / 미국 `HDFSASP0`) + 체결가(국내 `H0STCNT0` / 미국 `HDFSCNT0`). **체결통보(`H0STCNI0`/`H0GSCNI0`)는 Phase 2 보류.**
- 상세 TR·프레임·URL은 [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) §5 참고.

---

## 6. 보안 구성

`SecurityConfig` 기준.

| 항목 | 값 |
|------|-----|
| 세션 정책 | `STATELESS` (HTTP 세션 미사용, JWT 전용) |
| CSRF | 비활성 (REST API) |
| 비밀번호 인코더 | `BCryptPasswordEncoder` |
| 필터 순서 | `JwtAuthenticationFilter`를 `UsernamePasswordAuthenticationFilter` 앞에 배치 |
| 권한 | 모든 사용자 `ROLE_USER` (`CustomUserDetails`) |

**permitAll(인증 불필요) 경로**: `/health`, `/health/**`, `/auth/**`, `/actuator/**`, `/test/**`, `/market/**`, `/company/**`. 그 외(`/users/**`, `/assets/**`, `/trading/**`)는 모두 인증 필요.

**JWT 요약** (상세: [AUTHENTICATION_FLOW.md](./AUTHENTICATION_FLOW.md)):
- 라이브러리 jjwt 0.12.3, HMAC-SHA. secret은 `jwt.secret`(env `JWT_SECRET`).
- access token: claims `subject=username` + `userId` + `kisAccountId`, TTL 1시간.
- refresh token: claims `subject=username`만, TTL 24시간. `refresh_tokens` 테이블에 저장하며 사용자당 활성 토큰 1개(재로그인 시 기존 토큰 revoke).
- 클라이언트는 `Authorization: Bearer {JWT}` 헤더로 호출. KIS 호출 헤더(`authorization`/`appkey`/`appsecret`)와는 별개다.

CORS는 `WebConfig`에서 구성: origin `localhost:5173`/`5174`(Vue dev), `localhost:3000`(Nginx prod), credentials 허용, preflight max-age 3600s.

---

## 7. 설정 및 환경 로딩

- **`application.yml`**: 서버 포트 `7070`, context-path `/api`, Jackson timezone `Asia/Seoul`, HikariCP 풀 설정, JPA `validate`, Liquibase context `mvp`, JWT·Jasypt·KIS·DART·CORS·로깅 설정.
- **`DotenvEnvironmentPostProcessor`**: 부팅 시 `.env`를 Spring PropertySource로 로드(최저 우선순위, OS 환경 변수가 우선). 탐색 순서: `-Ddotenv.path` → 작업 디렉터리 `.env` → `api-server/.env` → 상위 6단계까지 `<dir>/.env`·`<dir>/api-server/.env`. `KEY=VALUE` 형식, `export` 접두사·`#` 주석·따옴표 처리. 실패해도 부팅을 막지 않는다.
- **`JasyptConfig`**: `PooledPBEStringEncryptor`, 알고리즘 `PBEWITHHMACSHA512ANDAES_256`, 1000 iterations, `RandomIvGenerator`, base64 출력. password는 env `JASYPT_PASSWORD`.

---

## 8. 예외 처리

`GlobalExceptionHandler`(`@RestControllerAdvice`)가 예외를 `ApiResponse`(success=false) 형태로 통일해 응답한다.

| 예외 | HTTP 상태 |
|------|-----------|
| `BusinessException` | `ErrorCode.getHttpStatus()` |
| `BadCredentialsException` | 401 |
| `MethodArgumentNotValidException` | 400 (필드별 오류 맵) |
| `IllegalArgumentException` / `MethodArgumentTypeMismatchException` | 400 |
| 그 외 `Exception` | 500 |

**`ErrorCode` 코드 대역**: 1000번대 공통, 2000번대 인증(`INVALID_TOKEN=2002`, `REFRESH_TOKEN_REVOKED=2004`), 3000번대 사용자(`USERNAME_DUPLICATE=3002`, `EMAIL_DUPLICATE=3003`, `PASSWORD_MISMATCH=3004`, `PHONE_MISMATCH=3005`), 4000번대 KIS(`KIS_ACCOUNT_NOT_FOUND=4000`, `KIS_OAUTH_FAILED=4004`), 5000번대 매매.

`KisApiException`은 정적 팩토리(`clientError`/`serverError`/`networkError`/`oauthFailed`)로 KIS 호출 실패를 분류한다.

---

## 관련 문서
- [README.md](./README.md) — 문서 인덱스 및 빠른 시작
- [API_DESIGN.md](./API_DESIGN.md) — 엔드포인트 전체 명세
- [AUTHENTICATION_FLOW.md](./AUTHENTICATION_FLOW.md) — JWT 인증 흐름
- [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) — KIS·DART 연동
- [STATUS.md](./STATUS.md) — 구현 진행 상황

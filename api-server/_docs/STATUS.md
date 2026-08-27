# STATUS

api-server 모듈의 기능·엔드포인트별 구현 진행 상황이다. 코드(`src/main/java/com/inbeom/apiserver/**`) 기준이며, 상태는 다음 기준으로 분류한다.

- **완료**: 컨트롤러·서비스·연동까지 구현되어 호출 가능
- **진행중**: 일부 구현 또는 임시 동작(MVP fallback)이 있어 보완 필요
- **미착수**: 코드에 존재하지 않음

> 참고: 본 표는 "구현 존재 여부"를 나타내며 통합 테스트 통과를 보증하지 않는다. 자동화 테스트 현황은 마지막 절을 참고한다.

---

## 1. 인증 (AuthController / AuthService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 로그인 | `POST /auth/login` | 완료 | KIS 계정 연동이 로그인 전제 조건(없으면 `KisAccountNotFoundException`) |
| 회원가입 | `POST /auth/register` | 완료 | KIS 계정 선택 등록, 기본 `UserTradeConfig` 생성 |
| 비밀번호 재설정 | `POST /auth/reset-password` | 완료 | username+phone 본인확인, 토큰 미발급 방식 |
| 아이디 중복확인 | `GET /auth/check-username` | 완료 | |
| 이메일 중복확인 | `GET /auth/check-email` | 완료 | |
| 토큰 갱신 | `POST /auth/refresh` | 완료 | access token만 재발급, refresh token 재사용 |
| 로그아웃 | `POST /auth/logout` | 완료 | refresh token revoke |
| KIS 계정 검증 | `POST /auth/validate-kis-account` | 완료 | KIS `POST /oauth2/tokenP`로 실검증 |

> **토큰 타입 구분**: access/refresh 토큰은 같은 키로 서명되므로 JWT `type` 클레임(`access`/`refresh`)으로 용도를 구분한다. `JwtAuthenticationFilter`/WebSocket 핸드셰이크는 `type=access`만, `POST /auth/refresh`는 `type=refresh`만 받는다. 이 클레임이 없는 토큰(구버전 발급분)은 양쪽 모두 거부된다 — 하위호환은 두지 않았다.

---

## 1b. WebAuthn / 패스키 (WebAuthnController / WebAuthnService)

Yubico `webauthn-server-core` 기반 생체·패스키 인증. 자격증명은 `webauthn_credentials`에 저장한다.

| 기능 | 엔드포인트 | 인증 | 상태 | 비고 |
|------|-----------|------|------|------|
| 등록 시작 | `POST /auth/webauthn/register/start` | AUTH | 완료 | `SecurityConfig`에서 `/auth/webauthn/register/**`만 `.authenticated()` — 광범위한 `/auth/**` permitAll보다 먼저 매칭된다 |
| 등록 완료 | `POST /auth/webauthn/register/finish` | AUTH | 완료 | |
| 로그인 시작 | `POST /auth/webauthn/login/start` | PUBLIC | 완료 | |
| 로그인 완료 | `POST /auth/webauthn/login/finish` | PUBLIC | 완료 | 성공 시 access/refresh 토큰 발급 |

> **실패 응답 주의**: 서명 검증 실패·assertion 불일치 등 전형적인 생체 로그인 실패는 `INVALID_CREDENTIALS`(2001) → **HTTP 401**로 나간다. 비로그인 상태에서 401이 나오는 엔드포인트이므로, 401을 일괄 "세션 만료"로 처리하는 프런트 인터셉터는 이 경로를 제외해야 한다. (flow 만료·JSON 파싱 실패는 400.)

---

## 2. 사용자 (UserController / UserService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 프로필 조회 | `GET /users/me` | 완료 | |
| 프로필 수정 | `PUT /users/me` | 완료 | 이메일 중복 검사 |
| 회원 탈퇴 | `DELETE /users/me` | 완료 | RefreshToken·KisAccount·TradeConfig·Settings cascade 삭제 |
| 설정 조회 | `GET /users/settings` | 완료 | `assetOrder`/`notifications` JSON 파싱, 없으면 기본값 생성 |
| 설정 수정 | `PUT /users/settings` | 완료 | |
| KIS 계정 조회 | `GET /users/kis-account` | 완료 | 평문 자격증명 미노출 |
| KIS 계정 수정 | `PUT /users/kis-account` | 완료 | 자격증명 변경 시 `isVerified=false` 리셋 |
| 매매 설정 조회 | `GET /users/trade-config` | 완료 | |
| 매매 설정 수정 | `PUT /users/trade-config` | 완료 | `isActive`(자동매매 ON/OFF) 토글 저장 |

---

## 3. 자산 (AssetController / AssetService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 보유종목 조회 | `GET /assets/holdings` | 완료 | KIS `TTTC8434R` |
| 예수금 조회 | `GET /assets/balance` | 완료 | holdings 응답의 잔고 추출 |
| 자산 스냅샷 기록 | `POST /assets/snapshot` | 진행중 | `asset_daily_snapshot` upsert(유저·날짜 1행). **`totalAsset`은 클라이언트가 보낸 값을 그대로 저장하며 KIS 잔고와 대조하지 않는다** — 자산 추이 차트는 그만큼 클라이언트 신뢰 데이터다(본인 데이터만 영향) |
| 자산 추이 조회 | `GET /assets/history?days=` | 완료 | 날짜 오름차순 `List<AssetHistoryResponse>`. `days`는 1~365로 클램프(범위 밖 값은 거부하지 않고 경계로 맞춤) |

---

## 4. 매매 (TradingController / TradingService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 매수 주문 | `POST /trading/buy` | 완료 | KIS `TTTC0802U` |
| 매도 주문 | `POST /trading/sell` | 완료 | KIS `TTTC0801U` |
| 거래내역 조회 | `GET /trading/history` | 완료 | KIS `TTTC0081R`, 최근 3개월. (구버전 `TTTC8001R` 버그 수정됨 — `archive/TRADE_HISTORY_FIX_SUMMARY.md`) |
| 미체결 주문 조회 | `GET /trading/pending-orders` | 완료 | `inquire-daily-ccld`(TTTC0081R) 결과에서 PENDING/PARTIAL 행만 필터링(신규 KIS TR 미사용) → `List<PendingOrderResponse>` |
| 최근 거래(홈) | `GET /trading/recent` | 완료 | DB `trade_history` 최근 8건, KIS 비의존 |
| 보유 잔고 요약 | `GET /trading/holdings` | 완료 | KIS `TTTC8434R` → `BalanceSummaryResponse` |
| 매수가능 조회 | `GET /trading/orderable?stockCode=&price=` | 완료 | KIS `TTTC8908R` inquire-psbl-order → `OrderableResponse{maxBuyQuantity, orderableCash, notice}` |
| 예약주문 등록 | `POST /trading/reserved-orders` | 완료 | KIS `CTSC0008U` |
| 예약주문 목록 | `GET /trading/reserved-orders` | 완료 | KIS `CTSC0004R` → `List<ReservedOrderResponse>` |
| 예약주문 취소 | `DELETE /trading/reserved-orders/{seq}` | 완료 | KIS `CTSC0009U` |

> TradingView 화면이 매수/매도/미체결/호가/매수가능까지 전부 실데이터로 연동 완료(목업 없음).
>
> **주문 구분**: 국내 정규 매수/매도(`/trading/buy`·`/trading/sell`)는 `ORD_DVSN="01"`+`ORD_UNPR="0"`으로 **항상 시장가**를 보낸다. 요청의 `price`는 매수가능금액 사전검증(`verifyBuyingPower`)에만 쓰이고 KIS 주문 본문에는 들어가지 않는다. 지정가가 필요한 경로는 예약주문(market/limit 분기)과 해외 주문(`ORD_DVSN="00"` 지정가 전용)이다.
>
> **거래 원장**: 수동 웹 주문은 KIS 접수 후 `trade_history`에 EXECUTED로 기록된다(`TradingService.placeManualBuy/placeManualSell`, `idempotency_key`는 null). Kafka 경로는 주문 **전에** `TradeOrderIdempotencyService`가 멱등키로 PENDING 행을 선점하므로 기록 주체가 다르다 — `executeBuy`/`executeSell` 자체는 원장에 쓰지 않는다.

---

## 5. 종목 정보 (CompanyController / CompanyInfoService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 기본정보 | `GET /company/{stockCode}/basic-info` | 완료 | KIS 시세(실전) + DART. 시세/DART 키 없으면 해당 필드 null + notice |
| 재무제표 | `GET /company/{stockCode}/financials` | 완료 | KIS 재무(연간), 외부 실패 시 부분 응답 |
| 공시 | `GET /company/{stockCode}/disclosures` | 완료 | DART 약 6개월, 최대 20건 |

---

## 5b. 종목 검색 (StockController / StockService)

PUBLIC(`/stocks/**` permitAll). `stock_master` 카탈로그 검색 + 현재가/호가 조회. 현재가는 공용 quote 헬퍼(`FHKST01010100` inquire-price), 호가는 `FHKST01010200` inquire-asking-price-exp-ccn 사용, quote 비활성 시 가격/호가 null + notice.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 종목 검색 | `GET /stocks/search?q=&market=` | 완료 | code prefix OR name contains(ignore-case), 최대 30건 → `List<StockSearchResponse>`. `market=US`면 해외(USD), 그 외/미지정은 국내(KRW) |
| 인기/상위 종목 | `GET /stocks/top` | 완료 | 검색 화면 초기 노출용 목록 |
| 종목 현재가 | `GET /stocks/{stockCode}/price` | 완료 | `StockPriceResponse{currentPrice, changeAmount, changeRate, notice?}` |
| 실시간 호가 (REST) | `GET /stocks/{stockCode}/orderbook` | 완료 | KIS `FHKST01010200` → `OrderbookResponse{currentPrice, asks[10], bids[10], notice}` (10단계 매도/매수 + 잔량) |

---

## 5b-ws. 실시간 시세 WebSocket 브리지 (Phase 1)

KIS WebSocket을 중계하는 브라우저용 엔드포인트. REST 폴링과 별개로 호가·체결가를 푸시한다. Browser ⇄ Spring `/ws/realtime` ⇄ KIS upstream(`ws://ops.koreainvestment.com:21000`, 실전 고정).

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 실시간 시세 소켓 | `WS /ws/realtime?token={JWT}` | 완료(Phase 1) | 접속키 `approval_key`(`POST /oauth2/Approval`), 구독 프레임 `tr_type`(1=등록/2=해제), `PINGPONG` echo로 연결 유지. JWT는 핸드셰이크 쿼리(`?token=`)로 인증 |

**Phase 1 TR (구현):** 호가 국내 `H0STASP0` / 미국 `HDFSASP0`, 체결가 국내 `H0STCNT0` / 미국 `HDFSCNT0`.
**Phase 2 구현(플래그 `kis.realtime.fills.enabled` 뒤):** 체결통보 국내 `H0STCNI0`, 해외 `H0GSCNI0`. 유저당 KIS 연결(계좌키)·`tr_key`=HTS ID(`user_kis_accounts.hts_id`)·AES-CBC 복호, `/ws/realtime {type:fills}`. 라이브는 HTS ID 설정 + 장중 + 실제 체결 필요. 상세: [KIS_API_GUIDE.md](KIS_API_GUIDE.md) §5.7

> **HARD LIMIT — 라이브 데이터는 장중(정규장)이어야 흐른다**(장외 시간에는 연결은 되나 데이터 푸시 없음). 상세: [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) §5.

---

## 5c. 관심종목 (FavoriteController / FavoriteService)

전체 AUTH 필요. 토큰의 `userId` 사용. 종목별 현재가는 공용 quote 헬퍼 재사용.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 관심종목 목록 | `GET /favorites` | 완료 | 목록 + 종목별 현재가(quote 비활성 시 null + notice) → `List<FavoriteResponse>` |
| 관심종목 추가 | `POST /favorites` | 완료 | `{stockCode}`, `stock_master`에서 종목명 해석, unique 충돌 멱등 처리 |
| 관심종목 삭제 | `DELETE /favorites/{stockCode}` | 완료 | `Void` |

---

## 5d. 해외주식 (OverseasController / OverseasTradingService · OverseasQuoteService)

미국 종목 현재가/잔고/매수/매도. `/overseas/stocks/**`(현재가) PUBLIC, 나머지 AUTH. 조회(현재가/잔고/미체결 등)는 graceful degrade(미연동/실패 시 200 + notice)를 유지하지만, **매수/매도는 국내 주문과 동일하게 실패 시 예외를 던져 4xx/5xx + 최상위 `success=false`, `data=null`로 응답한다**(과거엔 200 + `data.success=false`였으나 국내 패턴에 맞춰 정렬됨 — 상세: `API_DESIGN.md` §3.1, §5.8). 지정가 전용, 미국 외 타국가 미지원. 해외 TR(`TTTS*`/`TTTT*`)은 도메인 변환 없이 직접 사용.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 해외 현재가 | `GET /overseas/stocks/{symbol}/price?exchange=` | 완료 | KIS `HHDFS76200200`(현재가 `HHDFS00000300`), real quote 도메인. 미연동 시 가격 null + notice |
| 해외 잔고 | `GET /overseas/balance` | 완료 | KIS `TTTS3012R` → `OverseasBalanceResponse` |
| 해외 매수 | `POST /overseas/buy` | 완료 | KIS `TTTT1002U` 지정가. 실패 시 예외 전파 → 4xx/5xx + `success=false`(`data=null`) |
| 해외 매도 | `POST /overseas/sell` | 완료 | KIS `TTTT1006U` 지정가. 실패 시 예외 전파 → 4xx/5xx + `success=false`(`data=null`) |
| 해외 호가 | `GET /overseas/stocks/{symbol}/orderbook?exchange=` | 완료 | → `OverseasOrderbookResponse` |
| 해외 거래내역 | `GET /overseas/history` | 완료 | graceful degrade(실패 시 200 + notice) |
| 해외 미체결 | `GET /overseas/pending-orders` | 완료 | graceful degrade |
| 해외 주문가능 | `GET /overseas/orderable` | 완료 | graceful degrade |

> TradingView 해외(US) 지정가 매매, AssetDetailView 해외탭, SearchView 해외 검색이 위 엔드포인트로 실데이터 연동됨.

---

## 6. 시장 분석 (MarketAnalysisController / MarketAnalysisService)

DB에 적재된 AI 분석 결과 조회. 데이터 생성은 `ai-agent` 모듈 담당.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 시장 요약 | `GET /market/summary` | 완료 | `date` 미지정 시 최신일 fallback |
| 시장 감성 | `GET /market/sentiment` | 완료 | |
| AI 매매 결정 | `GET /market/decisions` | 완료 | buy/sell TOP3 |
| 최신 분석일 | `GET /market/latest-date` | 완료 | 4개 파이프라인 단계 모두 완료된 최신일 |
| 히트맵 | `GET /market/heatmap` | 완료 | 30종목 × 11 ML feature + 요약 |
| 종목 분석 요약 | `GET /market/stock-analysis/{stockCode}` | 완료 | Bot 카드용 |
| 종목 상세 분석 | `GET /market/stock-detail/{stockCode}` | 완료 | quant/sentiment/timeseries(D+1~D+5) |

---

## 7. 시장 데이터 (MarketDataController / MarketDataService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 지수 | `GET /market/indices` | 완료 | KIS 시세 `FHKUP03500100`. 일부 해외지수 미제공 가능 |
| 환율 | `GET /market/exchange-rates` | 완료 | frankfurter.app(ECB), 60s 캐시 |
| 경제뉴스 | `GET /market/news` | 완료 | RSS 3종, 약 8건, 제목 중복 제거 |

---

## 7b. 종목 뉴스 (StockNewsController / StockNewsService)

`/news/**` PUBLIC. ai-agent가 `stock_news`에 적재한 뉴스를 중계한다.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 뉴스 목록 | `GET /news` | 완료 | 종목/기간 필터 |
| 뉴스 상세 | `GET /news/{id}` | 완료 | |

---

## 8. 운영/개발 보조

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 헬스 체크 | `GET /health` | 완료 | |
| DB 헬스 체크 | `GET /health/db` | 완료 | |

---

## 8b. 내부 API (InternalController) — ai-agent 전용

`SecurityConfig`상 `/internal/**`은 permitAll이지만 **인증이 없는 것이 아니다** — 별도의 `InternalAuthFilter`가 `X-Internal-Api-Key` 헤더를 fail-closed로 검사한다(키 미설정 시 전체 거부). JWT 대신 공유 키를 쓰는 이유는 호출자가 사람이 아니라 ai-agent 프로세스이기 때문이다.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 자동매매 활성 유저 목록 | `GET /internal/auto-trading/users` | 완료 | Stage 0-1에서 사용 |
| 유저 보유종목 | `GET /internal/users/{userId}/holdings` | 완료 | 보유 종목을 final 30에 강제 포함하기 위해 조회 |
| 매수 실행 | `POST /internal/users/{userId}/trades/buy` | **Deprecated** | Stage 6 정규 경로는 Kafka `trade.order.requested`다. 동기 HTTP 경로는 실패 시 주문이 영구 유실되어 대체됨 |
| 매도 실행 | `POST /internal/users/{userId}/trades/sell` | **Deprecated** | 위와 동일 |

---

## 8c. 인프라 의존성 (Kafka · Redis · TimescaleDB)

| 구성요소 | 용도 | 비고 |
|---------|------|------|
| **Kafka** | ai-agent Stage 6 매매 주문 큐 (`trade.order.requested` / `result` / `dlq`) | `TradeOrderConsumer`가 소비. 멱등성 근거는 `trade_history.idempotency_key` + UNIQUE 제약(`uk_trade_history_idempotency_key`)이며, KIS 호출 **전에** PENDING 행을 선점(claim)한다 |
| **Redis** | KIS API 토큰 버킷(rate limit) + 시세/재무 응답 캐시(stale-if-error) | 인스턴스가 여러 개여도 버킷·캐시가 공유되어야 하므로 프로세스 메모리를 쓰지 않는다. 기본값 capacity 10 / refill 5 per sec |
| **TimescaleDB** | 시계열 테이블 4종의 hypertable 전환 (v1.20~v1.24) | `asset_daily_snapshot`, `stock_filter_score`, `prophet_forecast`, `news_analysis` |

---

## 진행중·미착수 정리

| 항목 | 상태 | 설명 |
|------|------|------|
| KIS 자격증명 복호화 | 완료 | `AuthService.register()`가 등록 시 Jasypt로 암호화해 저장하고, `KisAuthService`는 복호화 실패 시 평문 폴백 없이 `KIS_CREDENTIAL_DECRYPT_FAILED`(4006)로 fail-closed. 프로필 조회(`UserService`)는 실패 시 500 대신 null(빈 입력칸)만 반환해 재등록 가능 |
| 자동매매 실행 | 미착수(범위 외) | `user_trade_config.isActive` 플래그는 api-server가 저장·관리하나, 실제 자동 주문 실행은 `ai-agent` 스케줄러 담당. api-server에는 스케줄링 로직 없음 |
| 멀티유저 운영 | 진행중 | 인증/도메인은 멀티유저 구조이나 MVP 운영은 단일 관리자 중심 |

---

## 자동화 테스트 현황

`src/test/java/com/inbeom/apiserver/` 기준 **테스트 클래스 38개 / `@Test` 566개**(2026-08-26 실측). 서비스 레이어 단위 테스트(Mockito)가 대부분이고, 외부 인프라가 필요한 통합 테스트는 JUnit 태그로 분리되어 있다.

### ⚠️ `./gradlew test`만 돌리면 통합 테스트가 조용히 빠진다

`build.gradle`의 `test` 태스크는 `timescaledb`/`kafka`/`redis` 태그를 `excludeTags`로 **제외**한다. 이 셋은 실제 Docker 컨테이너를 띄우므로 Docker 없는 환경에서 기본 테스트 루프를 막지 않기 위한 분리다. **전체를 검증하려면 4개 태스크를 모두 실행해야 한다.**

| 태스크 | 포함 | Docker | 대상 |
|--------|------|--------|------|
| `./gradlew test` | 태그 없는 전체 | 불필요 | 서비스/유틸/DTO 단위 테스트 |
| `./gradlew redisTest` | `@Tag("redis")` | 필요 | `KisRateLimitAndCacheIntegrationTest` — 토큰 버킷·응답 캐시(stale-if-error) |
| `./gradlew kafkaTest` | `@Tag("kafka")` | 필요 | `TradeOrderConsumerIntegrationTest` — 멱등성·재시도·DLQ |
| `./gradlew timescaledbTest` | `@Tag("timescaledb")` | 필요 | hypertable 전환 무결성 + `TradeExecutionPlanUniqueKeyMigrationTest`(v1.26 유니크 키 교체) |

주요 커버리지: 인증/토큰(`AuthServiceTest`, `JwtTokenProviderTest` — 토큰 타입 클레임 포함), 매매(`TradingServiceTest`, `OverseasTradingServiceTest`), KIS 연동(`KisAuthServiceTest`, `KisApiClient` 계열), 실시간(`KisFillFrameDecryptorTest`, `SubscriptionManagerTest`, `RealtimeMessageSerializationTest` — WebSocket 와이어 키 계약), WebAuthn, 검색/관심종목, 내부 API.

> 이전 문서에 있던 "테스트 클래스 5개 / 컨트롤러·리포지토리 통합 테스트 미작성" 기술은 낡은 정보였다. 또한 "예외 체계 변경 후 일부 테스트가 stale" 이라는 알려진 이슈도 **더 이상 사실이 아니다** — 4개 태스크 전량 재실행 결과 실패 0건이다.

---

## 관련 문서
- [README.md](./README.md) — 문서 인덱스
- [ARCHITECTURE.md](./ARCHITECTURE.md) — 구조·레이어
- [API_DESIGN.md](./API_DESIGN.md) — 엔드포인트 명세

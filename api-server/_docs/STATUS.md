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
| 보유종목 조회 | `GET /assets/holdings` | 완료 | KIS `VTTC8434R` |
| 예수금 조회 | `GET /assets/balance` | 완료 | holdings 응답의 잔고 추출 |

---

## 4. 매매 (TradingController / TradingService)

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 매수 주문 | `POST /trading/buy` | 완료 | KIS `VTTC0802U` |
| 매도 주문 | `POST /trading/sell` | 완료 | KIS `VTTC0801U` |
| 거래내역 조회 | `GET /trading/history` | 완료 | KIS `VTTC0081R`, 최근 3개월. (구버전 `VTTC8001R` 버그 수정됨 — `archive/TRADE_HISTORY_FIX_SUMMARY.md`) |
| 미체결 주문 조회 | `GET /trading/pending-orders` | 완료 | `inquire-daily-ccld`(VTTC0081R) 결과에서 PENDING/PARTIAL 행만 필터링(신규 KIS TR 미사용) → `List<PendingOrderResponse>` |
| 최근 거래(홈) | `GET /trading/recent` | 완료 | DB `trade_history` 최근 8건, KIS 비의존 |
| 보유 잔고 요약 | `GET /trading/holdings` | 완료 | KIS `VTTC8434R` → `BalanceSummaryResponse` |
| 매수가능 조회 | `GET /trading/orderable?stockCode=&price=` | 완료 | KIS `VTTC8908R` inquire-psbl-order → `OrderableResponse{maxBuyQuantity, orderableCash, notice}` |

> TradingView 화면이 매수/매도/미체결/호가/매수가능까지 전부 실데이터로 연동 완료(목업 없음).

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
| 종목 현재가 | `GET /stocks/{stockCode}/price` | 완료 | `StockPriceResponse{currentPrice, changeAmount, changeRate, notice?}` |
| 실시간 호가 (REST) | `GET /stocks/{stockCode}/orderbook` | 완료 | KIS `FHKST01010200` → `OrderbookResponse{currentPrice, asks[10], bids[10], notice}` (10단계 매도/매수 + 잔량) |

---

## 5b-ws. 실시간 시세 WebSocket 브리지 (Phase 1)

KIS WebSocket을 중계하는 브라우저용 엔드포인트. REST 폴링과 별개로 호가·체결가를 푸시한다. Browser ⇄ Spring `/ws/realtime` ⇄ KIS upstream(`ws://ops.koreainvestment.com:21000` real / `:31000` mock).

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 실시간 시세 소켓 | `WS /ws/realtime?token={JWT}` | 완료(Phase 1) | 접속키 `approval_key`(`POST /oauth2/Approval`), 구독 프레임 `tr_type`(1=등록/2=해제), `PINGPONG` echo로 연결 유지. JWT는 핸드셰이크 쿼리(`?token=`)로 인증 |

**Phase 1 TR (구현):** 호가 국내 `H0STASP0` / 미국 `HDFSASP0`, 체결가 국내 `H0STCNT0` / 미국 `HDFSCNT0`.
**Phase 2 구현(국내, 플래그 `kis.realtime.fills.enabled` 뒤):** 체결통보 `H0STCNI0`/`H0STCNI9`. 유저당 KIS 연결(계좌키)·`tr_key`=HTS ID(`user_kis_accounts.hts_id`)·AES-CBC 복호, `/ws/realtime {type:fills}`. 해외 `H0GSCNI0` 보류. 라이브는 HTS ID + 실계좌/모의 스트리밍 + 장중 + 실제 체결 필요. 상세: [KIS_API_GUIDE.md](KIS_API_GUIDE.md) §5.7

> **HARD LIMIT — 라이브 데이터는 실계좌 키 + 장중(정규장)이 모두 필요하다. 모의(mock) 키나 장외 시간에는 스트림이 흐르지 않는다**(연결은 되나 데이터 푸시 없음). 상세: [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) §5.

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

미국 종목 현재가/잔고/매수/매도. `/overseas/stocks/**`(현재가) PUBLIC, 나머지 AUTH. 모든 경로 graceful degrade(미연동/실패 시 200 + notice). 모의 지정가 전용, 해외 호가·실시간 시세·미국 외 타국가 미지원. `convertTrId`가 해외 TR을 변환하지 않으므로 V 변형 직접 사용.

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 해외 현재가 | `GET /overseas/stocks/{symbol}/price?exchange=` | 완료 | KIS `HHDFS76200200`(현재가 `HHDFS00000300`), real quote 도메인. 미연동 시 가격 null + notice |
| 해외 잔고 | `GET /overseas/balance` | 완료 | KIS `VTTS3012R`(모의 trading 도메인) → `OverseasBalanceResponse` |
| 해외 매수 | `POST /overseas/buy` | 완료 | KIS `VTTT1002U` 지정가. 실패 시 `data.success=false` + notice |
| 해외 매도 | `POST /overseas/sell` | 완료 | KIS `VTTT1006U` 지정가. 실패 시 `data.success=false` + notice |

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

## 8. 운영/개발 보조

| 기능 | 엔드포인트 | 상태 | 비고 |
|------|-----------|------|------|
| 헬스 체크 | `GET /health` | 완료 | |
| DB 헬스 체크 | `GET /health/db` | 완료 | |
| BCrypt 유틸 | `GET /test/bcrypt/{password}` | 진행중 | 개발 전용. 운영 배포 전 제거 권장 |

---

## 진행중·미착수 정리

| 항목 | 상태 | 설명 |
|------|------|------|
| KIS 자격증명 복호화 | 진행중 | `KisAuthService`가 Jasypt 복호화 실패 시 평문 fallback(MVP 임시 동작). 모든 계정 암호화 저장으로 정리 필요 |
| `TestController` | 진행중 | 개발용 BCrypt 유틸. 운영에서 노출되지 않도록 제거/비활성 필요 |
| 자동매매 실행 | 미착수(범위 외) | `user_trade_config.isActive` 플래그는 api-server가 저장·관리하나, 실제 자동 주문 실행은 `ai-agent` 스케줄러 담당. api-server에는 스케줄링 로직 없음 |
| 멀티유저 운영 | 진행중 | 인증/도메인은 멀티유저 구조이나 MVP 운영은 단일 관리자 중심 |

---

## 자동화 테스트 현황

`src/test/java/com/inbeom/apiserver/`:

| 테스트 | 대상 |
|--------|------|
| `ApiServerApplicationTests` | 컨텍스트 로드 |
| `AuthServiceTest` | 회원가입/로그인/토큰 로직 |
| `AssetServiceTest` | 잔고·보유종목 조회 |
| `TradingServiceTest` | 매수/매도/거래내역 |
| `KisAuthServiceTest` | KIS 토큰 캐싱/복호화 |

서비스 레이어 단위 테스트 위주이며, 컨트롤러(MockMvc)·리포지토리 통합 테스트는 미작성이다. 실행: `./gradlew test`.

---

## 관련 문서
- [README.md](./README.md) — 문서 인덱스
- [ARCHITECTURE.md](./ARCHITECTURE.md) — 구조·레이어
- [API_DESIGN.md](./API_DESIGN.md) — 엔드포인트 명세

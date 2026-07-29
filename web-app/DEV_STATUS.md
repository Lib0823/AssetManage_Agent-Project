# web-app 화면별 개발 현황

> 작성일: 2026-06-22
> 완료 기준: **API 연동까지 끝나 더 개발할 게 없으면 ⭕ / 일부만 연동(나머지 mock·미구현)이면 🔺 / 화면(UI)만 있고 실 API 미연동이면 ❌**
> 분석 방법: 각 화면이 `@/services/api` 의 실제 백엔드 엔드포인트를 호출해 응답을 렌더링하는지 vs `mockData`·하드코딩·TODO 인지 기준.

## 요약

총 21개 화면 기준. (TransferView 송금 화면은 제거됨 — 주식 MVP 범위 밖)

| 상태 | 개수 | 화면 |
|------|------|------|
| ⭕ 완료 | 17 | LoginView, RegisterFinanceView, ResetPasswordView, ProfileView, BotView, CompanyDetailView, MarketAnalysisView, AssetsView, SettingsView, SearchView, FavoritesView, AssetDetailView, TermsView, TradingView, TransactionsView, HomeView, WelcomeView |
| 🔺 부분 | 1 | RegisterView |
| ❌ UI만 | 3 | SplashView, NewsView, NewsDetailView |

> TradingView는 매수/매도 + 미체결 + 실시간 호가 + 주문가능 수량/금액까지 실데이터. 예약주문만 추후 지원.
> **해외주식(US) 국내 수준 동등화 완료**: 매수/매도·잔고·현재가·검색 + 거래내역(`/overseas/history`)·미체결(`/overseas/pending-orders`)·주문가능(`/overseas/orderable`)·**1호가**(`/overseas/stocks/{symbol}/orderbook`, HHDFS76200100)·실시간 체결가(HDFSCNT0)·**실시간 체결통보**(H0GSCNI0, fills 플래그 뒤)까지. **구조적 미지원: 미국 외 타국가, 10호가 depth(KIS가 미국은 1호가만 제공).** 코인 비활성 유지.
> 전 화면 공통: 코인·예약주문·뉴스는 **추후 지원/제외**(실데이터 없음, 빈 데이터/라벨 처리, mock 주입 금지). 송금은 화면 자체 제거.

---

## 인증 (auth/)

| 화면 | 상태 | 메모 |
|------|:----:|------|
| SplashView | ❌ | 네비게이션만 수행, API 통신 없음 (의도된 스플래시 화면) |
| WelcomeView | ⭕ | Face ID 로그인 **구현**(WebAuthn 패스키 — `/auth/webauthn/login/start\|finish`, usernameless). 지원 기기에서만 버튼 노출(미지원 시 숨김). 실제 생체 프롬프트는 HTTPS+실기기에서만 검증 가능 |
| LoginView | ⭕ | `authApi.login` 호출 + 토큰 저장 + 에러 처리 완비 |
| RegisterView | 🔺 | 아이디/이메일 중복확인은 실 API(`checkUsername`/`checkEmail`). 실제 가입은 다음 단계(RegisterFinanceView)에서 처리 |
| RegisterFinanceView | ⭕ | `validateKisAccount` + `register` 연동, KIS 계정 인증 후 가입 완료 |
| TermsView | ⭕ | mock 토큰 제거 완료. 정보성 화면 + 동의 후 라우팅만 수행(토큰 미발급) |
| ResetPasswordView | ⭕ | `authApi.resetPassword` 연동, 유효성·에러 처리 완비 |

## 설정 (settings/)

| 화면 | 상태 | 메모 |
|------|:----:|------|
| ProfileView | ⭕ | 프로필·KIS계정 조회/수정(`getProfile`,`getKisAccount`,`updateProfile`,`updateKisAccount`,`validateKisAccount`) 전부 실 API |
| SettingsView | ⭕ | `getSettings`/`updateSettings`/`deleteAccount` 실 API. mockSettings 제거, 중립 기본값으로 초기화 후 응답으로 덮어씀 |

## 메인 (main/)

| 화면 | 상태 | 메모 |
|------|:----:|------|
| HomeView | ⭕ | 알림(`/trading/recent`)·뉴스·AI추천·**국내지수**(FHKUP03500100)·**해외지수**(다우/나스닥/S&P500, FHKST03030100)·환율 전부 실 API. 코인 지수만 mock(코인은 제외 대상) |
| AssetsView | ⭕ | `assetApi.getBalance`/`getHoldings` 실 API로 자산요약 구성. mockAssetSummary 제거, `handleRefresh()`는 재로드. 채권·코인 카드는 "추후 지원"(금액 0), 7일 추이는 추세 엔드포인트 없어 숨김 |
| BotView | ⭕ | 보유종목(`getHoldings`), AI분석(`getStockAnalysis` 병렬), 매매설정(`getTradeConfig`/`updateTradeConfig`) 전부 실 API |
| SearchView | ⭕ | `stockApi.search`(종목 카탈로그) + `stockApi.getPrice`(항목별 시세) 실 API. mock 제거. 즐겨찾기 토글은 `favoriteApi.add/remove`. **해외(US) sub-tab 실데이터** — `market=` 파라미터로 미국 종목 검색 |
| FavoritesView | ⭕ | `favoriteApi.list/add/remove` 실 API. 목록 + 현재가/등락률(quote 비활성 시 "—") 표시. mock 차트/뉴스/재무 섹션 제거 |

## 상세 (detail/)

| 화면 | 상태 | 메모 |
|------|:----:|------|
| AssetDetailView | ⭕ | 국내주식 실 API(`/assets/holdings`,`/assets/balance`). 미정의 `stockDetail` 버그 수정(balance summary 기반 값으로 대체). **해외(US) 탭 실데이터** — `/overseas/*` 잔고 연동(USD 표기). 해외 호가/실시간 시세는 미지원 |
| CompanyDetailView | ⭕ | AI분석(`getStockDetail`) + 기본정보/재무/공시(`companyApi` 3종) 전부 실 API |
| TradingView | ⭕ | 매수/매도(`buy`/`sell`) + 미체결(`/trading/pending-orders`) + **실시간 호가**(`stockApi.getOrderbook` → `/stocks/{code}/orderbook`, KIS 10호가) + **주문가능 수량/금액**(`tradingApi.getOrderable` → `/trading/orderable`) 실데이터. 현재가 자동주입·총액 반응형·실시간 날짜. **해외(US) 지정가 매매** `/overseas/*` 연동(모의 지정가 전용, 해외 호가 미지원). 예약주문만 추후 지원 |
| TransactionsView | ⭕ | 거래내역 실 API(`getHistory`) + **기간필터(1주/1개월/3개월) 동작** + 미체결. 배당 수령·현금 입출금은 KIS OpenAPI에 전용 TR 없음(공식 확인) → 요약은 총매수/총매도만(기타 항목 제거) |
| NewsView | ❌ | `mockTopNews` 사용. **뉴스 목록 API 미구현**, 필터링은 클라이언트만 |
| NewsDetailView | ❌ | `mockNewsDetail` 사용. 상세 API 미구현(onMounted에 TODO만) |

## 분석 (analysis/)

| 화면 | 상태 | 메모 |
|------|:----:|------|
| MarketAnalysisView | ⭕ | `marketAnalysisApi` 6종(latest-date/summary/sentiment/decisions/heatmap/stock-detail) 전부 실 API 연동 |

---

## 백엔드 미구현으로 막힌 항목 (참고)

화면을 마저 붙이려면 **api-server에 엔드포인트부터** 있어야 하는 것들:

- **뉴스** (`/news/*`) — NewsView, NewsDetailView, HomeView 뉴스 일부
- **해외주식(US)** — **국내 수준 동등화 완료**(표시·검색·매매·거래내역·미체결·주문가능·1호가·실시간 체결가/체결통보). 구조적 미지원: 미국 외 타국가, 10호가 depth(KIS 미국 1호가만 제공)
- **코인** — 비활성 유지(실데이터 없이 빈 데이터/라벨)
- ~~해외지수 위젯(HomeView)~~ — **해결됨**: 다우/나스닥/S&P500 실연동(`/market/indices` overseas, KIS FHKST03030100). 코인 지수만 mock(제외 대상)
- **배당 수령·현금 입출금 내역** — KIS OpenAPI에 개인 ledger 전용 TR **없음**(공식 확인). 배당은 종목 기준 '배당일정'(HHKDB669102C0)만 존재 → 별도 '배당 캘린더' 기능으로만 가능, 거래내역 기타 금액으로는 불가
- **실시간 시세 소켓 (호가/체결가)** — KIS WebSocket 브리지(`/ws/realtime`) **구현(Phase 1)**. 국내 `H0STASP0`(호가)/`H0STCNT0`(체결가), 미국 `HDFSASP0`/`HDFSCNT0`.
- **실시간 체결통보 (Phase 2, 국내, 플래그 뒤)** — `H0STCNI0`/`H0STCNI9` **구현**(`kis.realtime.fills.enabled`). 유저당 KIS 연결(계좌키)·HTS ID(`tr_key`, `user_kis_accounts.hts_id`)·AES-CBC 복호, 체결 시 토스트 알림. 해외 `H0GSCNI0` 보류.
  - **HARD LIMIT**: 실시간 라이브 데이터는 **실계좌 키 + 장중(정규장 시간)**이 모두 필요. **모의(mock) 키나 장외 시간에는 스트림이 흐르지 않음**(연결은 되나 데이터 푸시 없음). 체결통보는 추가로 **HTS ID 설정 + 실제 체결**이 있어야 동작(모의 스트리밍 지원 불확실).

> 해결됨: 종목 검색(`/stocks/search`, `/stocks/{code}/price`), 즐겨찾기(`/favorites`), 미체결주문(`/trading/pending-orders`), **실시간 호가**(`/stocks/{code}/orderbook` — KIS FHKST01010200), **매수가능 조회**(`/trading/orderable` — KIS VTTC8908R), **실시간 시세 WebSocket 브리지**(`/ws/realtime?token={JWT}` — Phase 1 호가/체결가)가 api-server에 추가됨.
> 제거됨: 송금/이체(TransferView) — 화면·라우트·진입버튼 전부 삭제.

# SCREENS — 화면별 기능 설계서

각 화면(view)의 목적, 주요 컴포넌트, 호출 API, 데이터 흐름(실데이터/Mock), 네비게이션을 정리한다. 모든 내용은 `web-app/src/views/` 코드 기준. 라우트 매핑은 [ARCHITECTURE.md §2](./ARCHITECTURE.md#2-라우팅-구조).

데이터 출처 표기: **실데이터** = api-server/AI 결과 호출, **혼합** = 실데이터 + 부분 하드코딩.

> **`services/mockData.js`는 삭제됐다.** 목업이 남아 있으면 화면이 "동작하는 것처럼" 보여 연동이 깨진 사실이 감춰지기 때문이다. 데이터를 못 받는 구간은 목업 대신 **빈 상태·로딩·점검 안내 배너**로 표현한다.

---

## auth — 인증·온보딩

### SplashView (`/`)
- **목적**: 앱 시작 스플래시. 로고 애니메이션 후 2초 뒤 `/welcome`으로 이동.
- **API**: 없음. **데이터**: 없음. **컴포넌트**: 없음.
- **네비게이션**: `→ /welcome` (onMounted 타이머).

### WelcomeView (`/welcome`)
- **목적**: 앱 소개 + 진입 분기(로그인 / 회원가입).
- **API**: `webauthnApi.loginStart/loginFinish`(생체 로그인, `services/webauthn.js` 경유) — **실데이터**.
- **특이사항**: `isPlatformAuthAvailable()`로 플랫폼 인증기(Face ID/지문/Windows Hello) 지원 여부를 확인해 버튼 노출을 결정한다. usernameless 패스키 로그인이며, 성공 시 `authStore.setAuthData()`로 일반 로그인과 동일하게 토큰을 저장한다.
- **네비게이션**: `→ /login`, `→ /register`, 생체 로그인 성공 `→ /home`.

### LoginView (`/login`)
- **목적**: 아이디/비밀번호 로그인 + 자동로그인 체크박스 + 비밀번호 재설정 링크.
- **API**: `authApi.login({ username, password })` — **실데이터**.
- **상태**: 응답 `data.{accessToken, refreshToken, user}`를 `authStore.setAuthData()`로 저장. 자동로그인 선호는 `localStorage`.
- **네비게이션**: 성공 시 `→ /home`, `→ /reset-password`.

### RegisterView (`/register`) — 가입 1/2단계
- **목적**: 기본 정보(아이디·비밀번호·이름·이메일·전화·생년월일) 입력, 아이디/이메일 중복확인.
- **API**: `authApi.checkUsername(id)`, `authApi.checkEmail(email)` — **실데이터**.
- **상태(Pinia)**: `hasStep1Data`, `setIdCheckResult`, `setEmailCheckResult`, `saveStep1Data`. 마운트 시 `registrationData.step1`·`validation`에서 폼 복원. 전화번호는 숫자만 추출 후 저장.
- **컴포넌트**: `van-popup` + `van-date-picker`(생년월일), `van-icon`, Toast 유틸.
- **네비게이션**: `→ /register/finance`.

### RegisterFinanceView (`/register/finance`) — 가입 2/2단계
- **목적**: KIS(한국투자증권) 계좌(appKey/appSecret) 입력·검증 후 가입 완료. 주식·채권 거래에는 KIS 계좌가 필수. 코인은 **가입 단계가 아니라 `ProfileView`(내 정보)**에서 업비트 키를 따로 등록한다(`PUT /users/upbit-account`).
- **API**: `authApi.validateKisAccount({ appKey, appSecret })` → `authApi.register(전체데이터)` → `authApi.login(...)`(가입 후 자동 로그인) — **실데이터**.
- **상태(Pinia)**: `hasStep1Data` 가드(1단계 데이터 없으면 `/register`로), `registrationData.step1` 병합, `setAuthData`, `clearRegistrationData`.
- **특이사항**: KIS 분당 1회 제한 오류(`EGW00133`) 시 60초 재시도 카운트다운. APP key 발급 포털(`apiportal.koreainvestment.com`)을 새 탭으로 연다. 오류코드 3001/3002 시 `/register`로 복귀.
- **네비게이션**: 성공 → `/home`, 자동로그인 실패 → `/login`, 1단계 누락 → `/register`.

### TermsView (`/terms`)
- **목적**: 약관 동의 화면.
- **API/데이터**: 없음(하드코딩 약관 텍스트).
- **특이사항**: 필수 약관 2개 동의 확인 후 `/home`으로 이동하며, **토큰은 발급하지 않는다**(정보성 게이트). 라우터(`router/index.js`)의 정식 가입 흐름은 RegisterView→RegisterFinanceView이며, TermsView는 그 흐름에 연결돼 있지 않음.
- **네비게이션**: 동의 `→ /home`, 거부 `→ /welcome`.

### ResetPasswordView (`/reset-password`)
- **목적**: 아이디+전화번호로 본인 확인 후 새 비밀번호 설정.
- **API**: `authApi.resetPassword({ username, phone, newPassword, passwordConfirm })` — **실데이터**. 전화번호 하이픈 제거 후 전송.
- **네비게이션**: 성공 `→ /login`.

---

## main — 메인 탭 화면 (하단 네비)

### HomeView (`/home`) — 대시보드 ✅ 실데이터 연동
- **목적**: 시장 개요 대시보드. 주요 지수, 환율, 주요 뉴스, AI 매수 추천, 최근 거래 알림.
- **API** (onMounted, `Promise.allSettled` 병렬): `marketApi.getIndices`, `marketApi.getExchangeRates`, `marketApi.getTopNews`, `marketApi.getAiRecommendations`, `tradingApi.getRecentTrades` — **혼합**.
- **데이터 흐름**: 지수·환율·뉴스·AI추천·알림은 실데이터. KIS 미가용 시 **목업으로 덮지 않고** 점검 안내 배너를 띄운다(`mockData.js`는 삭제됨 — 목업이 남으면 연동이 깨진 사실이 감춰진다). 지수 요청에 20초 타임아웃 명시. 알림은 DB 거래내역(`trade_history`) 기반.
- **컴포넌트**: `AppHeader`, `van-popup`(알림 모달), `van-icon`, 환율 미니 스파크라인(inline SVG).
- **네비게이션**: 뉴스 `→ /news/:id`(또는 외부 링크), AI추천 종목 `→ /company/:symbol?showAiAnalysis=true`.

### AssetsView (`/assets`) — 자산 요약 ✅ 실데이터
- **목적**: 총자산·자산유형별(현금/주식/채권/코인) 비중·자산 추이·자산 카드.
- **API**: `assetApi.getBalance()`, `assetApi.getHoldings()`, `assetApi.getHistory(30)`(30일 추이), `overseasApi.getBalance()`, `marketApi.getExchangeRates()` — **실데이터**.
- **컴포넌트**: `AppHeader`, `KisMaintenanceNotice`, Chart.js `Doughnut`(비중)·`Line`(추이).
- **특이사항**: 자산 카드 표시 순서는 `uiSettings.assetOrder`(설정 화면의 드래그 정렬)를 따른다. KIS 점검/장애는 `isKisOutageError`로 판별해 배너로 graceful degrade. **채권·코인 카드 모두 실연동**(채권=`bondApi.getBalance` 매수 로트 합계, 코인=`coinApi.getAccounts` 수량 × `getTickers` 배치 시세).
- **네비게이션**: `→ /assets/detail?main=<type>` (주식이면 `sub=overseas`).

### BotView (`/bot`) — AI 봇 제어 ✅ 실데이터 연동
- **목적**: 자동매매 봇 상태/평가금, 보유종목별 AI 분석, 봇 설정 모달(최대 투자금·시장 선택).
- **API** (onMounted): `userApi.getTradeConfig`, `userApi.updateTradeConfig`, `tradingApi.getHoldings`, `marketAnalysisApi.getStockAnalysis(symbol)`(보유종목마다 비동기) — **실데이터**.
- **데이터 흐름**: 보유종목(KIS) 로드 → 종목별 AI 분석을 비동기로 채움(`loading: true` 초기화 후 교체). KIS 응답 가공(소수점 처리·환율 2자리). 봇 on/off·설정은 `updateTradeConfig`로 저장.
- **컴포넌트**: `AppHeader`, `van-popup`(설정), 애니메이션 봇 아바타(inline SVG).
- **네비게이션**: `→ /news?symbol=`, `→ /trading/:symbol`, `→ /company/:symbol`, `→ /market-analysis`.

### SearchView (`/search`) — 종목 검색 ✅ 실데이터
- **목적**: 주식(국내/해외) 검색 + 결과 목록 + 관심 토글 + 기업 상세 이동.
- **API**: `stockApi.getTop(market)`(인기 종목), `stockApi.search(q)` / `stockApi.searchOverseas(q)`, `stockApi.getPrice(code)` / `overseasApi.getPrice(code, exchange)`, `favoriteApi.list/add/remove`, `marketApi.getExchangeRates()` — **실데이터**.
- **컴포넌트**: `AppHeader`, `InvestmentTabs`, `KisMaintenanceNotice`.
- **특이사항**: KIS 점검/미가용은 `isKisOutageError`·`isKisUnavailableNotice`로 판별해 배너 처리.
- **네비게이션**: `→ /company/:symbol`.

### FavoritesView (`/favorites`) — 관심/포트폴리오 ✅ 실데이터
- **목적**: 관심종목 캐러셀(가격·차트), 관심 토글, 관심 목록.
- **API**: `favoriteApi.list()`, `favoriteApi.remove(stockCode)`, `overseasApi.getPrice(code, exchangeCode)`, `marketApi.getExchangeRates()` — **실데이터**.
- **컴포넌트**: `AppHeader`, `InvestmentTabs`, `KisMaintenanceNotice`, Chart.js `Line`(가격 추이, 등락 방향에 따라 그라데이션).
- **네비게이션**: `→ /company/:symbol`.

---

## detail — 상세 화면

### AssetDetailView (`/assets/detail`) — 자산 상세 ✅ 실데이터
- **목적**: 보유 주식(국내/해외)·현금 잔고 상세(KIS).
- **API**: `assetApi.getHoldings()`, `assetApi.getBalance()`, `overseasApi.getBalance()`(USD 원본), `marketApi.getExchangeRates()`(USD→KRW 병기) — **실데이터**.
- **실시간**: `useRealtimeStore`로 보유 종목 체결가를 구독해 평가금액을 갱신한다(연결 불가 시 REST 스냅샷 유지).
- **쿼리 파라미터**: `main`, `sub`(탭 상태). **컴포넌트**: `AppHeader`, `AssetTabs`, `van-icon`.
- **네비게이션**: `→ /news?symbol=`, `→ /trading/:symbol`, `→ /company/:symbol`. (송금 진입 버튼은 범위 외로 제거됨)

### CompanyDetailView (`/company/:symbol`) — 기업/AI 상세 ✅ 실데이터
- **목적**: 기업 종합 정보 — AI 분석(정량/센티먼트/시계열), 기본정보, 재무, 공시.
- **API**: `marketAnalysisApi.getStockDetail(symbol)`, `companyApi.getBasicInfo(symbol)`, `companyApi.getFinancials(symbol)`, `companyApi.getDisclosures(symbol)` — **실데이터**.
- **route params**: `:symbol`. **컴포넌트**: `AppHeader`, `van-icon`, `van-popup`, 커스텀 SVG 차트(Prophet 예측, KIS 피처 막대).
- **네비게이션**: `→ /news?symbol=`, `→ /trading/:symbol`.

### TradingView (`/trading/:symbol`) — 매매 ✅ 실데이터
- **목적**: 매수/매도 주문 폼 + 실시간 호가 + 매수가능 조회.
- **API**: `tradingApi.buy(order)`, `tradingApi.sell(order)`(주문 실행), `/stocks/{code}/orderbook`(10단계 호가), `/trading/orderable`(매수가능 수량/금액), `tradingApi.getPendingOrders`(미체결) — **전부 실데이터**(목업 없음). 예약주문은 추후 지원.
- **route params**: `:symbol`(기본값 `005930`). **컴포넌트**: `AppHeader`.
- **네비게이션**: 성공 `→ /transactions`.

### TransactionsView (`/transactions`) — 거래내역 ✅ 실데이터
- **목적**: 3개월 거래내역·미체결/예약 주문·기간별 요약.
- **API**: `tradingApi.getHistory({ timeout: 25000 })` — **실데이터**(KIS 조회 지연 대응 25초 타임아웃).
- **컴포넌트**: `AppHeader`, `InvestmentTabs`.
- **네비게이션**: 미체결 클릭 `→ /trading/:symbol`, 오류 시 `→ /profile`.

### NewsView (`/news`) — 뉴스 목록 ✅ 실데이터
- **목적**: 뉴스 피드(날짜·검색·정렬 필터).
- **API**: `newsApi.getList({ symbol?, date? })` — **실데이터**. `?symbol=` 쿼리로 종목별 필터.
- **컴포넌트**: `AppHeader`, `AssetTabs`, `KisMaintenanceNotice`, 커스텀 검색 SVG.
- **네비게이션**: `→ /news/:id`.

### NewsDetailView (`/news/:id`) — 뉴스 상세 ✅ 실데이터
- **목적**: 기사 본문·메타·태그·이미지·관련 뉴스.
- **API**: `newsApi.getDetail(id)`(본문), `newsApi.getList({ symbol })`(관련 뉴스) — **실데이터**. `route.params.id`를 실제로 사용한다.
- **컴포넌트**: `AppHeader`.
- **네비게이션**: 관련 뉴스 `→ /news/:id`.

### BondDetailView (`/bonds/:code`) — 채권 상세 ✅ 실데이터
- **목적**: 보유 채권의 시세·호가·발행정보 확인 후 매도로 이동.
- **API**: `bondApi.getBondInfo/getIssueInfo/getPrice/getOrderbook(bondCode)` — **실데이터**(시세 계열은 PUBLIC).
- **특이사항**: `bondCode`는 **12자리 영숫자**(`KR2033022D33`)로 주식의 6자리 숫자와 다르다. **검색 진입점이 없다** — KIS에 채권 검색 API가 없어 `AssetsView` 보유 목록이 유일한 진입 경로다.
- **네비게이션**: `→ /bonds/:code/sell`(매도 로트를 쿼리로 전달).

### BondSellView (`/bonds/:code/sell`) — 채권 매도 ✅ 실데이터
- **목적**: 보유 채권을 **매수 로트 단위**로 매도.
- **API**: `bondApi.sell(payload)` — **실데이터**.
- **특이사항**: payload의 `buyDate`/`buySeq`/`separateTaxation`은 **사용자 입력이 아니라 잔고 응답을 그대로 되돌려 보내는 값**이다(빠지면 400). `utils/bond.js`의 `buildBondLotQuery`/`readBondLotQuery`가 이 값을 라우트 쿼리로 왕복시킨다.

### CoinSearchView (`/coins`) — 코인 검색 ✅ 실데이터
- **목적**: 업비트 원화 마켓(288개 안팎) 검색.
- **API**: `coinApi.getMarkets()` — **실데이터**(PUBLIC). 목록이 자주 바뀌지 않아 **받아서 클라이언트에서 필터링**한다.
- **특이사항**: `notice` 필드를 받아 degrade 안내를 띄운다. 유의/주의 플래그를 배지로 노출.
- **네비게이션**: `→ /coins/:market`.

### CoinDetailView (`/coins/:market`) — 코인 상세 ✅ 실데이터
- **목적**: 현재가·호가·캔들 확인 후 매매로 이동.
- **API**: `coinApi.getTickers([market])`(**배치 전용 — 단건 엔드포인트 없음**), `getOrderbook`, `getCandles` — **실데이터**(PUBLIC).
- **특이사항**: 마켓 목록 degrade 시 유의·주의 배지가 조용히 사라지지 않도록 `marketInfoNotice`로 "확인 불가"를 명시한다(2026-08-29 QA 반영).
- **네비게이션**: `→ /coins/:market/trade?side=buy|sell`.

### CoinTradingView (`/coins/:market/trade`) — 코인 매매 ✅ 실데이터
- **목적**: 업비트 원화 마켓 매수/매도.
- **API**: `coinApi.getMarkets/getTickers/getAccounts`, `coinApi.buy/sell(payload)` — **실데이터**.
- **특이사항**: **주문 타입에 따라 입력 필드 자체가 바뀐다** — 지정가는 수량+단가, **시장가 매수는 총액(원)만**, **시장가 매도는 수량만**(업비트 규격). 수량·금액은 사용자가 친 **문자열 그대로** 전송한다(`Number` 왕복 시 소수 8자리가 지수표기로 변질). 멱등키(`idempotencyKey`)는 확인 시점에 만들어 **실패 후 재시도에 같은 값을 재전송**한다. `submittedState`는 접수 상태이며 체결 상태가 아니다.
- **네비게이션**: 접수 후 결과 표시.

> 송금/계좌이체 화면(구 `TransferView` · `/transfer`)은 주식 MVP 범위 밖으로 제거되었다(라우트·진입 버튼 포함).

---

## analysis — 시장 분석

### MarketAnalysisView (`/market-analysis`) — 시장분석 대시보드 ✅ 실데이터
- **목적**: KOSPI 지수, 30종목 피처 히트맵, AI 매매 추천(매수/매도 TOP3), 수급 사분면, 5일 전망, 시장 센티먼트, 펀더멘털.
- **API** (순차): `marketAnalysisApi.getLatestDate()` → `getSummary(date)`, `getSentiment(date)`, `getDecisions(date)`, `getHeatmap(date)` — **실데이터 only**.
- **차트**: **모두 클라이언트 렌더링**(CSS 그리드 히트맵, HTML/CSS 게이지·분포 막대, computed 기반 미니 막대). 서버 PNG 이미지·`/static/charts` 참조 없음. Chart.js·Vant 미사용.
- **컴포넌트**: `AppHeader`만.
- **네비게이션**: 뒤로 `→ /bot`.

---

## settings — 설정

### ProfileView (`/profile`) — 프로필 ✅ 실데이터
- **목적**: 개인정보(이름·이메일·전화·생년월일)·KIS 계좌·비밀번호 재설정·로그아웃.
- **API**: `userApi.getProfile()`, `userApi.getKisAccount()`, `authApi.validateKisAccount(...)`, `userApi.updateProfile(...)`, `userApi.updateKisAccount(...)` — **실데이터**.
- **컴포넌트**: `AppHeader`, `van-calendar`(생년월일), 프로필 inline SVG.
- **네비게이션**: `→ /settings`, `→ /reset-password`, 로그아웃 `→ /welcome`.

### SettingsView (`/settings`) — 설정 🔶 혼합
- **목적**: 자산 우선순위 드래그 정렬, 일반 토글(다크모드·자동로그인), 알림 설정(자산유형별), 회원 탈퇴.
- **API**: `userApi.getSettings()`, `userApi.updateSettings(...)`, `userApi.deleteAccount()` — **실데이터**(기본값으로 초기화 후 마운트 시 서버 설정으로 덮어씀).
- **컴포넌트**: `AppHeader`. 일반 HTML 폼/드래그.
- **네비게이션**: 저장 후 `router.back()`, 탈퇴 후 `→ /welcome`.

---

## 화면 데이터 출처 요약

| 화면 | 출처 | 호출 API(핵심) |
|------|------|----------------|
| Home | ✅ 혼합(실+Mock폴백) | marketApi.*, tradingApi.getRecentTrades |
| Bot | ✅ 실데이터 | userApi.getTradeConfig, tradingApi.getHoldings, marketAnalysisApi.getStockAnalysis |
| MarketAnalysis | ✅ 실데이터 | marketAnalysisApi.getLatestDate/getSummary/getSentiment/getDecisions/getHeatmap |
| CompanyDetail | ✅ 실데이터 | marketAnalysisApi.getStockDetail, companyApi.* |
| Transactions | ✅ 실데이터 | tradingApi.getHistory |
| Trading | 🔶 혼합(실행만 실데이터) | tradingApi.buy/sell |
| AssetDetail | ✅ 실데이터 | assetApi.getHoldings/getBalance, overseasApi.getBalance |
| Settings | ✅ 실데이터 | userApi.getSettings/updateSettings/deleteAccount |
| Profile | ✅ 실데이터 | userApi.*, authApi.validateKisAccount |
| Login/Register/RegisterFinance/ResetPassword | ✅ 실데이터 | authApi.* |
| Assets | ✅ 실데이터 | assetApi.getBalance/getHoldings/getHistory, overseasApi.getBalance |
| Search | ✅ 실데이터 | stockApi.search/getTop/getPrice, overseasApi.getPrice, favoriteApi.* |
| Favorites | ✅ 실데이터 | favoriteApi.list/remove, overseasApi.getPrice |
| News / NewsDetail | ✅ 실데이터 | newsApi.getList, newsApi.getDetail |
| Terms | ⚠️ 플레이스홀더 | (없음, 정식 가입 흐름에 미연결) |
| Splash/Welcome | — | (없음, Welcome은 생체 로그인 진입점) |

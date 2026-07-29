# SCREENS — 화면별 기능 설계서

각 화면(view)의 목적, 주요 컴포넌트, 호출 API, 데이터 흐름(실데이터/Mock), 네비게이션을 정리한다. 모든 내용은 `web-app/src/views/` 코드 기준. 라우트 매핑은 [ARCHITECTURE.md §2](./ARCHITECTURE.md#2-라우팅-구조).

데이터 출처 표기: **실데이터** = api-server/AI 결과 호출, **Mock** = `services/mockData.js`/하드코딩, **혼합** = 실데이터 + Mock 폴백/부분.

---

## auth — 인증·온보딩

### SplashView (`/`)
- **목적**: 앱 시작 스플래시. 로고 애니메이션 후 2초 뒤 `/welcome`으로 이동.
- **API**: 없음. **데이터**: 없음. **컴포넌트**: 없음.
- **네비게이션**: `→ /welcome` (onMounted 타이머).

### WelcomeView (`/welcome`)
- **목적**: 앱 소개 + 진입 분기(로그인 / 회원가입).
- **API/데이터**: 없음(하드코딩 텍스트).
- **네비게이션**: `→ /login`, `→ /register`. Face ID 버튼은 `console.log`만 하는 스텁.

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
- **목적**: KIS(한국투자증권) 계좌(appKey/appSecret) 입력·검증 후 가입 완료. 주식 투자(KIS)는 필수, 코인은 UI상 비활성.
- **API**: `authApi.validateKisAccount({ appKey, appSecret })` → `authApi.register(전체데이터)` → `authApi.login(...)`(가입 후 자동 로그인) — **실데이터**.
- **상태(Pinia)**: `hasStep1Data` 가드(1단계 데이터 없으면 `/register`로), `registrationData.step1` 병합, `setAuthData`, `clearRegistrationData`.
- **특이사항**: KIS 분당 1회 제한 오류(`EGW00133`) 시 60초 재시도 카운트다운. APP key 발급 포털(`apiportal.koreainvestment.com`)을 새 탭으로 연다. 오류코드 3001/3002 시 `/register`로 복귀.
- **네비게이션**: 성공 → `/home`, 자동로그인 실패 → `/login`, 1단계 누락 → `/register`.

### TermsView (`/terms`)
- **목적**: 약관 동의 화면.
- **API/데이터**: 없음(하드코딩 약관 텍스트).
- **특이사항**: 동의 시 `localStorage`에 **mock-token**을 넣고 `/home`으로 이동(실제 인증 아님 — 플레이스홀더 흐름). 라우터(`router/index.js`)의 정식 가입 흐름은 RegisterView→RegisterFinanceView이며, TermsView는 그 흐름에 연결돼 있지 않음.
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
- **데이터 흐름**: 지수·환율·뉴스·AI추천·알림은 실데이터. 단 해외/코인 지수 등은 KIS 미가용 시 `mockMarketIndices` 폴백. 지수 요청에 20초 타임아웃 명시. 알림은 DB 거래내역(`trade_history`) 기반.
- **컴포넌트**: `AppHeader`, `van-popup`(알림 모달), `van-icon`, 환율 미니 스파크라인(inline SVG).
- **네비게이션**: 뉴스 `→ /news/:id`(또는 외부 링크), AI추천 종목 `→ /company/:symbol?showAiAnalysis=true`.

### AssetsView (`/assets`) — 자산 요약 ⚠️ 전부 Mock
- **목적**: 총자산·자산유형별(현금/주식/채권/코인) 비중·7일 추이·자산 카드.
- **API**: 없음. **데이터**: `mockAssetSummary` + 하드코딩 7일 추이 — **Mock 100%**.
- **컴포넌트**: `AppHeader`, Chart.js `Doughnut`(비중)·`Line`(추이).
- **TODO**: 새로고침 핸들러는 타임스탬프만 갱신하는 스텁("실제 자산 정보 새로고침 API 호출"). 채권·코인 비활성.
- **네비게이션**: `→ /assets/detail?main=<type>` (주식이면 `sub=overseas`).

### BotView (`/bot`) — AI 봇 제어 ✅ 실데이터 연동
- **목적**: 자동매매 봇 상태/평가금, 보유종목별 AI 분석, 봇 설정 모달(최대 투자금·시장 선택).
- **API** (onMounted): `userApi.getTradeConfig`, `userApi.updateTradeConfig`, `tradingApi.getHoldings`, `marketAnalysisApi.getStockAnalysis(symbol)`(보유종목마다 비동기) — **실데이터**.
- **데이터 흐름**: 보유종목(KIS) 로드 → 종목별 AI 분석을 비동기로 채움(`loading: true` 초기화 후 교체). KIS 응답 가공(소수점 처리·환율 2자리). 봇 on/off·설정은 `updateTradeConfig`로 저장.
- **컴포넌트**: `AppHeader`, `van-popup`(설정), 애니메이션 봇 아바타(inline SVG).
- **네비게이션**: `→ /news?symbol=`, `→ /trading/:symbol`, `→ /company/:symbol`, `→ /market-analysis`.

### SearchView (`/search`) — 종목 검색 ⚠️ Mock
- **목적**: 주식(국내/해외) 검색 + 결과 목록 + 관심 토글 + 기업 상세 이동.
- **API**: 없음. **데이터**: `mockSearchResults` + 하드코딩 `priceData` — **Mock 100%**.
- **TODO**: 가격 데이터는 "나중에 API로 대체" 주석.
- **컴포넌트**: `AppHeader`, `InvestmentTabs`.
- **네비게이션**: `→ /company/:symbol`.

### FavoritesView (`/favorites`) — 관심/포트폴리오 ⚠️ Mock
- **목적**: 관심종목 캐러셀(가격·기간별 차트·뉴스·재무), 보유/관심 토글, 관심 목록.
- **API**: 없음. **데이터**: `mockSearchResults` + 하드코딩 `companyData`(가격/3기간 차트/뉴스/재무) — **Mock 100%**.
- **컴포넌트**: `AppHeader`, `InvestmentTabs`, Chart.js `Line`(가격 추이, 등락 방향에 따라 그라데이션).
- **네비게이션**: `→ /company/:symbol`.

---

## detail — 상세 화면

### AssetDetailView (`/assets/detail`) — 자산 상세 🔶 혼합
- **목적**: 보유 주식·현금 잔고 상세(KIS).
- **API**: `assetApi.getHoldings()`, `assetApi.getBalance()` — **혼합**(국내는 실데이터, 해외는 `mockStocks`).
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

### NewsView (`/news`) — 뉴스 목록 ⚠️ Mock
- **목적**: 뉴스 피드(날짜·검색·정렬 필터).
- **API**: 없음(`newsApi` 미사용). **데이터**: `mockTopNews` — **Mock**.
- **컴포넌트**: `AppHeader`, `AssetTabs`, 커스텀 검색 SVG.
- **네비게이션**: `→ /news/:id`.

### NewsDetailView (`/news/:id`) — 뉴스 상세 ⚠️ Mock
- **목적**: 기사 본문·메타·태그·이미지·관련 뉴스.
- **API**: 없음. **데이터**: `mockNewsDetail` — **Mock**. `route.params.id`를 읽지만 사용하지 않음(onMounted에 TODO).
- **컴포넌트**: `AppHeader`.
- **네비게이션**: 관련 뉴스 `→ /news/:id`.

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
- **API**: `userApi.getSettings()`, `userApi.updateSettings(...)`, `userApi.deleteAccount()` — **혼합**(`mockSettings`로 초기화 후 마운트 시 실데이터로 덮어씀).
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
| AssetDetail | 🔶 혼합 | assetApi.getHoldings/getBalance |
| Settings | 🔶 혼합 | userApi.getSettings/updateSettings/deleteAccount |
| Profile | ✅ 실데이터 | userApi.*, authApi.validateKisAccount |
| Login/Register/RegisterFinance/ResetPassword | ✅ 실데이터 | authApi.* |
| Assets | ⚠️ Mock 100% | (없음) |
| Search | ⚠️ Mock 100% | (없음) |
| Favorites | ⚠️ Mock 100% | (없음) |
| News / NewsDetail | ⚠️ Mock | (없음, newsApi 미사용) |
| Transfer | ⚠️ 스텁 | (없음, TODO) |
| Terms | ⚠️ 플레이스홀더 | (없음, mock-token 저장) |
| Splash/Welcome | — | (없음) |

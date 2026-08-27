# ARCHITECTURE — web-app

Vue 3 SPA의 디렉터리 구조, 라우팅, 상태관리, API 레이어, 빌드/배포, 백엔드·AI 연동을 정리한다. 모든 내용은 `web-app/src/` 및 설정 파일 코드 기준이다.

## 1. 디렉터리 구조

```
web-app/
├── index.html              # Vite 진입 HTML (lang=ko, PWA 메타, #app 마운트)
├── package.json            # 의존성·스크립트 (dev/build/preview/lint/format)
├── vite.config.js          # Vite + vue + vueDevTools + VitePWA, alias '@'→src, server port 5173
├── eslint.config.js        # ESLint flat config (js recommended + vue essential + prettier)
├── .prettierrc.json        # Prettier 설정
├── Dockerfile              # 2-stage: node build → nginx 서빙
├── nginx.conf              # SPA fallback, gzip, 캐시 헤더, 보안 헤더
├── jsconfig.json           # 에디터용 path alias
└── src/
    ├── main.js             # 앱 부트스트랩: Pinia·Vant 컴포넌트 등록·Chart.js 등록·router·v-calendar
    ├── App.vue             # 루트: <RouterView> + 조건부 <BottomNav> + onMounted 자동 로그인 복원
    ├── router/index.js     # 라우트 정의 + beforeEach 가드
    ├── services/
    │   ├── api.js          # axios 인스턴스 + 인터셉터 + 도메인별 API 객체(12개)
    │   ├── mockData.js     # 화면용 Mock 데이터 (export 1개: mockMarketIndices)
    │   ├── realtime.js     # 실시간 WebSocket 클라이언트 싱글톤 (/ws/realtime)
    │   └── webauthn.js     # WebAuthn(생체 로그인/패스키) 클라이언트 헬퍼
    ├── stores/
    │   ├── auth.js         # Pinia: 회원가입 멀티스텝 데이터 + 인증 토큰/유저 + 계좌모드
    │   └── realtime.js     # Pinia: 실시간 호가/체결가/체결통보 상태 + 구독 래퍼
    ├── utils/
    │   ├── tokenStorage.js # 인증 토큰 저장소 (자동로그인 설정에 따라 local/session 분기)
    │   ├── uiSettings.js   # 다크모드·자동로그인·자산순서 (localStorage + 인메모리 ref)
    │   ├── kisStatus.js    # KIS 점검/장애 판별 (graceful degrade 배너용)
    │   ├── logger.js       # 개발 모드 전용 로깅 래퍼
    │   └── toast.js        # Vant showToast 래퍼 (success/error/warning/info/loading)
    ├── assets/
    │   ├── base.css        # 디자인 토큰(CSS 변수: color/spacing/radius/font 등)
    │   ├── main.css        # 앱 전역 스타일, 반응형, v-calendar 커스텀
    │   └── logo.svg
    ├── components/
    │   └── common/         # AppHeader, BottomNav, StockCard, AssetTabs,
    │                       # InvestmentTabs, KisMaintenanceNotice
    └── views/
        ├── auth/           # Splash, Welcome, Login, Register, RegisterFinance, Terms, ResetPassword
        ├── main/           # Home, Assets, Bot, Search, Favorites
        ├── detail/         # AssetDetail, CompanyDetail, Trading, Transactions, News, NewsDetail
        ├── analysis/       # MarketAnalysis
        └── settings/       # Profile, Settings
```

> Vite + Vue 초기 템플릿 산출물(`HelloWorld.vue`, `TheWelcome.vue`, `WelcomeItem.vue`, `components/icons/Icon*.vue`, `views/AboutView.vue`)과 범위 밖으로 빠진 `TransferView.vue`는 **모두 삭제됐다**. 현재 `components/`·`views/` 아래에는 실제로 사용되는 파일만 남아 있다.

## 2. 라우팅 구조

라우터: `createWebHistory`, `scrollBehavior`로 네비게이션 시 항상 최상단 스크롤. 모든 뷰는 동적 `import()`로 lazy-load.

### 라우트 표

| path | name | 뷰 컴포넌트 | 인증 필요(prod)¹ | bottomNav² |
|------|------|------------|:---:|:---:|
| `/` | splash | `auth/SplashView` | 공개 | — |
| `/welcome` | welcome | `auth/WelcomeView` | 공개 | — |
| `/login` | login | `auth/LoginView` | 공개 | — |
| `/register` | register | `auth/RegisterView` | 공개 | — |
| `/register/finance` | register-finance | `auth/RegisterFinanceView` | 공개 | — |
| `/terms` | terms | `auth/TermsView` | 공개 | — |
| `/reset-password` | reset-password | `auth/ResetPasswordView` | 공개 | — |
| `/home` | home | `main/HomeView` | 필요 | ✅ |
| `/assets` | assets | `main/AssetsView` | 필요 | ✅ |
| `/bot` | bot | `main/BotView` | 필요 | ✅ |
| `/search` | search | `main/SearchView` | 필요 | ✅ |
| `/favorites` | favorites | `main/FavoritesView` | 필요 | ✅ |
| `/assets/detail` | assets-detail | `detail/AssetDetailView` | 필요 | — |
| `/company/:symbol` | company-detail | `detail/CompanyDetailView` | 필요 | — |
| `/trading/:symbol` | trading | `detail/TradingView` | 필요 | — |
| `/transactions` | transactions | `detail/TransactionsView` | 필요 | ✅ |
| `/news` | news | `detail/NewsView` | 필요 | — |
| `/news/:id` | news-detail | `detail/NewsDetailView` | 필요 | — |
| `/market-analysis` | market-analysis | `analysis/MarketAnalysisView` | 필요 | — |
| `/profile` | profile | `settings/ProfileView` | 필요 | ✅ |
| `/settings` | settings | `settings/SettingsView` | 필요 | — |

¹ **인증 필요(prod)**: `beforeEach` 가드는 `publicPages` 목록(`/`, `/welcome`, `/login`, `/register`, `/register/finance`, `/terms`, `/reset-password`)을 제외한 모든 경로를 인증 필요로 본다. 단 **`import.meta.env.DEV`일 때는 모든 검사를 건너뛴다**. 프로덕션에서 토큰(`getToken('accessToken')` — local/session 양쪽 조회)이 없으면 `/welcome`으로 리다이렉트.

² **bottomNav**: 라우트 `meta.showBottomNav: true`이면 `App.vue`가 하단 네비게이션을 렌더한다.

### 하단 네비게이션(BottomNav) 항목

`components/common/BottomNav.vue`에 7개 항목이 하드코딩돼 있다: 내정보(`/profile`), AI(`/bot`), 자산(`/assets`), 홈(`/home`), 관심(`/favorites`), 검색(`/search`), 거래(`/transactions`). 각 아이콘은 inline SVG. 현재 경로(`route.path`)와 일치하는 항목이 active 처리된다.

## 3. 상태관리 (State Management)

- **Pinia 스토어 2개** (모두 setup-store 형태):
  - `stores/auth.js`
    - 회원가입 멀티스텝 데이터: `registrationData`(`step1` 개인정보 / `step2` 금융정보(KIS) / `validation` 중복확인 상태). 액션: `saveStep1Data`, `saveStep2Data`, `setIdCheckResult`, `setEmailCheckResult`, `clearRegistrationData`, `hasStep1Data`.
    - 인증 상태: `user`, `accessToken`, `refreshToken`. 액션: `setAuthData`(스토어 + 저장소 동시 저장), `clearAuthData`, `loadAuthDataFromStorage`(앱 시작 시 `App.vue`가 호출), `logout`(서버 logout 후 로컬 삭제), getter `isAuthenticated`. (2026-08 QA — 모의투자 지원 제거와 함께 `accountMode`/`setAccountMode` 삭제.)
  - `stores/realtime.js` — 실시간 호가/체결가 캐시(`getQuote`/`getTick`)와 구독 래퍼(`subscribe`/`subscribeFills`), 연결 상태·안내 문구. `services/realtime.js` 싱글톤을 감싼다. 소비처는 `App.vue`(체결통보 전역 구독), `TradingView`, `AssetDetailView`, `ProfileView`.

### 토큰 저장소 규칙 (중요)

토큰은 **localStorage 단일 출처가 아니다.** `utils/tokenStorage.js`가 "자동 로그인"(`uiSettings.autoLogin`) 설정에 따라 저장 위치를 가른다:

| autoLogin | 저장 위치 | 수명 |
|---|---|---|
| `true`(기본값) | `localStorage` | 브라우저를 껐다 켜도 유지 |
| `false` | `sessionStorage` | 새로고침은 살아남지만 탭/브라우저를 닫으면 소멸 |

따라서 **저장소를 직접 읽지 말고 반드시 `getToken(key)`를 쓴다** — `getToken`은 `localStorage` → `sessionStorage` 순으로 양쪽을 조회한다. `localStorage.getItem('accessToken')`을 직접 호출하면 자동 로그인 OFF 사용자에게만 `null`이 되는 조용한 버그가 생긴다(실제로 실시간 WebSocket·프로필 화면에서 발생했던 결함).

- 쓰기: `setTokens(pairs)` — 현재 설정에 맞는 저장소에 쓰고 반대쪽 잔여값을 지운다.
- 삭제: `clearTokens()` — 인증 키 3개(`accessToken`, `refreshToken`, `user`)만 양쪽에서 제거한다. **`localStorage.clear()`를 쓰면 안 된다** — `uiSettings`(다크모드/자동로그인/자산순서)까지 날아가고 `sessionStorage` 쪽 토큰은 그대로 남는다.

`uiSettings`는 별도 키(`localStorage.uiSettings`)이며 인증과 무관하게 보존된다. `utils/uiSettings.js`는 모듈 로드 시 1회 읽은 인메모리 `ref`를 통해 제공하므로, localStorage에서 지워져도 **다음 페이지 로드 시점에야** 손실이 드러난다.

- 그 외 화면별 상태는 각 뷰의 `ref`/`computed`로 로컬 관리. 화면별 상세는 [SCREENS.md](./SCREENS.md).

## 4. API 레이어

`services/api.js` — 단일 axios 인스턴스 + 도메인별 API 객체.

### 인스턴스 설정
- `baseURL`: `import.meta.env.VITE_API_BASE_URL || 'http://localhost:7070/api'`.
  > api-server `application.yml`의 `server.port: 7070` + context-path `/api`와 일치한다.
- `timeout`: 10000ms (요청별로 개별 override 가능 — 예: 거래내역 25s).
- 기본 헤더: `Content-Type: application/json`.

### 인터셉터
- **요청 인터셉터**: `getToken('accessToken')`(local/session 양쪽 조회)이 있으면 `Authorization: Bearer <token>` 자동 주입.
- **응답 인터셉터**:
  - 성공 시 `response.data`만 반환. 이때 반환되는 것은 api-server의 **`ApiResponse` 래퍼 본문**(`{success, message, data}`)이므로, 실제 페이로드는 한 단계 더 들어간 `res.data`다.
  - 공개 인증 엔드포인트는 인터셉터 처리 제외(401 그대로 호출부에 반환): `/auth/login`, `/auth/register`, `/auth/reset-password`, `/auth/webauthn/login/`.
  - 401 + 미재시도 요청이면 **토큰 자동 refresh**: `refreshToken`으로 `POST /auth/refresh` → 새 `accessToken`을 `setTokens`로 저장하고 Pinia `accessToken` ref도 갱신한 뒤 원요청 재시도. refresh 진행 중 들어온 요청은 큐(`refreshSubscribers`)에 대기시켰다가 새 토큰으로 재개하며, refresh가 실패하면 대기 큐를 전부 reject 후 비운다. 이어서 `clearTokens()`(인증 키만 제거) 후 `/login`으로 강제 이동.

### 도메인별 API 객체

현재 **12개** 객체가 export 돼 있으며, 모두 실제 화면에서 사용된다.

| 객체 | 엔드포인트(메서드) | 비고 |
|------|-------------------|------|
| `authApi` | `/auth/login`, `/auth/register`, `/auth/reset-password`, `/auth/check-username`, `/auth/check-email`, `/auth/logout`, `/auth/validate-kis-account` | 인증·회원가입·KIS 계좌 검증. refresh는 인터셉터가 raw axios로 직접 호출(재귀 방지) |
| `webauthnApi` | `/auth/webauthn/register/start`·`/finish`, `/auth/webauthn/login/start`·`/finish` | 생체 로그인/패스키. `register/*`는 JWT 필요, `login/*`은 공개(usernameless) |
| `userApi` | `/users/me`(GET/PUT/DELETE), `/users/settings`(GET/PUT), `/users/kis-account`(GET/PUT), `/users/trade-config`(GET/PUT) | 프로필·설정·KIS 계좌·자동매매 설정 |
| `assetApi` | `/assets/holdings`, `/assets/balance`, `/assets/snapshot`(POST), `/assets/history` | 보유종목·잔고·총자산 일별 스냅샷/추이 |
| `tradingApi` | `/trading/buy`, `/sell`, `/history`, `/recent`, `/holdings`, `/pending-orders`, `/orderable`, `/reserved-orders`(GET/POST/DELETE) | 국내 매매·거래내역·미체결·예약주문(실전 계좌 전용) |
| `stockApi` | `/stocks/search`, `/stocks/top`, `/stocks/{code}/price`, `/stocks/{code}/orderbook` | 국내 종목 검색·인기·시세·호가 |
| `overseasApi` | `/overseas/stocks/{symbol}/price`·`/orderbook`, `/overseas/balance`, `/history`, `/pending-orders`, `/orderable`, `/buy`, `/sell` | 해외(US) 시세·잔고·매매. `exchange` 파라미터 필요 |
| `favoriteApi` | `/favorites`(GET/POST), `/favorites/{code}`(DELETE) | 관심종목 |
| `companyApi` | `/company/{code}/basic-info`, `/financials`, `/disclosures` | 기업정보 |
| `newsApi` | `/news`(`{symbol?, date?}`), `/news/{id}` | 뉴스 목록·상세. NewsView/NewsDetailView에서 사용 |
| `marketApi` | `/market/indices`, `/market/exchange-rates`, `/market/news`, `/market/decisions` | 홈 화면 지수·환율·뉴스·AI추천 |
| `marketAnalysisApi` | `/market/summary`, `/sentiment`, `/decisions`, `/latest-date`, `/heatmap`, `/stock-analysis/{code}`, `/stock-detail/{code}` | 시장분석 대시보드·종목 상세 |

> 과거 문서에 있던 `botApi`는 삭제됐다(BotView는 `userApi`/`tradingApi`/`marketAnalysisApi`를 사용). 어떤 API가 어느 화면에서 호출되는지는 [SCREENS.md](./SCREENS.md)와 [STATUS.md](./STATUS.md) 참조.

### 실시간 WebSocket 계층

REST와 별개로 `/ws/realtime`에 붙는 실시간 계층이 있다.

- `services/realtime.js` — 네이티브 WebSocket 싱글톤. `${wsOrigin}/ws/realtime?token=<accessToken>`으로 JWT 핸드셰이크. 구독 dedupe(refCount) + 지수 백오프 재연결(최대 4회 후 `disabled`로 포기, 사용자 액션 시 재시도).
  - 프레임: 구독/해제 `{action, market, symbol, type, exchange}`, 데이터 `{type:'orderbook'|'tick', ...}`, 체결통보 `{type:'fills'}`, 상태 `{type:'status', state, notice}`.
  - **Graceful degrade**: 연결 실패/서버 비활성 상태에서 절대 throw 하지 않고 상태만 알린다. 뷰는 REST 스냅샷을 유지한다.
  - 실시간은 **KIS 계좌가 등록된 유저에게만** 활성화된다(`App.vue`의 `applyRealtimeForKisAccount()`가 계좌 조회 성공 여부로 `setEnabled` 판정 — 2026-08 QA 이전에는 `accountMode==='REAL'` 기준이었으나, 모의투자 지원 제거로 계좌 등록 여부 기준으로 교체됨).
- `stores/realtime.js` — 위 싱글톤을 감싼 Pinia 스토어. 프레임 필드는 **camelCase 계약**(`currentPrice`/`changeAmount`/`changeRate`/`accVolume`, `quote.asks|bids: [{price, quantity}]`)으로, REST 렌더 경로를 그대로 재사용하기 위한 의도된 규약이다.

브라우저는 KIS 소켓에 직접 붙지 않는다 — 항상 Spring 브리지를 경유하며, 서버가 단일 상향 KIS 연결을 심볼 ref-count로 멀티플렉싱한다.

## 5. 빌드 / PWA / 배포

- **빌드**: Vite 7. `@` alias → `src`. dev 서버 `host: true`(LAN 접근), port 5173.
- **PWA** (`VitePWA`):
  - `registerType: 'autoUpdate'`, `cleanupOutdatedCaches`, `skipWaiting`, `clientsClaim`.
  - manifest: name `F. Finance App`, short_name `F.`, `theme_color #4F46E5`, `display: standalone`, 아이콘 `/logo.png`(192/512).
  - 설치형 앱으로 동작.
- **Docker**: 2-stage. ① `node:lts-alpine`에서 `npm install` → `npm run build`. ② `nginx:stable-alpine`에 `dist/` 복사, `nginx.conf` 적용, 80 포트 노출.
- **Nginx** (`nginx.conf`):
  - SPA fallback: `try_files $uri $uri/ /index.html`.
  - gzip, 정적 파일 1년 캐시, manifest/sw는 no-cache.
  - 보안 헤더: `X-Frame-Options`, `X-Content-Type-Options`, `X-XSS-Protection`.
  - `/api` 프록시 블록은 **주석 처리**되어 있음(미사용). 즉 nginx는 API를 프록시하지 않으며, 프런트엔드는 `VITE_API_BASE_URL`로 직접 호출한다.

## 6. 백엔드·AI 연동 흐름

코드에서 확인된 통신 구조:

```
Vue3 (axios single instance, baseURL=VITE_API_BASE_URL)
  └─→ /auth/*, /users/*, /assets/*, /trading/*  →  Spring Boot api-server (7070, 인증/거래/자산/사용자)
  └─→ /stocks/*, /overseas/*, /favorites/*      →  Spring Boot api-server (시세/해외/관심종목)
  └─→ /company/*, /news/*                        →  Spring Boot api-server (기업정보·뉴스)
  └─→ /market/*  (summary/sentiment/decisions/   →  api-server가 AI 분석 결과(PostgreSQL)를 중계
                  heatmap/indices/...)               (marketApi·marketAnalysisApi)

Vue3 (native WebSocket)
  └─→ /ws/realtime?token=<JWT>                   →  Spring 브리지 → KIS 실시간 소켓 (멀티플렉싱)
```

- 프런트엔드의 HTTP 통신은 **단일 baseURL**로만 나가며, 실시간만 같은 호스트의 WebSocket 엔드포인트를 추가로 쓴다(`VITE_API_BASE_URL`에서 컨텍스트 경로를 떼고 파생). 코드상 AI 서버(FastAPI, 8000)나 `/static/charts/*` 이미지에 **직접 접근하는 경로는 없다**. 시장분석·종목 상세 화면의 차트는 모두 클라이언트에서 CSS/HTML/inline SVG로 직접 렌더링하며, 서버 생성 PNG를 `<img>`로 불러오지 않는다.
- 따라서 AI 분석 결과(센티먼트, Prophet 예측, AI 매매 판단 등)는 `/market/*` 경로를 통해 **JSON 형태로** 받아 프런트에서 시각화하는 구조다. api-server(`MarketAnalysisController`/`MarketDataController`/`CompanyController`)가 ai-agent의 산출물을 DB에서 읽어 중계하며, **web-app이 ai-agent를 직접 호출하는 경로는 없다**.

## 7. UI 컴포넌트 / 스타일 토큰

- **Vant 4**: `main.js`에서 사용 컴포넌트를 명시 등록(Button, Tabs, Popup, DatePicker, Calendar, Toast, Skeleton, List, PullRefresh 등). locale 한국어(`ko-KR`).
- **공통 컴포넌트**(`components/common/`):
  - `AppHeader` — 뒤로가기·타이틀·아이콘·우측 슬롯. 대부분의 상세/설정 화면 상단에 사용.
  - `BottomNav` — 하단 7탭 (위 §2.3).
  - `StockCard` — 보유종목 카드(현재가/매입금/평가손익/수량/손익률 + 뉴스·매매·기업정보 버튼, emit).
  - `AssetTabs` / `InvestmentTabs` — 주식/채권/코인(채권·코인 disabled) + 국내/해외 서브탭. 거의 동일하나 `AssetTabs`는 탭 목록을 prop으로 외부 주입 가능.
  - `KisMaintenanceNotice` — KIS 점검/장애 시 화면을 깨뜨리지 않고 띄우는 안내 배너. `utils/kisStatus.js`의 판별 함수와 짝을 이룬다(이 프로젝트의 일관된 graceful-degrade UX 패턴).
- **스타일**: `assets/base.css`에 디자인 토큰을 CSS 변수로 정의(`--color-*`, `--spacing-*`, `--radius-*`, `--font-*`, `--max-width-mobile`, `--bottom-nav-height` 등). `main.css`가 전역 스타일·반응형(1024px 이상에서 모바일 폭으로 중앙 정렬)·v-calendar 커스텀을 담당. Tailwind 4.1도 의존성에 포함.
- **Chart.js**: `main.js`에서 전역 register. 사용 화면은 `AssetsView`(Doughnut, Line), `FavoritesView`(Line). 그 외 차트성 표현(시장분석 히트맵, Prophet 예측, 미니 스파크라인)은 Chart.js 없이 CSS/SVG로 직접 구현.

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
    │   ├── api.js          # axios 인스턴스 + 인터셉터 + 도메인별 API 객체
    │   └── mockData.js     # 화면용 Mock 데이터 (17개 export)
    ├── stores/
    │   └── auth.js         # Pinia 스토어: 회원가입 멀티스텝 데이터 + 인증 토큰/유저
    ├── utils/
    │   └── toast.js        # Vant showToast 래퍼 (success/error/warning/info/loading)
    ├── assets/
    │   ├── base.css        # 디자인 토큰(CSS 변수: color/spacing/radius/font 등)
    │   ├── main.css        # 앱 전역 스타일, 반응형, v-calendar 커스텀
    │   └── logo.svg
    ├── components/
    │   ├── common/         # AppHeader, BottomNav, StockCard, AssetTabs, InvestmentTabs
    │   ├── icons/          # Icon*.vue (Vite 스캐폴드 잔재)
    │   ├── HelloWorld.vue  # Vite 스캐폴드 잔재 (라우팅/뷰에서 미참조)
    │   ├── TheWelcome.vue  # Vite 스캐폴드 잔재
    │   └── WelcomeItem.vue # Vite 스캐폴드 잔재
    └── views/
        ├── auth/           # Splash, Welcome, Login, Register, RegisterFinance, Terms, ResetPassword
        ├── main/           # Home, Assets, Bot, Search, Favorites
        ├── detail/         # AssetDetail, CompanyDetail, Trading, Transactions, News, NewsDetail, Transfer
        ├── analysis/       # MarketAnalysis
        ├── settings/       # Profile, Settings
        └── AboutView.vue   # Vite 스캐폴드 잔재 (라우터에서 미참조)
```

> 스캐폴드 잔재: `HelloWorld.vue`, `TheWelcome.vue`, `WelcomeItem.vue`, `components/icons/Icon*.vue`, `views/AboutView.vue`는 Vite + Vue 초기 템플릿 산출물로, 라우터(`router/index.js`)나 다른 뷰에서 참조되지 않는다(grep 확인). 실제 화면 로직과 무관.

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

¹ **인증 필요(prod)**: `beforeEach` 가드는 `publicPages` 목록(`/`, `/welcome`, `/login`, `/register`, `/register/finance`, `/terms`, `/reset-password`)을 제외한 모든 경로를 인증 필요로 본다. 단 **`import.meta.env.DEV`일 때는 모든 검사를 건너뛴다**. 프로덕션에서 토큰(`localStorage.accessToken`)이 없으면 `/welcome`으로 리다이렉트.

² **bottomNav**: 라우트 `meta.showBottomNav: true`이면 `App.vue`가 하단 네비게이션을 렌더한다.

### 하단 네비게이션(BottomNav) 항목

`components/common/BottomNav.vue`에 7개 항목이 하드코딩돼 있다: 내정보(`/profile`), AI(`/bot`), 자산(`/assets`), 홈(`/home`), 관심(`/favorites`), 검색(`/search`), 거래(`/transactions`). 각 아이콘은 inline SVG. 현재 경로(`route.path`)와 일치하는 항목이 active 처리된다.

## 3. 상태관리 (State Management)

- **Pinia 스토어**: `stores/auth.js` 단 하나. setup-store 형태.
  - 회원가입 멀티스텝 데이터: `registrationData`(`step1` 개인정보 / `step2` 금융정보(KIS) / `validation` 중복확인·인증 상태). 액션: `saveStep1Data`, `saveStep2Data`, `setIdCheckResult`, `setEmailCheckResult`, `setPhoneVerified`, `clearRegistrationData`, `hasStep1Data`.
  - 인증 상태: `user`, `accessToken`, `refreshToken`. 액션: `setAuthData`(스토어 + `localStorage` 동시 저장), `clearAuthData`, `loadAuthDataFromStorage`(앱 시작 시 `App.vue`가 호출), `logout`(서버 logout 후 로컬 삭제), getter `isAuthenticated`.
- **localStorage**: 토큰 영속화의 단일 출처. axios 인터셉터·라우터 가드 모두 `localStorage`를 직접 읽는다(`accessToken`, `refreshToken`, `user`).
- 그 외 화면별 상태는 각 뷰의 `ref`/`computed`로 로컬 관리(전역 store 없음). 화면별 상세는 [SCREENS.md](./SCREENS.md).

## 4. API 레이어

`services/api.js` — 단일 axios 인스턴스 + 도메인별 API 객체.

### 인스턴스 설정
- `baseURL`: `import.meta.env.VITE_API_BASE_URL || 'http://localhost:7070/api'`.
  > api-server `application.yml`의 `server.port: 7070` + context-path `/api`와 일치한다.
- `timeout`: 10000ms (요청별로 개별 override 가능 — 예: 거래내역 25s).
- 기본 헤더: `Content-Type: application/json`.

### 인터셉터
- **요청 인터셉터**: `localStorage.accessToken`이 있으면 `Authorization: Bearer <token>` 자동 주입.
- **응답 인터셉터**:
  - 성공 시 `response.data`만 반환(뷰에서는 axios 래퍼가 아닌 payload를 직접 받음).
  - `/auth/login`·`/auth/register`·`/auth/reset-password` 요청은 인터셉터 처리 제외(401 그대로 반환).
  - 401 + 미재시도 요청이면 **토큰 자동 refresh**: `refreshToken`으로 `POST /auth/refresh` → 새 `accessToken` 저장 후 원요청 재시도. refresh 진행 중 들어온 요청은 큐(`refreshSubscribers`)에 대기시켰다가 새 토큰으로 재개. refresh 실패 시 `localStorage.clear()` 후 `/login`으로 강제 이동.

### 도메인별 API 객체

| 객체 | 엔드포인트(메서드) | 비고 |
|------|-------------------|------|
| `authApi` | `/auth/login`, `/auth/register`, `/auth/reset-password`, `/auth/check-username`, `/auth/check-email`, `/auth/refresh`, `/auth/logout`, `/auth/validate-kis-account` | 인증·회원가입·KIS 계좌 검증 |
| `userApi` | `/users/me`(GET/PUT/DELETE), `/users/settings`(GET/PUT), `/users/kis-account`(GET/PUT), `/users/trade-config`(GET/PUT) | 프로필·설정·KIS 계좌·자동매매 설정 |
| `assetApi` | `/assets/holdings`, `/assets/balance` | 보유종목·잔고 |
| `tradingApi` | `/trading/buy`, `/trading/sell`, `/trading/history`, `/trading/recent`, `/trading/holdings` | 매수/매도·거래내역·최근거래·보유 |
| `companyApi` | `/company/{code}/basic-info`, `/financials`, `/disclosures` | 기업정보 (Spring Boot) |
| `newsApi` | `/news`, `/news/{id}`, `/news/by-date` | 뉴스 (주석상 FastAPI 처리) — **현재 뷰에서 미사용** |
| `marketApi` | `/market/indices`, `/market/exchange-rates`, `/market/news`, `/market/decisions` | 홈 화면 지수·환율·뉴스·AI추천 |
| `botApi` | `/bot/status`, `/bot/analysis/{symbol}`, `/bot/toggle`, `/bot/settings` | 정의돼 있으나 **현재 뷰에서 미사용** (BotView는 userApi/tradingApi/marketAnalysisApi 사용) |
| `marketAnalysisApi` | `/market/summary`, `/sentiment`, `/decisions`, `/latest-date`, `/heatmap`, `/stock-analysis/{code}`, `/stock-detail/{code}` | 시장분석 대시보드·종목 상세 |

> 주석 처리된 `stockApi`는 미구현으로 남아 있다(`// TODO: Implement these endpoints in api-server`). 어떤 API가 실제로 호출되는지는 [SCREENS.md](./SCREENS.md)와 [STATUS.md](./STATUS.md) 참조.

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
  └─→ /company/*                                 →  Spring Boot api-server (기업정보)
  └─→ /market/*  (summary/sentiment/decisions/   →  api-server가 AI 분석 결과(PostgreSQL)를 중계
                  heatmap/indices/...)               (marketApi·marketAnalysisApi)
```

- 프런트엔드는 **단일 baseURL**로만 통신한다. 코드상 AI 서버(FastAPI, 8000)나 `/static/charts/*` 이미지에 **직접 접근하는 경로는 발견되지 않았다**. 시장분석·종목 상세 화면의 차트는 모두 클라이언트에서 CSS/HTML/inline SVG로 직접 렌더링하며, 서버 생성 PNG를 `<img>`로 불러오지 않는다.
- 따라서 AI 분석 결과(센티먼트, Prophet 예측, AI 매매 판단 등)는 `/market/*` 경로를 통해 **JSON 형태로** 받아 프런트에서 시각화하는 구조다. (api-server가 ai-agent의 산출물을 DB에서 읽어 제공하는 것으로 추정 — 프런트 코드만으로는 백엔드 내부 경로 미확인.)

## 7. UI 컴포넌트 / 스타일 토큰

- **Vant 4**: `main.js`에서 사용 컴포넌트를 명시 등록(Button, Tabs, Popup, DatePicker, Calendar, Toast, Skeleton, List, PullRefresh 등). locale 한국어(`ko-KR`).
- **공통 컴포넌트**(`components/common/`):
  - `AppHeader` — 뒤로가기·타이틀·아이콘·우측 슬롯. 대부분의 상세/설정 화면 상단에 사용.
  - `BottomNav` — 하단 7탭 (위 §2.3).
  - `StockCard` — 보유종목 카드(현재가/매입금/평가손익/수량/손익률 + 뉴스·매매·기업정보 버튼, emit).
  - `AssetTabs` / `InvestmentTabs` — 주식/채권/코인(채권·코인 disabled) + 국내/해외 서브탭. 거의 동일하나 `AssetTabs`는 탭 목록을 prop으로 외부 주입 가능.
- **스타일**: `assets/base.css`에 디자인 토큰을 CSS 변수로 정의(`--color-*`, `--spacing-*`, `--radius-*`, `--font-*`, `--max-width-mobile`, `--bottom-nav-height` 등). `main.css`가 전역 스타일·반응형(1024px 이상에서 모바일 폭으로 중앙 정렬)·v-calendar 커스텀을 담당. Tailwind 4.1도 의존성에 포함.
- **Chart.js**: `main.js`에서 전역 register. 사용 화면은 `AssetsView`(Doughnut, Line), `FavoritesView`(Line). 그 외 차트성 표현(시장분석 히트맵, Prophet 예측, 미니 스파크라인)은 Chart.js 없이 CSS/SVG로 직접 구현.

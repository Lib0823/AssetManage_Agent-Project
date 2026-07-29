# STATUS — web-app 진행 상황

화면·기능별 진행 상황과 실데이터 연동 현황을 정리한다. 모든 판단은 `web-app/src/` 코드 직접 확인 기준이며, 코드만으로 확정할 수 없는 항목은 "미확인/TODO"로 표시했다. 최근 작업 흐름은 `git log -- web-app/`로 교차 확인했다.

상태 정의:
- **완료** — 실데이터 API 호출 + 화면 렌더 동작 코드가 갖춰짐.
- **진행중** — 일부 실데이터 연동 + 일부 Mock/스텁 혼재.
- **미착수** — 화면 UI는 있으나 API 미연동(Mock·하드코딩·스텁만 존재).

> 주의: "완료"는 코드 존재 기준이며, 실제 동작/QA 통과를 의미하지 않는다(미테스트). 이 모듈에는 자동화 테스트가 없다(§아래 참조).

## 1. 화면별 진행 상황

| 화면 | 상태 | 실데이터 연동 | 근거 |
|------|:---:|:---:|------|
| HomeView | 완료 | ✅ (Mock 폴백 포함) | `marketApi.*` + `tradingApi.getRecentTrades`, 최근 커밋 `d4436ed feat(web-app): wire home screen to real data` |
| BotView | 완료 | ✅ | `userApi.getTradeConfig/updateTradeConfig`, `tradingApi.getHoldings`, `marketAnalysisApi.getStockAnalysis`; 커밋 `95b83df show AI analysis in bot holdings cards` |
| MarketAnalysisView | 완료 | ✅ | `marketAnalysisApi` 5개 호출; 커밋 `16b33cb integrate market analysis API`, `c4f562c dashboard UI overhaul` |
| CompanyDetailView | 완료 | ✅ | `marketAnalysisApi.getStockDetail` + `companyApi.*`; 커밋 `251f213 wire stock detail screen to real APIs` |
| TransactionsView | 완료 | ✅ | `tradingApi.getHistory`(25s); 커밋 `93d2bb1 implement TransactionsView with real KIS API`, `6505334 raise timeout to 25s` |
| ProfileView | 완료 | ✅ | `userApi.*` + `authApi.validateKisAccount` |
| LoginView | 완료 | ✅ | `authApi.login` + `authStore.setAuthData` |
| RegisterView | 완료 | ✅ | `authApi.checkUsername/checkEmail` + Pinia 멀티스텝 |
| RegisterFinanceView | 완료 | ✅ | `authApi.validateKisAccount/register/login`, KIS rate-limit 처리 |
| ResetPasswordView | 완료 | ✅ | `authApi.resetPassword` |
| TradingView | 완료 | ✅ | 매수/매도(`tradingApi.buy/sell`) + 미체결(`tradingApi.getPendingOrders` → `/trading/pending-orders`) + 실시간 호가(`/stocks/{code}/orderbook`) + 매수가능(`/trading/orderable`) 전부 실데이터. 예약주문은 추후 지원(빈 상태) |
| AssetDetailView | 완료 | ✅ | 국내 holdings/balance 실데이터. `stockDetail` 미정의 버그 수정, USD 하드코딩 제거. 송금 진입 버튼 제거(범위 외). 해외는 추후 지원 |
| SettingsView | 완료 | ✅ | `userApi.getSettings/updateSettings/deleteAccount`. mockSettings 제거, 중립 기본값 초기화 후 응답으로 덮어씀 |
| AssetsView | 완료 | ✅ | `assetApi.getBalance/getHoldings`로 자산요약 구성. mockAssetSummary 제거, 새로고침 재로드. 채권·코인 추후 지원(0), 7일 추이 숨김 |
| SearchView | 완료 | ✅ | `stockApi.search`(카탈로그) + `stockApi.getPrice`(시세). mock 제거. 즐겨찾기 토글 `favoriteApi.add/remove`. 해외 추후 지원 |
| FavoritesView | 완료 | ✅ | `favoriteApi.list/add/remove`. 목록 + 현재가/등락률(quote 비활성 시 "—"). mock 섹션 제거, 해외 추후 지원 |
| NewsView | 미착수 | ⚠️ Mock | `mockTopNews` 사용, `newsApi` 미사용 |
| NewsDetailView | 미착수 | ⚠️ Mock | `mockNewsDetail` 사용, `route.params.id` 미사용(onMounted TODO) |
| TermsView | 완료 | — | mock 토큰 제거. 정보성 화면 + 동의 후 라우팅만(토큰 미발급) |
| SplashView | 완료 | — | 정적 화면(타이머 리다이렉트) |
| WelcomeView | 완료 | — | 정적 화면(Face ID 버튼은 스텁) |

요약: **인증/온보딩 + AI·시장분석·거래내역 + 자산요약·종목검색·관심종목 + 매매(매수/매도·미체결·호가·매수가능) 경로는 실데이터 연동 완료**. 뉴스는 Mock 상태, 해외주식·코인·예약주문은 추후 지원. 송금/계좌이체는 주식 MVP 범위 밖으로 제외(TransferView·`/transfer` 라우트·AssetDetailView 진입 버튼 제거).

## 2. 기능 단위 진행 상황

| 기능 | 상태 | 비고 |
|------|:---:|------|
| 로그인/로그아웃 | 완료 | `authApi.login/logout` + Pinia + localStorage |
| 2단계 회원가입(개인→KIS) | 완료 | Pinia `registrationData`로 단계 간 상태 유지, KIS 계좌 검증 포함 |
| 비밀번호 재설정 | 완료 | `authApi.resetPassword` |
| 토큰 자동 refresh | 완료 | 응답 인터셉터 401 처리 + 대기 큐 + 실패 시 강제 로그아웃 |
| 자동 로그인 복원 | 완료 | `App.vue` onMounted → `authStore.loadAuthDataFromStorage()` |
| 라우터 인증 가드 | 완료(DEV 우회) | `import.meta.env.DEV`에서는 검사 생략 |
| AI 봇 on/off·설정 | 완료 | `userApi.updateTradeConfig` |
| 보유종목별 AI 분석 표시 | 완료 | `marketAnalysisApi.getStockAnalysis` 비동기 로드 |
| 시장분석 대시보드 | 완료 | 히트맵/센티먼트/예측 모두 클라이언트 렌더 |
| 매수/매도 주문 | 완료 | 실행 API 연동 |
| 실시간 호가 조회 | 완료 | `/stocks/{code}/orderbook`(KIS `FHKST01010200`), 10단계 매도/매수 + 잔량 |
| 매수가능 조회 | 완료 | `/trading/orderable`(KIS `VTTC8908R`), 매수가능 수량/금액 |
| 미체결 주문 조회 | 완료 | `tradingApi.getPendingOrders` → `/trading/pending-orders`(daily-ccld 필터) |
| 거래내역 조회 | 완료 | 25s 타임아웃 |
| 자산 요약/추이 | 완료(추이 제외) | `assetApi.getBalance/getHoldings`. 7일 추이는 엔드포인트 없어 숨김 |
| 종목 검색 | 완료 | `stockApi.search/getPrice`(`/stocks/*`) |
| 관심종목 | 완료 | `favoriteApi.list/add/remove`(`/favorites`) |
| 뉴스 피드/상세 | 미착수 | Mock, `newsApi` 정의됐으나 미사용 |
| 계좌 이체(송금) | 제외 | 주식 MVP 범위 밖. TransferView·`/transfer` 라우트·AssetDetailView 진입 버튼 제거(api-server 변경 없음 — 원래 송금 엔드포인트 없었음) |
| PWA 설치 | 완료(설정) | `VitePWA` 구성됨(실기기 설치 검증은 미확인) |

## 3. 인프라/품질

| 항목 | 상태 | 비고 |
|------|:---:|------|
| ESLint | 구성됨 | flat config (`eslint.config.js`), `npm run lint` |
| Prettier | 구성됨 | `.prettierrc.json`, `npm run format` |
| 자동화 테스트 | 없음 | `package.json`에 test 스크립트·테스트 프레임워크(Vitest 등) 없음 |
| Dockerfile/Nginx | 구성됨 | 2-stage 빌드 + SPA fallback, `/api` 프록시는 주석 처리(미사용) |
| PWA manifest/SW | 구성됨 | `vite.config.js` VitePWA |

## 4. 미확인 / TODO (코드만으로 확정 불가)

1. **🔴 `analysis_view/` 정적 HTML 부재**: 루트 `CLAUDE.md`와 본 작업 지시는 `web-app/analysis_view/overview.html`·`stock_detail.html`(Vue 라우터 밖 정적 페이지)의 존재를 전제하나, **현재 리포지터리에서 해당 디렉터리·파일이 발견되지 않았다**(`find` 확인). `web-app/index.html`(Vite 진입점) 외 정적 HTML은 없다. → 과거에 삭제됐거나 다른 브랜치(예: `ai-trading-pipeline`)에만 존재할 가능성. **현재 `develop-analysis` 브랜치 기준으로는 미존재.**
2. **API baseURL (해결됨, 7070)**: `services/api.js` 기본값 `http://localhost:7070/api`는 api-server `application.yml`(`server.port: 7070` + context-path `/api`)과 일치한다. 구 루트 `CLAUDE.md`의 8080은 오기였고 정정됨. 단 `web-app`에 `.env`/`.env.example` 파일은 없으므로(확인됨), 배포 시 `VITE_API_BASE_URL` 명시 권장.
3. **api-server ↔ ai-agent 내부 경로**: 프런트는 단일 baseURL의 `/market/*`로 AI 결과를 받으나, api-server가 ai-agent(8000)/DB 중 어디서 데이터를 가져오는지는 프런트 코드 밖이라 미확인.
4. **`botApi`·`newsApi` 미사용**: `services/api.js`에 정의돼 있으나 어떤 뷰에서도 호출되지 않음(`newsApi`는 뉴스 화면 연동 대기). `stockApi`/`favoriteApi`는 SearchView/FavoritesView에서 실사용으로 연동됨.
5. **TermsView 흐름**: mock 토큰 발급 제거 완료. 동의 게이트 후 라우팅만 수행(토큰 미발급).
6. **Vite 스캐폴드 잔재**: `HelloWorld.vue`/`TheWelcome.vue`/`WelcomeItem.vue`/`components/icons/Icon*.vue`/`AboutView.vue`는 미참조. 정리(삭제) 대상 후보이나 의도 미확인.
7. **`favicon.ico` vs `logo.png`**: `index.html`은 `/logo.png`를 아이콘으로 사용, `public/favicon.ico`도 존재. 사용 정책 미확인(기능 영향 없음).
8. **"완료" 화면의 실동작/QA**: 테스트 코드가 없어 런타임 동작은 코드 검토 기준 추정일 뿐, 검증되지 않음.

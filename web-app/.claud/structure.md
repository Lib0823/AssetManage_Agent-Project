# Web App - Project Structure

## 디렉토리 구조

```
web-app/
├── src/
│   ├── features/             # 기능별 모듈 (도메인 기반)
│   │   ├── auth/
│   │   │   ├── components/   # LoginForm.vue, RegisterForm.vue
│   │   │   ├── composables/  # useAuth.js
│   │   │   ├── api/          # authApi.js
│   │   │   └── views/        # LoginView.vue, RegisterView.vue
│   │   │
│   │   ├── trading/
│   │   │   ├── components/   # OrderForm.vue, OrderBook.vue, StockChart.vue
│   │   │   ├── composables/  # useOrders.js, useWebSocket.js
│   │   │   ├── api/          # tradingApi.js
│   │   │   └── views/        # TradingView.vue, OrderHistoryView.vue
│   │   │
│   │   ├── asset/
│   │   │   ├── components/   # PortfolioCard.vue, AssetSummary.vue
│   │   │   ├── composables/  # useAssets.js
│   │   │   ├── api/          # assetApi.js
│   │   │   └── views/        # PortfolioView.vue
│   │   │
│   │   └── news/
│   │       ├── components/   # NewsCard.vue, NewsList.vue
│   │       ├── api/          # newsApi.js
│   │       └── views/        # NewsView.vue
│   │
│   ├── shared/               # 공통 모듈
│   │   ├── components/       # BaseButton.vue, BaseInput.vue, LoadingSpinner.vue
│   │   ├── composables/      # useLoading.js, useToast.js
│   │   ├── utils/            # format.js, validate.js
│   │   └── api/              # axios.js (인터셉터 설정)
│   │
│   ├── router/               # Vue Router
│   │   └── index.js
│   │
│   ├── stores/               # Pinia 스토어
│   │   ├── user.js
│   │   └── trading.js
│   │
│   ├── App.vue
│   └── main.js
│
├── public/                   # 정적 파일
├── index.html
├── vite.config.js
├── package.json
└── .env.local
```

## 폴더 구조 원칙

### features/ (도메인 기반)
- 각 기능별로 독립적인 모듈 구성
- 모듈 간 의존성 최소화
- 해당 기능의 components, composables, api, views 포함

### shared/ (공통)
- 2개 이상 모듈에서 사용하는 코드
- Base* 접두사 컴포넌트 (BaseButton, BaseInput)
- 공통 유틸리티 함수

## 네이밍 규칙

| 대상 | 패턴 | 예시 |
|------|------|------|
| 컴포넌트 | PascalCase | LoginForm.vue |
| View | {Name}View | TradingView.vue |
| Composable | use{Name} | useAuth.js |
| API | {domain}Api | tradingApi.js |
| Store | {domain}.js | user.js |

## 라우트 구조

| 경로 | 컴포넌트 | 인증 |
|------|----------|------|
| /login | LoginView | X |
| /register | RegisterView | X |
| / | HomeView | O |
| /trading | TradingView | O |
| /trading/:symbol | StockDetailView | O |
| /portfolio | PortfolioView | O |
| /orders | OrderHistoryView | O |
| /news | NewsView | O |

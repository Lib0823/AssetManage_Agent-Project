# API Server - Project Structure

## 디렉토리 구조

```
api-server/
├── src/main/java/com/inbeom/api/
│   ├── auth/                 # 인증/인가 도메인
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── domain/           # User, RefreshToken
│   │   └── dto/
│   │
│   ├── trading/              # 주식 거래 도메인
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── domain/           # Order, Execution, Stock
│   │   ├── dto/
│   │   └── client/           # 한국투자증권 API 클라이언트
│   │
│   ├── asset/                # 자산 관리 도메인
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── domain/           # Account, Asset
│   │   └── dto/
│   │
│   ├── news/                 # 뉴스 도메인
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── domain/           # News
│   │   └── dto/
│   │
│   ├── ai/                   # AI 연동 도메인
│   │   ├── controller/
│   │   ├── service/
│   │   ├── domain/           # AiSignal, AutoTradingConfig
│   │   └── dto/
│   │
│   └── common/               # 공통 모듈
│       ├── config/           # Security, JPA, Redis, Swagger
│       ├── exception/        # GlobalExceptionHandler, ErrorCode
│       ├── dto/              # ApiResponse, PageResponse
│       └── util/
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   ├── application-dev.yml
│   └── db/migration/         # Flyway 마이그레이션
│
└── src/test/java/            # 테스트
```

## 도메인별 역할

| 도메인 | 책임 |
|--------|------|
| auth | 회원가입, 로그인, JWT 토큰 관리 |
| trading | 시세 조회, 주문 생성/취소, 체결 관리, WebSocket |
| asset | 계좌 관리, 보유 종목, 수익률 계산 |
| news | 뉴스 수집, 종목별 뉴스 조회 |
| ai | AI Agent 연동, 자동매매 설정 |
| common | 설정, 예외처리, 공통 DTO |

## 계층 구조

```
Controller → Service → Repository → Database
              ↓
           Domain (Entity)
              ↓
           DTO (Request/Response)
```

## 네이밍 규칙

| 대상 | 패턴 | 예시 |
|------|------|------|
| Controller | {Domain}Controller | AuthController |
| Service | {Domain}Service | OrderService |
| Repository | {Entity}Repository | UserRepository |
| Entity | 단수형 | User, Order |
| DTO | {Action}{Domain}Request/Response | LoginRequest |

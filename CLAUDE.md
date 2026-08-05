# === DevKit 라우팅 규칙 (자동 생성) ===
<!-- setup-all 이 설치된 컴포넌트 블록만 골라 주입. 이미 이 블록이 있으면 교체(중복 주입 금지). -->

## [graphify 설치 시]
코드베이스 질문은 파일을 열기 전에 먼저 Graphify로 범위를 좁힌다.
graphify query/path/explain로 관련 노드를 찾고, 근거가 필요한 파일만 연다.
INFERRED 관계는 소스에서 재확인한다.

## [ontology 설치 시]
도메인/비즈니스 개념 질문은 devkit/ontology.yaml을 먼저 참조한다.
개체의 코드 근거가 필요하면 source_refs를 따라간다.
온톨로지 수정 시 ontology.yaml을 갱신하고 manual: true로 표시한다.

## [harness 설치 시]
복잡한 다단계 작업은 구축된 Agent Team이 자동 발동한다.
(실행에는 CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1 필요)

## [superpowers 설치 시]
TDD·디버깅·계획 수립 등 범용 작업 패턴은 설치된 superpowers 스킬이 자동 발동한다.
(이 규칙은 보험 — superpowers 스킬 자체의 pushy description이 1차 트리거, harness와 동일 계층)

# === /DevKit 라우팅 규칙 ===

## 하네스: FinanceManage_Agent 개발/유지보수

**목표:** web-app/api-server/ai-agent 3개 모듈에 걸친 개발·유지보수 작업을 담당 전문가에게 라우팅하고, 경계면(API 계약·DB 컬럼) 정합성을 지속 검증한다.

**트리거:** 기능 추가·버그 수정·리팩토링 등 개발 작업 요청 시 `finance-agent-orchestrator` 스킬을 사용하라. 단순 질문(코드 설명, 문서 조회)은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-01 | 초기 구성 (backend-engineer, ai-pipeline-engineer, frontend-engineer, integration-qa + finance-agent-orchestrator) | 전체 | /setup-all harness 최초 구축 |

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation Map (하네스 진입점)

작업 전, 전체 문서 지도와 모듈별 문서 진입점은 **[`_docs/README.md`](_docs/README.md)** 에서 시작하세요.

| 목적 | 문서 |
|------|------|
| 전체 문서 지도 / 길찾기 | [`_docs/README.md`](_docs/README.md) |
| 시스템 데이터 흐름·통신·파이프라인 다이어그램 | [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) |
| 전체 개발 현황 | [`_docs/STATUS.md`](_docs/STATUS.md) |
| 설치·실행 방법 | [`_docs/USAGE.md`](_docs/USAGE.md) |
| 프로젝트 소개 (사람용) | [`README.md`](README.md) |
| **web-app** 모듈 문서 | [`web-app/_docs/README.md`](web-app/_docs/README.md) |
| **api-server** 모듈 문서 | [`api-server/_docs/README.md`](api-server/_docs/README.md) |
| **ai-agent** 모듈 문서 | [`ai-agent/_docs/README.md`](ai-agent/_docs/README.md) |
| DB 스키마 | [`database/README.md`](database/README.md) · [`database/schema.sql`](database/schema.sql) |

각 모듈 디렉터리에는 자체 `CLAUDE.md`도 있습니다(`ai-agent/CLAUDE.md` 등). 모듈 내부를 작업할 때는 해당 `CLAUDE.md`와 `_docs/README.md`를 먼저 읽으세요.

## Project Overview

AI-powered stock auto-trading system that analyzes KOSPI top 100 stocks daily, filters to top 30 using ML scoring, performs 3-way analysis (quantitative features, sentiment analysis, time-series forecasting), and uses Gemini AI to execute buy/sell decisions via KIS mock trading API.

**Monorepo Structure:**
- `web-app/` — Vue3 SPA frontend (PWA-enabled)
- `api-server/` — Spring Boot backend for trading execution and REST API
- `ai-agent/` — Python FastAPI for ML pipeline, analysis, and AI decisions
- `database/` — PostgreSQL schema and ERD documentation

## Development Commands

### web-app (Vue3 Frontend)
```bash
cd web-app
npm install              # Install dependencies
npm run dev              # Development server (http://localhost:5173)
npm run build            # Production build
npm run preview          # Preview production build
npm run lint             # Run ESLint
npm run format           # Format code with Prettier
```

### api-server (Spring Boot Backend)
```bash
cd api-server
./gradlew bootRun        # Run development server (port 7070)
./gradlew build          # Build project (build/libs/*.jar)
./gradlew test           # Run tests
./gradlew clean          # Clean build artifacts
```
> Requires `JWT_SECRET` and `JASYPT_PASSWORD` environment variables (see `.env.example`). Liquibase auto-migrates the schema on startup.

### ai-agent (FastAPI)
```bash
cd ai-agent
./run_dev.sh             # venv 자동 생성 + 의존성 설치 + http://localhost:8000
```
> **반드시 venv 안에서 실행.** 시스템 python3로 직접 실행하면 Prophet이 깨져 `prophet_forecast`가 NULL로 저장됨.

### Full System (Docker Compose)
```bash
cp .env.example .env             # 외부 API 키 (비워도 기동됨)
docker compose up -d --build     # 4개 서비스 전체 기동 (최초 빌드 수~십 분)
docker compose up -d postgres    # DB만 (로컬 개발 시)
docker compose down              # 중지
docker compose logs -f
```

**Container Services (모두 활성):**
- `web-app` → Nginx (port 3000, `/api`는 api-server로 프록시)
- `api-server` → Spring Boot (port 7070, context-path `/api`)
- `ai-agent` → FastAPI (port 8000, torch/prophet/KR-FinBERT 포함 → 이미지 수 GB)
- `postgres` → PostgreSQL (port 5432)
- `elasticsearch` → (port 9200) 코드 미사용이라 compose에서 주석 처리

> Dockerfile: `api-server/Dockerfile`(멀티스테이지 JDK21→JRE21), `ai-agent/Dockerfile`(python3.11 + fonts-nanum), `web-app/Dockerfile`(node 빌드→nginx). 시크릿은 루트 `.env`를 `env_file`로 주입. 상세: [`_docs/USAGE.md`](_docs/USAGE.md)

## Architecture & Data Flow

전체 다이어그램은 [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) 참고.

### Service Communication Pattern
```
Vue3 → Spring Boot (7070)     : 인증, 대시보드, 자산, 거래내역, 설정, 시장 분석, 종목 상세
Spring Boot → KIS API          : 주문 실행, 잔고/시세 조회
Spring Boot → DART API         : 기업 재무·공시 조회
Spring Boot ⇄ PostgreSQL       : 사용자/인증/설정/거래 이력 + AI 분석 결과 조회
ai-agent → KIS / DART / News   : 분석용 원천 데이터 수집
ai-agent → Gemini API          : 11개 피처 기반 매수/매도 판단
ai-agent ⇄ PostgreSQL          : 분석 결과, 예측, AI 판단, 안전망 필터 저장
ai-agent → Spring Boot         : is_active=true일 때 매매 실행 요청
```
> web-app은 ai-agent를 직접 호출하지 않습니다. ai-agent가 DB에 쓴 분석 결과를 Spring Boot의 `MarketAnalysisController`/`MarketDataController`/`CompanyController`가 중계합니다.

### Daily Pipeline Flow (APScheduler @ 평일 08:50 KST)
> **스케줄 범위**: 자동 스케줄(`run_complete_pipeline_sync`)이 **전체 파이프라인(Stage 1~6)**을 실행. 수동 트리거 `POST /api/pipeline/trigger`(`run_complete_pipeline`)도 동일하게 전체 실행. 상세: [`ai-agent/_docs/PIPELINE_DESIGN.md`](ai-agent/_docs/PIPELINE_DESIGN.md)

1. **Stage 0 — 휴장일 체크**: 주말·공휴일이면 중단
2. **Stage 1 — Stock Filtering**: KOSPI 100 → StandardScaler scoring → Top 30
   - `score = abs(foreign_net_buy)*0.3 + abs(institutional_net_buy)*0.3 + vol_avg_multiple*0.3 + price_volatility*0.1`
   - StandardScaler는 매일 당일 100종목 기준으로 새로 fit, 보유 종목은 final 30에 강제 포함
3. **Stage 2 — Data Collection**: KIS API (asyncio 병렬, 5 req/sec), 뉴스(RSS + 네이버), DART 재무
4. **Stage 3 — 3-Way Analysis**:
   - **Quantitative**: 4 KIS features + 3 DART financials
   - **Sentiment**: KR-FinBERT (track 1: 시장 뉴스, track 2: 종목 뉴스)
   - **Time-Series**: Prophet 120-day forecasting → D+1~D+5 trends
   - (matplotlib 차트 생성은 **미구현** — `/static/charts/` 없음)
5. **Stage 4 — AI Decision**: Gemini API가 11 피처 판단 → Buy/Sell TOP3 → `ai_trade_decision`
6. **Stage 5 — Safety Filter**: 임계값 기반 사후 검증 → `safety_filter_result`
7. **Stage 6 — Trade Execution**: `is_active=true`면 POST to Spring Boot → KIS 주문 → `trade_execution_plan`

### Frontend Architecture (Vue3)
- **Router**: Vue Router 4 with lazy-loaded views
- **State**: Pinia (`stores/auth.js` 등) + LocalStorage (토큰/UI 설정)
- **API Layer**: Axios with request/response interceptors, 401 시 RefreshToken 자동 갱신 (`web-app/src/services/api.js`)
- **Styling**: Tailwind CSS 4.1 + Vant UI
- **Build**: Vite 7.3 with PWA plugin
- **Auth Guard**: Dev mode skips auth, production checks localStorage token

상세 화면·라우팅은 [`web-app/_docs/README.md`](web-app/_docs/README.md) 참고.

**Key Routes:**
- `/` → Splash · `/home` → 대시보드 · `/assets` → 자산 · `/bot` → AI 봇 · `/search` → 검색 · `/news` → 뉴스 · `/profile`, `/settings` → 사용자 관리

### Backend Architecture (Spring Boot)
- **Java**: 21 (LTS, toolchain)
- **Framework**: Spring Boot 4.1.0-SNAPSHOT
- **ORM**: Spring Data JPA
- **Database**: PostgreSQL, **schema managed by Liquibase** (`src/main/resources/db/changelog/`)
- **Security**: Spring Security + **JWT 완비** (Access + RefreshToken). `io.jsonwebtoken:jjwt 0.12.3`
- **Encryption**: Jasypt (`PBEWITHHMACSHA512ANDAES_256`) — KIS 키 암호화 저장
- **Build**: Gradle · **Testing**: JUnit 5 + Mockito

**Package Structure (`com.inbeom.apiserver`):**
```
controller/   AuthController, AssetController, TradingController, UserController,
              MarketAnalysisController, MarketDataController, CompanyController,
              HealthController
service/      AuthService, UserService, TradingService, KisAuthService 등
domain/       User, RefreshToken, UserKisAccount, UserTradeConfig, UserSettings, TradeHistory
repository/   Spring Data JPA repositories
dto/          auth, trade, kis, user, common, market, company 하위 패키지
client/       KIS / DART 외부 API 클라이언트
config/       SecurityConfig, CorsConfig 등
security/     JwtAuthenticationFilter, CustomUserDetails, CustomUserDetailsService
util/         JwtTokenProvider
exception/    GlobalExceptionHandler, BusinessException, ErrorCode 등
```
> CLAUDE.md 이전 버전은 "ApiServerApplication.java 단일 파일" / "no JWT yet"로 적혀 있었으나, 실제로는 위와 같이 다층 구조이며 JWT/RefreshToken이 완비되어 있습니다.

상세 API/인증 흐름은 [`api-server/_docs/README.md`](api-server/_docs/README.md), [`api-server/_docs/AUTHENTICATION_FLOW.md`](api-server/_docs/AUTHENTICATION_FLOW.md) 참고.

### AI Pipeline Architecture (FastAPI)
- **Scheduler**: APScheduler (평일 08:50 KST)
- **구조**: `pipeline/`(orchestrator, scheduler), `analysis/`(filter, quantitative, sentiment, timeseries), `collectors/`, `models/`, `ai/`, `filters/`(safety_filter), `execution/`(trade_executor), `database/`
- **ML Stack**: pandas, NumPy, scikit-learn (StandardScaler), Prophet
- **NLP**: transformers (KR-FinBERT)
- **Charts**: matplotlib + NanumGothic font (현재 차트 생성 단계는 미구현)
- **AI**: Gemini API (무료 티어, 1 call/day)
- **Async**: asyncio for parallel KIS API calls (rate limit: 5 req/sec)

상세 6단계 플로우는 [`ai-agent/_docs/PIPELINE_DESIGN.md`](ai-agent/_docs/PIPELINE_DESIGN.md), 모듈 지침은 [`ai-agent/CLAUDE.md`](ai-agent/CLAUDE.md) 참고.

**Chart Files (Static Serving):**
- `heatmap_today.png` → 11 features × 30 stocks heatmap
- `quant_features_today.png` → Foreign/institutional net buy + volume bars
- `sentiment_today.png` → Sentiment scores by stock
- `prophet_forecast_today.png` → Top 3 buy predictions with confidence intervals

## Database Schema

**실제 테이블: 21개 + 뷰 4개** (Liquibase가 20개 changelog로 생성, v1.0~v1.19). **스키마 단일 출처는 Liquibase changelog**(`api-server/src/main/resources/db/changelog/`)이며, [`database/schema.sql`](database/schema.sql)은 라이브 DB에서 뽑은 참고용 스냅샷입니다(자동 생성 — `database/generate-schema.sh`, 직접 편집 금지). 전체 목록·관계는 [`database/README.md`](database/README.md).

| 그룹 | 테이블 |
|------|--------|
| 사용자 & 인증 | `users`, `refresh_tokens`, `user_kis_accounts`, `user_trade_config`, `user_settings`, `webauthn_credentials` |
| 분석 데이터 | `stock_filter_score`, `stock_financial`, `news_analysis`, `stock_news`, `prophet_forecast`, `ai_trade_decision`, `safety_filter_result` |
| 웹 표시용 | `market_daily_summary`, `stock_realtime_price`, `asset_daily_snapshot` |
| 매매 실행 | `trade_execution_plan`, `feature_threshold_config`, `trade_history` |
| 검색 & 관심종목 | `stock_master`, `user_favorites` |
| 뷰 | `v_latest_trade_plan`, `v_decision_with_filter`, `v_market_overview`, `v_stock_analysis_summary` |

> 테이블 수 변경 이력: v1.8에서 `stock_master`/`user_favorites` 추가(19개), v1.10 `stock_news`·v1.14 `webauthn_credentials`·v1.17 `asset_daily_snapshot` 추가로 22개, v1.18에서 `safety_filter_result.decision` 컬럼 추가(테이블 수 변화 없음), v1.19에서 아무도 읽거나 쓰지 않던 `user_holdings`를 제거해 현재 21개입니다.

## Technology Stack Summary

| Layer | Technology |
|-------|-----------|
| Frontend | Vue 3.5 (Composition API), Vite 7.3, Vue Router 4, Pinia, Tailwind CSS 4.1, Chart.js, Vant UI, PWA |
| Backend API | Spring Boot 4.1, Java 21, Spring Data JPA, Spring Security + JWT(jjwt 0.12.3), Jasypt, Liquibase, PostgreSQL, Gradle |
| AI Pipeline | Python 3.11+, FastAPI, APScheduler, pandas, NumPy, scikit-learn, Prophet, transformers (KR-FinBERT), matplotlib |
| AI Model | Gemini API (free tier) |
| Database | PostgreSQL 16 (21 tables + 4 views) |
| Search | Elasticsearch 8.x (확장 예정) |
| Infra | Docker, Docker Compose |
| External APIs | KIS Developers (mock trading), DART (financial data) |

## Important Development Notes

### Frontend (web-app)
- **API Base URL**: `VITE_API_BASE_URL` 환경변수, 기본값 `http://localhost:7070/api`
- **Auth**: Dev mode (`import.meta.env.DEV`) skips auth checks; 401 시 RefreshToken으로 자동 갱신
- **PWA**: installable, service worker configured in vite.config.js
- **Navigation**: bottom nav via `meta.showBottomNav` in route config
- **Styling**: 2-space indentation, single quotes for JS strings

### Backend (api-server)
- **Java 21 Required** (toolchain in build.gradle)
- **Lombok**: getters/setters/constructors
- **Schema**: Liquibase 가 source of truth — 스키마 변경은 직접 SQL 아닌 changelog 파일 수정
- **환경변수**: `JWT_SECRET`(256bit 이상), `JASYPT_PASSWORD` 필수
- **Indentation**: 4-space for Java files
- **알려진 이슈**: 예외 체계 변경 후 일부 테스트가 stale 할 수 있음 ([`_docs/STATUS.md`](_docs/STATUS.md) 참고)

### AI Pipeline (ai-agent)
- **venv 필수**: 시스템 python3 직접 실행 시 Prophet 깨짐 → `prophet_forecast` NULL
- **Rate Limiting**: KIS API 5 req/sec, asyncio.Semaphore(5) + 0.2s 간격
- **Scheduling**: APScheduler (프로그램 내 설정, 평일 08:50 KST). 자동 스케줄이 전체 파이프라인(Stage 1~6) 실행. 수동 트리거 `POST /api/pipeline/trigger`도 동일
- **Data Flow**: 보유 종목을 final 30에 강제 포함 (매도 분석 가능하게)
- **Charts**: matplotlib 차트 생성 단계는 미구현 (NanumGothic 폰트는 추후 차트 추가 시 필요)

### Database (database/)
- **단일 출처**: Liquibase (`api-server/src/main/resources/db/changelog/`, mvp context) — 스키마 변경은 여기에 changeset 추가
- **Schema File**: `database/schema.sql` — `pg_dump`로 뽑은 참고용 스냅샷. 직접 편집하지 말고 `database/generate-schema.sh`로 재생성. changeset 추가 후 재생성이 밀리면 실제 스키마보다 낡을 수 있으므로, 컬럼 존재 확인은 changelog를 기준으로 한다
- **문서**: `database/README.md` (테이블 목록·관계·인덱스 전략)

### Project-Specific Context
대학원 최종 프로젝트(MVP). 단일 사용자(admin) 기준, KIS 모의투자, 무료 Gemini 티어 전제. 멀티 유저·실전 매매·운영 배포는 향후 과제. KIS 키는 `user_kis_accounts`에 Jasypt 암호화 저장, KOSPI 100 종목 코드는 ai-agent에 하드코딩.

### Git Workflow
- Main branch: `main`
- Current development branch: `develop-analysis`
- 커밋 규약: Conventional Commits (`feat`, `fix`, `docs`, ...)

### Analysis View Files
- `web-app/analysis_view/overview.html`, `web-app/analysis_view/stock_detail.html` — Vue3 라우터와 별개의 정적 HTML 분석 화면

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

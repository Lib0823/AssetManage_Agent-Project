# Database Schema

AI 주식 자동매매 시스템 데이터베이스 스키마 문서

## 개요

- **DBMS**: PostgreSQL 16
- **총 테이블 수**: 21개 (+ 뷰 4개)
- **스키마**: 단일 public 스키마 (MVP 단순화)
- **스키마 소스(편집 대상)**: **Liquibase** changelog (`api-server/src/main/resources/db/changelog/`)

## 파일 구조

```
database/
├── schema.sql           # 전체 DDL 스냅샷 (자동 생성 — 편집 금지)
├── generate-schema.sh   # schema.sql 재생성 스크립트
└── README.md            # 이 파일 (사람·AI용 스키마 참조: 테이블 카탈로그·ERD·흐름)
```

### 단일 소스 원칙 (Single Source of Truth)

스키마 관리 지점은 **Liquibase changelog 하나**입니다. `database/`는 여러 모듈(api-server·ai-agent 등)이 공유하는 **참조 지점**입니다.

| 파일 | 역할 | 편집 |
|------|------|------|
| `api-server/.../db/changelog/` | 스키마 소스 (Liquibase가 부팅 시 적용) | ✅ **여기만 편집** |
| `database/schema.sql` | 적용된 스키마의 전체 DDL 스냅샷 | ❌ 자동 생성 (`pg_dump`) |
| `database/README.md` | 사람·AI용 카탈로그/ERD/흐름 | ✅ 설명 보강 |

**스키마 변경 워크플로우**:
1. `api-server/src/main/resources/db/changelog/`에 changeset 추가/수정
2. `docker compose up -d --build api-server` (또는 로컬 `./gradlew bootRun`) → Liquibase가 DB에 적용
3. `./database/generate-schema.sh` → `schema.sql` 재생성 (라이브 DB에서 `pg_dump -s`)
4. 필요 시 이 README의 테이블 카탈로그 갱신

> `schema.sql`을 직접 실행하거나 손으로 수정하지 마세요. 항상 changelog가 소스입니다.

> `schema.sql`은 v1.19(`user_holdings` 제거)까지 반영된 스냅샷이다. 이후 changelog가 추가되면 다시 밀릴 수 있으니, 스키마 관련 작업 전에는 재생성 여부를 확인할 것 — schema.sql이 changelog보다 오래된 상태로 방치되면 이 파일을 근거로 정상 동작하는 코드의 컬럼/테이블을 잘못 지우는 사고로 이어질 수 있다(DB 없이 DDL을 손으로 고치는 것도 금지 — 스냅샷 위조가 된다).

상위 시스템 데이터 흐름은 [`../_docs/ARCHITECTURE.md`](../_docs/ARCHITECTURE.md) 참고.

---

## 테이블 목록

### 1. 사용자 & 인증 (6개)

| 테이블명 | 설명 | 주요 컬럼 |
|---------|------|-----------|
| `users` | 사용자 기본 정보 | username, email, password(BCrypt), name, phone, birth_date |
| `refresh_tokens` | JWT 리프레시 토큰 | user_id, token, expires_at, revoked_at |
| `user_kis_accounts` | 사용자별 KIS API 키 (1:1) | user_id, account_number, app_key, app_secret (Jasypt 암호화), is_verified, **hts_id**(v1.11, 평문 — 실시간 체결통보 tr_key), **account_mode**(v1.15, MOCK/REAL — 도메인·TR 라우팅 기준, 기본 MOCK) |
| `user_trade_config` | 자동매매 설정 (1:1) | user_id, order_amount, max_holdings, order_type, **is_active** |
| `user_settings` | UI 설정 (1:1) | user_id, asset_order(JSONB), dark_mode, auto_login, notifications(JSONB) |
| `webauthn_credentials` | WebAuthn 생체/패스키 자격증명 (v1.14) | credential_id(Base64URL, unique), user_id FK→users(id), public_key_cose, signature_count, transports, created_at |

> `webauthn_credentials`는 생체/패스키 로그인용 자격증명을 저장한다. `credential_id`↔`user_id` **1:1 매핑**이며, WebAuthn user_handle 은 `user_id`에서 8바이트로 파생한다(별도 컬럼 없음, usernameless 로그인 시 역조회). 등록은 가입 이후 JWT 인증 상태에서(`/auth/webauthn/register/*`), 로그인은 public usernameless(`/auth/webauthn/login/*`)로 수행한다. 상세는 [`../api-server/_docs/AUTHENTICATION_FLOW.md`](../api-server/_docs/AUTHENTICATION_FLOW.md)의 WebAuthn 섹션 참고.

### 2. 분석 데이터 (7개)

| 테이블명 | 설명 | 갱신 주기 | 쓰는 쪽 |
|---------|------|-----------|---------|
| `stock_filter_score` | 코스피 100 스코어링 (→ 30 선정) | 매일 (평일 08:50) | ai-agent |
| `stock_financial` | DART 재무지표 (PER, ROE, 영업이익률) | 분기별 | ai-agent |
| `news_analysis` | KR-FinBERT 감성 분석 (집계) | 매일 | ai-agent |
| `prophet_forecast` | Prophet D+1~D+5 시계열 예측 | 매일 | ai-agent |
| `ai_trade_decision` | Gemini AI 매수/매도 판단 (TOP3) | 매일 | ai-agent |
| `safety_filter_result` | Gemini 판단 사후 검증 (안전망 필터) | 매일 | ai-agent |
| `stock_news` | 종목별 뉴스 기사 원문 저장 (제목/요약/URL/감성/태그 JSONB) | 매일 | ai-agent |

> `stock_news`는 종목별 뉴스 기사 단건을 저장한다(`news_analysis`는 종목 단위 감성 집계). ai-agent가 `(stock_code, analysis_date)` 기준으로 DELETE 후 INSERT 하므로 unique 제약은 없다. `tags`(JSONB, 문자열 배열)에는 GIN 인덱스(`idx_stock_news_tags`, `jsonb_path_ops`)가 있어 향후 태그 검색에 사용한다. Spring Boot의 `StockNewsController`(`/api/news`)가 읽기 전용으로 중계한다.

### 3. 웹 표시용 (2개)

| 테이블명 | 설명 | 비고 |
|---------|------|------|
| `market_daily_summary` | 시장 전체 일일 요약 (KOSPI 지수, 등락 종목수, 시장 감성) | 대시보드용 |
| `stock_realtime_price` | 종목별 실시간 가격 (캐시) | 현재가, 등락률, 거래량. **채우는 코드가 없어 항상 null** — `MarketAnalysisRepository`가 LEFT JOIN으로 참조하므로 고아 테이블은 아니지만, writer가 없다는 점은 알고 있어야 한다 |

> `user_holdings`(사용자별 보유 종목 현황)는 v1.19에서 제거했다. api-server·ai-agent 어디서도 읽거나 쓰지 않는 순수 고아 테이블이었다 — 보유 종목은 KIS 실시간 조회(`TradingService.getHoldings` 등)로 대체됐다.

### 4. 매매 실행 & 거래 (3개)

| 테이블명 | 설명 | 관리 주체 |
|---------|------|-----------|
| `trade_execution_plan` | 안전망 필터 통과 후 매매 계획 및 실행 결과 | ai-agent |
| `feature_threshold_config` | 안전망 필터 임계값 (BUY/SELL 규칙, 동적 조정) | ai-agent (기본값 seed) |
| `trade_history` | 주문 체결 이력 (KIS 주문번호, 상태, 체결가) | Spring Boot |

### 5. 검색 & 관심종목 (2개)

| 테이블명 | 설명 | 관리 주체 |
|---------|------|-----------|
| `stock_master` | 종목 검색 카탈로그 (code/name/market/**exchange_code/currency**). KOSPI + US 종목 seed | Spring Boot (Liquibase seed) |
| `user_favorites` | 사용자별 관심종목 (user_id, stock_code, stock_name 비정규화) | Spring Boot |

> `stock_master`는 `ai-agent/config/constants.py`의 `STOCK_NAMES`(+`005930` 삼성전자, `055550` 신한지주)를 seed한 검색 카탈로그(`market='KOSPI'`)에 더해, **v1.9에서 해외주식(US) 지원 컬럼·시드를 추가**했다: `exchange_code`(VARCHAR(10), 해외 거래소 NASD/NYSE/AMEX, 국내=NULL), `currency`(VARCHAR(3), 기본 `KRW`). v1.9 시드로 NASDAQ 15 + NYSE 10 = **25개 미국 종목**(`currency='USD'`)을 적재한다. `market=US` 검색은 이 USD 행을 대상으로 한다. `user_favorites`는 `(user_id, stock_code)` unique, `user_id` FK→`users(id)` cascade.

### 6. 뷰 (4개)

| 뷰명 | 설명 |
|------|------|
| `v_latest_trade_plan` | 당일 매매 계획 요약 (계획/통과/실행 건수, 예상 금액) |
| `v_decision_with_filter` | Gemini 판단 + 안전망 필터 결합 결과 (최근 7일) |
| `v_market_overview` | 시장 일일 요약 (KOSPI 지수·등락·수급·시장감성) + 당일 매수/매도 시그널·필터 통과 집계 |
| `v_stock_analysis_summary` | 종목별 11개 피처 통합 (스코어·수급·재무·감성·시계열·실시간가 결합) |

---

## 데이터 흐름

```
[FastAPI 파이프라인 @ 평일 08:50]
    ↓
stock_filter_score        ← 코스피 100 스코어링 → Top 30 (is_selected)
    ↓
news_analysis             ← KR-FinBERT 감성 분석
prophet_forecast          ← Prophet 시계열 예측
(stock_financial 조회)    ← DART 재무지표 (분기별 적재분 사용)
    ↓
ai_trade_decision         ← Gemini AI 판단 (buy/sell/hold TOP3)
    ↓
safety_filter_result      ← 임계값 기반 사후 검증
    ↓
trade_execution_plan      ← 통과 종목 매매 계획
    ↓
[Spring Boot, is_active=true]
    ↓
trade_history             ← KIS API 체결 결과
```

---

## 중요 컬럼

### user_trade_config.is_active
- 자동매매 ON/OFF 플래그
- `FALSE`: 파이프라인 분석만 수행, 매매 실행 안 함
- `TRUE`: 분석 + Gemini 판단 → Spring Boot로 매매 요청

### stock_filter_score.is_selected
- `TRUE`: 분석 대상 30개 종목
- `FALSE`: 코스피 100에는 포함되지만 분석 제외

### ai_trade_decision.decision
- `buy`: 매수 추천 · `sell`: 매도 추천 · `hold`: 보유 유지
- `feature_summary` (JSONB): 판단 근거가 된 주요 피처값

### safety_filter_result.filter_checks (JSONB)
- 각 규칙별 통과 여부 (예: `uncertainty_check`, `foreign_net_buy_check`, `sentiment_check` 등)

### feature_threshold_config
- 안전망 필터 임계값을 동적으로 관리. Liquibase changelog가 11개 피처 기본 규칙을 seed
  (`foreign_net_buy`, `institutional_net_buy`, `sentiment_score`, `prophet_price_trend`,
  `prophet_volume_trend`, `prophet_price_uncertainty`, `per`, `roe`, `operating_margin`,
  `morning_return`, `close_position`)

---

## 마이그레이션 (Liquibase)

**⚠️ 데이터베이스 스키마는 Liquibase로 관리됩니다.**

모든 스키마 변경은 `api-server/src/main/resources/db/changelog/`에서 관리되며, Spring Boot 시작 시 자동 적용됩니다.

### 디렉토리 구조

```
api-server/src/main/resources/db/changelog/
├── db.changelog-master.yaml          # 마스터 changelog
├── mvp/                              # MVP context
│   ├── v1.0-users-auth.yaml          # 사용자 인증 및 설정
│   ├── v1.1-analysis-tables.yaml     # AI 분석 데이터
│   ├── v1.2-trade-history.yaml       # 매매 이력
│   ├── v1.3-schema-updates.yaml      # KIS 연동 스키마 업데이트
│   ├── v1.4-test-data.yaml           # 테스트 데이터
│   ├── v1.5-user-settings.yaml       # 사용자 UI 설정
│   ├── v1.6-stage4-5-enhancements.yaml  # 안전망 필터 + 매매 실행 계획
│   ├── v1.7-web-display-tables.yaml  # 시장 요약 / 실시간 가격 / 보유 종목
│   ├── v1.8-stock-master-favorites.yaml  # 종목 검색 카탈로그 + 관심종목
│   ├── v1.9-overseas-stock-master.yaml   # 해외주식(US) 컬럼(exchange_code/currency) + US 시드
│   └── v1.10-stock-news.yaml             # 종목별 뉴스 기사 저장 (stock_news + GIN 태그 인덱스)
└── product/                          # 프로덕션 context (향후)
```

### Context 구분
- **mvp**: 최소 기능 제품 스키마 (단일 사용자, 핵심 기능)
- **product**: 프로덕션 스키마 (멀티유저, 권한, 감사로그 등 — 향후)

### 설정

```yaml
# api-server/src/main/resources/application.yml
spring:
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml
    enabled: true
    contexts: mvp
```

### Liquibase 추적 테이블
- `databasechangelog`: 실행된 changeset 이력
- `databasechangeloglock`: 동시 실행 방지 락

---

## 초기 데이터

- `users`: admin 계정 (username: `admin`, password: `admin123` BCrypt)
- `user_trade_config`: admin 투자 설정 (is_active `FALSE`)
- `feature_threshold_config`: 11개 피처 기본 임계값
- 개발용 테스트 계정(`testuser` / `password123`)은 Liquibase `v1.4-test-data.yaml`로 적재

---

## 인덱스 전략

- **사용자 조회**: `username`, `email`
- **일자 조회**: `score_date`, `analysis_date`, `forecast_date`, `decision_date`, `filter_date`, `execution_date`
- **종목 조회**: `stock_code`
- **복합 조회**: `(stock_code, date)`, `(user_id, stock_code)`, `(decision_date, decision, rank)`

## 보안 고려사항

- `users.password` → BCrypt
- `user_kis_accounts.app_key` / `app_secret` → Jasypt (AES-256) 암호화 저장
- `refresh_tokens.token` → JWT 자체 서명 (평문 저장)
- 애플리케이션 레벨에서 사용자별 데이터 격리

## 향후 확장 계획

1. **다중 계좌 지원**: `user_kis_accounts` 1:N 관계로 변경
2. **포트폴리오 관리**: 포지션·자산 배분 테이블 확장
3. **알림 기능**: `notifications` 테이블 추가
4. **뉴스 원문 저장**: `news_articles` 테이블 추가
5. **ML 모델 성능 추적**: `model_performance` 테이블 추가

# API_REFERENCE — DB 스키마 & Spring Boot 조회 참조

> 이 문서는 ai-agent가 기록하는 DB 테이블의 **실제 스키마**(`database/schema.sql` 기준)와, api-server(Spring Boot)에서 이를 조회할 때의 쿼리/주의사항을 정리한다.
> 파이프라인 알고리즘은 [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md), 구조는 [ARCHITECTURE.md](ARCHITECTURE.md) 참고.

---

## 1. 테이블 개요

| 테이블 | 작성 주체 | 용도 | 갱신 주기 |
| --- | --- | --- | --- |
| `stock_filter_score` | AI Agent | Top 30 필터 점수 + KIS 정량 피처 | 매일 |
| `market_daily_summary` | AI Agent | KOSPI 지수 및 시장 요약 | 매일 |
| `stock_financial` | AI Agent (DART) | 분기 재무지표 | 분기 |
| `news_analysis` | AI Agent | 뉴스 감성 점수 | 매일 |
| `prophet_forecast` | AI Agent | D+1~D+5 예측 | 매일 |
| `ai_trade_decision` | AI Agent | Gemini 매수/매도 TOP3 | 매일 |
| `safety_filter_result` | AI Agent | Safety Filter 검증 결과 | 매일 |
| `trade_history` | api-server | KIS 주문 체결 이력 | 거래 시 |
| `user_trade_config` | api-server | 자동매매 설정(`is_active`, `order_amount`) | 설정 변경 시 |

> AI Agent가 사용하는 SQLAlchemy 모델은 `database/models.py`에 `StockFilterScore`, `MarketDailySummary`, `ProphetForecast` 3종만 정의되어 있다. 나머지 테이블은 raw SQL(repository) 또는 DART 클라이언트로 기록한다.

---

## 2. 핵심 테이블 스키마 (schema.sql 기준)

### 2-1. stock_filter_score

```sql
CREATE TABLE stock_filter_score (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL,
    stock_name VARCHAR(50) NOT NULL,
    score_date DATE NOT NULL,                  -- ⚠️ trade_date 아님
    foreign_net_buy BIGINT NOT NULL,
    institutional_net_buy BIGINT NOT NULL,     -- ⚠️ institution_net_buy 아님
    vol_avg_multiple NUMERIC(10, 2) NOT NULL,  -- ⚠️ volume_ratio 아님
    price_volatility NUMERIC(10, 4) NOT NULL,
    scaler_score NUMERIC(10, 4) NOT NULL,      -- ⚠️ final_score 아님
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    morning_return NUMERIC(10, 4),             -- Stage 2-1-B UPDATE
    close_position NUMERIC(5, 4),              -- Stage 2-1-B UPDATE
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, score_date)
);
```

### 2-2. market_daily_summary

```sql
CREATE TABLE market_daily_summary (
    id BIGSERIAL PRIMARY KEY,
    summary_date DATE NOT NULL UNIQUE,
    kospi_index NUMERIC(10, 2),
    kospi_change_rate NUMERIC(6, 2),
    kospi_volume BIGINT,
    total_stocks INT,
    rising_stocks INT,            -- 현재 NULL (Stage 1 미수집)
    falling_stocks INT,           -- 현재 NULL
    unchanged_stocks INT,         -- 현재 NULL
    total_foreign_net_buy BIGINT,
    total_institutional_net_buy BIGINT,
    market_sentiment_score NUMERIC(5, 3),  -- Stage 2-2 UPDATE
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 2-3. stock_financial

```sql
CREATE TABLE stock_financial (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL,
    stock_name VARCHAR(50) NOT NULL,
    base_date DATE NOT NULL,             -- 분기말 기준일
    per NUMERIC(10, 2),                  -- KIS 시세로 보강, 적자/결측 시 NULL
    roe NUMERIC(10, 2),
    operating_margin NUMERIC(10, 2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, base_date)
);
```

### 2-4. news_analysis

```sql
CREATE TABLE news_analysis (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10),                  -- NULL = 시장 전반(Track 1)
    analysis_date DATE NOT NULL,
    sentiment_score NUMERIC(5, 3) NOT NULL,  -- -1.0 ~ 1.0
    news_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, analysis_date)
);
```

### 2-5. prophet_forecast

```sql
CREATE TABLE prophet_forecast (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL,
    stock_name VARCHAR(50) NOT NULL,
    forecast_date DATE NOT NULL,             -- ⚠️ trade_date 아님
    yhat_d1 NUMERIC(12, 2), ... yhat_d5 NUMERIC(12, 2),
    yhat_upper_d1 NUMERIC(12, 2), ... yhat_upper_d5,
    yhat_lower_d1 NUMERIC(12, 2), ... yhat_lower_d5,
    price_trend NUMERIC(10, 6),              -- ⚠️ prophet_price_trend 아님
    volume_trend NUMERIC(10, 6),             -- ⚠️ prophet_volume_trend 아님
    price_uncertainty NUMERIC(10, 4),        -- ⚠️ prophet_price_uncertainty 아님
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, forecast_date)
);
```

### 2-6. ai_trade_decision

```sql
CREATE TABLE ai_trade_decision (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL,
    stock_name VARCHAR(50) NOT NULL,
    decision_date DATE NOT NULL,             -- ⚠️ trade_date 아님
    decision VARCHAR(10) NOT NULL,           -- ⚠️ decision_type 아님. 값 'BUY'/'SELL'
    rank INT,                                -- 1, 2, 3 (TOP3)
    reason TEXT,
    prompt_summary TEXT,
    confidence_score DECIMAL(5, 4),          -- 추후 확장용
    feature_summary JSONB,                   -- 추후 확장용
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, decision_date, decision)
);
```

### 2-7. safety_filter_result

```sql
CREATE TABLE safety_filter_result (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL,
    stock_name VARCHAR(50) NOT NULL,
    filter_date DATE NOT NULL,
    decision VARCHAR(10) NOT NULL,           -- 'BUY'/'SELL'
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason TEXT,
    feature_values JSONB,
    max_quantity INT,                        -- 투자한도 기준 최대 매수 수량
    current_price BIGINT,                    -- 필터 시점 현재가
    filter_checks JSONB,                     -- 규칙별 통과 여부
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stock_code, filter_date, decision)
);
```

> 전체 스키마(users/refresh_tokens/trade_history/trade_execution_plan/feature_threshold_config/뷰 등)의 단일 출처(source of truth)는 Liquibase changelog(`api-server/src/main/resources/db/changelog/`)다. 루트 [`database/schema.sql`](../../database/schema.sql)은 이를 적용한 DB에서 뽑은 참고용 스냅샷이므로, 컬럼 존재 여부가 문제될 때는 changelog를 확인한다.

---

## 3. 컬럼명 주의 요약 (DataFrame ↔ DB)

api-server에서 조회 시 **항상 실제 DB 컬럼명**을 사용해야 한다. AI Agent 코드의 내부 DataFrame 컬럼명과 다른 항목:

| 내부 DataFrame / 구 문서 표기 | 실제 DB 컬럼 | 테이블 |
| --- | --- | --- |
| `trade_date` | `score_date` | stock_filter_score |
| `institution_net_buy` | `institutional_net_buy` | stock_filter_score |
| `volume_ratio` | `vol_avg_multiple` | stock_filter_score |
| `final_score` | `scaler_score` | stock_filter_score |
| `trade_date` | `forecast_date` | prophet_forecast |
| `prophet_price_trend` | `price_trend` | prophet_forecast |
| `prophet_volume_trend` | `volume_trend` | prophet_forecast |
| `prophet_price_uncertainty` | `price_uncertainty` | prophet_forecast |
| `trade_date` | `decision_date` | ai_trade_decision |
| `decision_type` | `decision` | ai_trade_decision |

---

## 4. 대표 조회 쿼리

### 4-1. 오늘의 Top 30 종목

```sql
SELECT stock_code, stock_name, scaler_score,
       foreign_net_buy, institutional_net_buy
FROM stock_filter_score
WHERE score_date = CURRENT_DATE AND is_selected = TRUE
ORDER BY scaler_score DESC;
```

### 4-2. 오늘의 AI 매수/매도 결정

```sql
SELECT stock_code, stock_name, decision, rank, reason
FROM ai_trade_decision
WHERE decision_date = CURRENT_DATE
ORDER BY decision, rank;
```

### 4-3. 종목 11개 피처 통합 조회

```sql
SELECT sfs.stock_code, sfs.stock_name, sfs.score_date,
       sfs.foreign_net_buy, sfs.institutional_net_buy,
       sfs.morning_return, sfs.close_position,
       sf.per, sf.roe, sf.operating_margin,
       na.sentiment_score,
       pf.price_trend, pf.volume_trend, pf.price_uncertainty,
       atd.decision, atd.reason, atd.rank,
       sfr.passed, sfr.failure_reason, sfr.max_quantity
FROM stock_filter_score sfs
LEFT JOIN stock_financial sf       ON sfs.stock_code = sf.stock_code
LEFT JOIN news_analysis na         ON sfs.stock_code = na.stock_code AND sfs.score_date = na.analysis_date
LEFT JOIN prophet_forecast pf      ON sfs.stock_code = pf.stock_code AND sfs.score_date = pf.forecast_date
LEFT JOIN ai_trade_decision atd    ON sfs.stock_code = atd.stock_code AND sfs.score_date = atd.decision_date
LEFT JOIN safety_filter_result sfr ON sfs.stock_code = sfr.stock_code AND sfs.score_date = sfr.filter_date
WHERE sfs.stock_code = :stockCode AND sfs.score_date = CURRENT_DATE;
```

### 4-4. 종목 시계열 예측 상세 (차트용)

```sql
SELECT stock_code, forecast_date,
       yhat_d1, yhat_lower_d1, yhat_upper_d1,
       yhat_d2, yhat_lower_d2, yhat_upper_d2,
       yhat_d3, yhat_lower_d3, yhat_upper_d3,
       yhat_d4, yhat_lower_d4, yhat_upper_d4,
       yhat_d5, yhat_lower_d5, yhat_upper_d5
FROM prophet_forecast
WHERE stock_code = :stockCode AND forecast_date = CURRENT_DATE;
```

### 4-5. 시장 요약

```sql
SELECT summary_date, kospi_index, kospi_change_rate, kospi_volume,
       total_foreign_net_buy, total_institutional_net_buy, market_sentiment_score
FROM market_daily_summary
WHERE summary_date = CURRENT_DATE;
```

### 4-6. Safety Filter 통과 현황

```sql
SELECT COUNT(*) FILTER (WHERE passed) AS passed_count,
       COUNT(*) FILTER (WHERE NOT passed) AS failed_count
FROM safety_filter_result
WHERE filter_date = CURRENT_DATE;
```

> 결합 뷰 `v_decision_with_filter`(ai_trade_decision + safety_filter_result)는 schema.sql에 정의되어 있어 그대로 활용 가능하다.

---

## 5. 조회 시 주의사항

1. **날짜 비교**: `score_date = CURRENT_DATE` (DATE). `NOW()`(TIMESTAMP)와 직접 비교 금지.
2. **NULL 처리**: `per`(적자/결측), `prophet_forecast` 상세값, `market_daily_summary`의 rising/falling/unchanged는 NULL 가능 → LEFT JOIN 및 DTO에서 null-safe 처리.
3. **시장 전반 감성**: `news_analysis.stock_code IS NULL` 행이 Track 1(시장 전반). 종목별 조회 시 `stock_code = :code`로 필터.
4. **decision 값**: `ai_trade_decision.decision`은 `'BUY'`/`'SELL'` 대문자.
5. **인덱스 활용**: `(score_date, is_selected)`, `(stock_code, score_date)`, `(decision_date, decision, rank)` 인덱스가 schema.sql에 정의됨.

# STATUS — 진행 상황

> 이 문서는 ai-agent 모듈의 **기능별 구현 상태**와 **주요 수정 이력**을 코드 기준으로 정리한다.
> 기능 설계 상세는 [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md), 구조는 [ARCHITECTURE.md](ARCHITECTURE.md) 참고.

기준 시점: 2026-06 (develop-analysis 브랜치)

---

## 1. Stage별 구현 상태

| Stage | 기능 | 상태 | 구현 위치 |
| --- | --- | --- | --- |
| 0 | 휴장일 체크 | ✅ 완료 | `KISClient.is_market_open` |
| 1 | 종목 필터링 (Top 30) | ✅ 완료 | `StockFilter`, `fetch_stock_data_parallel` |
| 2-1-A | DART 재무 수집 + PER 보강 | ✅ 완료 | `DARTAPIClient`, `get_valuations_for_stocks` |
| 2-1-B | KIS 정량 피처 | ✅ 완료 | `QuantitativeAnalyzer` |
| 2-2 | 감성 분석 (2-track) | ✅ 완료 | `SentimentAnalyzer`, `KRFinBERTAnalyzer` |
| 2-3 | 시계열 예측 (Prophet) | ✅ 완료 | `TimeSeriesAnalyzer`, `ProphetForecaster` |
| 3 | 차트 생성 (matplotlib) | ❌ 미구현 | — (코드에 `charts/` 없음) |
| 4 | Gemini AI 결정 | ✅ 완료 | `TradingDecisionGenerator`, `GeminiClient` |
| 5 | Safety Filter | ✅ 완료 | `SafetyFilter` |
| 6 | 거래 실행 | ✅ 완료 | `TradeExecutor` |

상태 정의: ✅ 완료 = 동작 코드 존재 / 🔄 진행중 / ❌ 미구현.

---

## 2. 알려진 갭 / 주의사항

### 2-1. 스케줄러는 전체 파이프라인 실행 ✅

`PipelineScheduler._job_wrapper`(`pipeline/scheduler.py`)는 `orchestrator.run_complete_pipeline_sync()`를 호출한다. 즉 **자동 스케줄(평일 08:50)이 전체 파이프라인(Stage 1~6)**을 수행한다(잡 id `full_pipeline_job`).

수동 트리거 API `POST /api/pipeline/trigger`(main.py → `run_complete_pipeline()`)도 동일한 전체 경로를 실행한다.

### 2-2. Stage 3 차트 미구현 ❌

CLAUDE.md 및 구 문서는 Stage 3에서 matplotlib PNG 4종(`heatmap_today.png` 등)을 `/static/charts/`에 생성한다고 기술하나, 현재 코드에는:
- `charts/` 디렉터리·차트 생성 클래스 없음
- 앱 코드(`_claude/` 제외)에 matplotlib 호출 없음
- FastAPI 정적 마운트(`/static`) 없음
- `run_complete_pipeline`이 Stage 3을 명시적으로 건너뜀

`requirements.txt`에 `matplotlib`는 남아 있으나 실사용 코드는 없다. `_temp/`에 있던 PNG 5종은 과거 1회성 미리보기 산출물이며 코드가 참조하지 않아 정리 대상이다.

### 2-3. market_daily_summary 일부 컬럼 NULL

`rising_stocks`, `falling_stocks`, `unchanged_stocks`는 Stage 1 수집기가 종목별 등락률을 반환하지 않아 `None`으로 저장된다(임의 값 생성 금지 원칙). 채우려면 `kis_client.fetch_stock_data_parallel`이 등락률을 반환하도록 보강이 선행되어야 한다.

### 2-4. PER는 DART가 아닌 KIS 시세로 보강

`stock_financial.per`는 DART 재무제표만으로 산출 불가하여(현재가·발행주식수 부재), Stage 2-1-A에서 KIS `get_current_price`(output.per)로 보강한다. 적자/결측 종목은 `None`.

---

## 3. DB 저장 수정 이력

과거 AI Agent 코드의 DataFrame 컬럼명과 실제 DB 스키마가 달라 저장 실패/NULL이 발생했고, 다음과 같이 정합화되었다. (현재 코드는 모두 반영 완료.)

### 3-1. stock_filter_score 컬럼 매핑

| 내부 DataFrame 컬럼 | 실제 DB 컬럼 | 매핑 위치 |
| --- | --- | --- |
| `trade_date` | `score_date` | `save_filter_scores` |
| `institution_net_buy` | `institutional_net_buy` | `save_filter_scores` |
| `volume_ratio` | `vol_avg_multiple` | `save_filter_scores` |
| `final_score` | `scaler_score` | `save_filter_scores` |

### 3-2. prophet_forecast 컬럼명

- 기준일 컬럼: `trade_date` → **`forecast_date`** (모델/repository 모두 수정).
- 트렌드 컬럼: `prophet_*` → **`price_trend`/`volume_trend`/`price_uncertainty`**.
- `stock_name` 컬럼 추가(NOT NULL 대응).
- DB NUMERIC 정밀도 초과 방지 clamping 적용:
  - `yhat_d1~d5`/`upper`/`lower`: NUMERIC(12,2) → max 9,999,999,999.99
  - `price_trend`/`volume_trend`: NUMERIC(10,6) → ±9,999.0으로 clamp
  - `price_uncertainty`: NUMERIC(10,4) → 0~999,999.0으로 clamp

### 3-3. ai_trade_decision 컬럼명

- `decision_type` → **`decision`** (값 `'BUY'`/`'SELL'`).
- `trade_date` → **`decision_date`**.
- `stock_name`을 `STOCK_NAMES`로 보강.

### 3-4. news_analysis 중복/시장행 처리

- 시장 전반 행(`stock_code=NULL`): PostgreSQL UNIQUE 제약이 NULL 중복을 막지 못하므로 `IS NULL` 매칭으로 기존 행 삭제 후 1건 삽입.
- 종목별 행: `ON CONFLICT (stock_code, analysis_date) DO UPDATE`로 재실행 시 갱신.
- `news_count`는 실제 분석 기사 수 저장(과거 0 하드코딩 → 수정).

### 3-5. safety_filter_result JSON 안전 직렬화

- `filter_checks`(JSONB) 저장 시 numpy 스칼라 → 네이티브 타입, NaN/±Inf → None 정제.
- `json.dumps(..., allow_nan=False)`로 잔여 비유한값 방지.
- bulk insert 실패 시 행 단위 fallback insert.

### 3-6. market_daily_summary 합계 컬럼

- `total_institutional_net_buy`가 과거 컬럼명 오타(`institution_net_buy`)로 항상 NULL이던 문제 수정 → 수집기 컬럼명 `institutional_net_buy` 사용.

---

## 4. 테스트 현황

`tests/`에 pytest 단위 테스트 존재:

| 파일 | 대상 |
| --- | --- |
| `test_filter.py` | Stage 1 스코어링 |
| `test_quantitative.py` | Stage 2-1 정량 |
| `test_sentiment.py` | Stage 2-2 감성 |
| `test_timeseries.py` | Stage 2-3 시계열 |
| `test_decision_generator.py` | Stage 4 의사결정 |
| `conftest.py` | 공통 fixture |

실행:

```bash
pytest tests/ -v
pytest --cov=. --cov-report=html tests/
```

> `_claude/` 디렉터리에 있던 1회성 디버그/검증 스크립트(KIS 인증 진단, DART 수집 테스트, DB 점검 등)는 본 정리 작업에서 제거되었다. 재사용 단위 테스트는 `tests/`에 유지된다.

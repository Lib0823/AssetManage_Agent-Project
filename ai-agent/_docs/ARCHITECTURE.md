# ARCHITECTURE — AI Agent 모듈 구조

> 이 문서는 ai-agent 모듈의 **디렉터리/컴포넌트 구조**, **일일 파이프라인 데이터 흐름**, **외부 연동(KIS/DART/Gemini)**을 코드 기준으로 정리한다.
> 알고리즘 상세 설계는 [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md), 진행 상황은 [STATUS.md](STATUS.md)를 참고.

---

## 1. 모듈 개요

ai-agent는 매 거래일 KOSPI 100 종목을 분석해 매수/매도 의사결정을 생성하고, 자동매매가 켜져 있으면 Spring Boot api-server를 통해 KIS 모의투자 주문을 실행하는 **Python FastAPI 서비스**다.

| 항목 | 내용 |
| --- | --- |
| 실행 형태 | FastAPI 상주 서버 (port 8000) + APScheduler 백그라운드 스케줄러 |
| 트리거 | 평일 08:50 KST 자동 실행 + REST API 수동 트리거 |
| 분석 입력 | KOSPI 100 (하드코딩), 보유 종목은 항상 분석 대상에 포함 |
| 분석 출력 | 11개 피처 → Gemini 매수/매도 TOP3 → Safety Filter → 주문 실행 |
| 데이터 저장 | PostgreSQL (SQLAlchemy ORM) |

---

## 2. 디렉터리 구조 (실제 코드 기준)

```
ai-agent/
├── main.py                     # FastAPI 앱 + lifespan에서 스케줄러/오케스트레이터 초기화
├── requirements.txt
├── run_dev.sh                  # 개발용 실행 스크립트
├── .env.example                # 환경 변수 템플릿
│
├── pipeline/
│   ├── scheduler.py            # APScheduler cron 설정 (PipelineScheduler)
│   └── orchestrator.py         # 전체 파이프라인 오케스트레이션 (PipelineOrchestrator)
│
├── analysis/
│   ├── filter.py               # Stage 1: KOSPI 100 → Top 30 스코어링 (StockFilter)
│   ├── quantitative.py         # Stage 2-1: 7개 정량 피처 (QuantitativeAnalyzer)
│   ├── sentiment.py            # Stage 2-2: 감성 분석 2-track (SentimentAnalyzer)
│   └── timeseries.py           # Stage 2-3: Prophet 예측 (TimeSeriesAnalyzer)
│
├── collectors/
│   ├── kis_client.py           # KIS Open API 비동기 클라이언트 (KISClient)
│   ├── dart_client.py          # DART Open API 클라이언트 (DARTAPIClient)
│   └── news_collector.py       # RSS + 네이버 금융 뉴스 수집 (NewsCollector)
│
├── models/
│   ├── kr_finbert.py           # KR-FinBERT 감성 추론 (KRFinBERTAnalyzer)
│   └── prophet_trainer.py      # Prophet 학습/예측 (ProphetForecaster)
│
├── ai/
│   ├── gemini_client.py        # Gemini API 래퍼 (GeminiClient)
│   └── decision_generator.py   # 11개 피처 → 프롬프트 → JSON 파싱 (TradingDecisionGenerator)
│
├── filters/
│   └── safety_filter.py        # Stage 5: 피처 기반 매매 검증 (SafetyFilter)
│
├── execution/
│   └── trade_executor.py       # Stage 6: api-server 통한 주문 실행 (TradeExecutor)
│
├── database/
│   ├── models.py               # SQLAlchemy 모델 3종 + engine/SessionLocal
│   └── repository.py           # 저장/조회 로직 (DatabaseRepository)
│
├── config/
│   ├── settings.py             # Pydantic 환경 변수 (Settings)
│   └── constants.py            # KOSPI_100, STOCK_NAMES, FILTER_WEIGHTS 등
│
├── tests/                      # pytest 단위 테스트 (filter/quant/sentiment/timeseries/decision)
│
└── _docs/                      # 본 문서 디렉터리
```

> **참고**: `charts/` 디렉터리와 `static/charts/` 차트 출력은 **현재 코드에 존재하지 않는다**. Stage 3(matplotlib 차트 생성)은 미구현이다. 자세한 내용은 [STATUS.md](STATUS.md) 참고.

---

## 3. 컴포넌트별 역할

| 컴포넌트 | 클래스 | 역할 |
| --- | --- | --- |
| `pipeline/orchestrator.py` | `PipelineOrchestrator` | Stage 1~6 전체 흐름 조립, 각 단계 결과를 DB에 저장 |
| `pipeline/scheduler.py` | `PipelineScheduler` | APScheduler cron 등록, 자동 실행 트리거 |
| `analysis/filter.py` | `StockFilter` | StandardScaler 정규화 + 가중합 스코어, Top 30 선정 |
| `analysis/quantitative.py` | `QuantitativeAnalyzer` | KIS 4개 + DART 3개 = 7개 정량 피처 산출 |
| `analysis/sentiment.py` | `SentimentAnalyzer` | 시장 전반(Track 1) + 종목별(Track 2) 감성 점수 |
| `analysis/timeseries.py` | `TimeSeriesAnalyzer` | Prophet 학습 → 3개 추세/불확실성 피처 산출 |
| `collectors/kis_client.py` | `KISClient` | OAuth 토큰 캐시, rate limit, 시세/수급/잔고/지수 조회 |
| `collectors/dart_client.py` | `DARTAPIClient` | corp_code 매핑, 분기 재무제표 수집, fallback 분기 탐색 |
| `collectors/news_collector.py` | `NewsCollector` | RSS 피드 파싱 + 네이버 금융 뉴스 JSON API |
| `models/kr_finbert.py` | `KRFinBERTAnalyzer` | `snunlp/KR-FinBert-SC` 추론, 점수 = P(긍정) − P(부정) |
| `models/prophet_trainer.py` | `ProphetForecaster` | Prophet fit/forecast, 기울기·불확실성 계산 |
| `ai/gemini_client.py` | `GeminiClient` | `models/gemini-2.5-flash` 호출, JSON 파싱, 키 없으면 mock |
| `ai/decision_generator.py` | `TradingDecisionGenerator` | 3개 분석 DataFrame 병합 → 프롬프트 → 의사결정 |
| `filters/safety_filter.py` | `SafetyFilter` | 매수/매도 규칙 검증, 투자한도 기반 max_quantity 산출 |
| `execution/trade_executor.py` | `TradeExecutor` | `is_active` 확인 후 api-server `/api/trading/execute` 호출 |
| `database/repository.py` | `DatabaseRepository` | 각 Stage 결과 저장(8개 save 메서드) 및 조회 |

---

## 4. 시스템 전체 구성

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│    Vue3 SPA     │────▶│  Spring Boot    │────▶│   PostgreSQL    │
│   (web-app)     │     │  (api-server)   │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                       ▲                        ▲
         │                       │ POST /api/trading/      │ 분석 결과 저장
         ▼                       │ execute                 │
┌─────────────────┐             │                        │
│  AI Agent       │─────────────┴────────────────────────┘
│  (FastAPI:8000) │
└─────────────────┘
   │        │        │
   ▼        ▼        ▼
┌──────┐ ┌──────┐ ┌────────┐
│ KIS  │ │ DART │ │ Gemini │
└──────┘ └──────┘ └────────┘
```

- **AI Agent → PostgreSQL**: 분석 결과(필터 점수, 감성, 예측, AI 결정, 안전 필터)를 직접 기록.
- **AI Agent → Spring Boot api-server**: 자동매매가 켜져 있으면 매매 요청 전송. 주문 체결 이력(`trade_history`)은 api-server가 기록.
- **AI Agent → 외부 API**: KIS(시세/수급/주문 데이터), DART(분기 재무), Gemini(의사결정).

---

## 5. 일일 파이프라인 데이터 흐름

`PipelineOrchestrator.run_complete_pipeline()`가 호출하는 Stage 1~6의 흐름이다. 각 Stage의 알고리즘 상세는 [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md) 참고.

```
APScheduler / 수동 트리거
  │
  ├─ Stage 0  휴장일 체크 (kis_client.is_market_open)
  │            주말 + KIS 수급 API 실시간 테스트 → 휴장이면 즉시 종료
  │
  ├─ Stage 1  종목 필터링 (StockFilter.process)
  │            KOSPI 100 수급/거래량/변동성 수집 → StandardScaler 가중합
  │            → Top 30 + 보유 종목  ⟹  stock_filter_score, market_daily_summary
  │
  ├─ Stage 2-1 정량 분석
  │   ├─ 2-1-A DART 재무(분기) 수집 + KIS 시세로 PER 보강 ⟹ stock_financial
  │   └─ 2-1-B KIS 정량 피처(morning_return, close_position) ⟹ stock_filter_score(UPDATE)
  │
  ├─ Stage 2-2 감성 분석 (SentimentAnalyzer.analyze_stocks)
  │            Track 1 시장 전반 + Track 2 종목별  ⟹  news_analysis, market_daily_summary(UPDATE)
  │
  ├─ Stage 2-3 시계열 분석 (TimeSeriesAnalyzer.analyze_stocks)
  │            Prophet 120일 학습 → D+1~D+5 추세  ⟹  prophet_forecast
  │
  ├─ Stage 4  Gemini AI 결정 (TradingDecisionGenerator.generate_decisions)
  │            11개 피처 → 프롬프트 → 매수/매도 TOP3  ⟹  ai_trade_decision
  │
  ├─ Stage 5  Safety Filter (SafetyFilter.filter_decisions)
  │            피처 규칙 검증 + 투자한도 max_quantity  ⟹  safety_filter_result
  │
  └─ Stage 6  거래 실행 (TradeExecutor.execute_trades)
               user_trade_config.is_active 확인 → api-server POST  ⟹  trade_history (api-server 기록)
```

### 5-1. Stage별 입출력 요약

| Stage | 처리 | 저장 테이블 | 비고 |
| --- | --- | --- | --- |
| 0 | 휴장일 체크 | — | 휴장이면 `{success:false, is_holiday:true}` 반환 |
| 1 | Top 30 필터링 | `stock_filter_score`, `market_daily_summary` | Stage 1 데이터는 Stage 2에서 재사용 |
| 2-1-A | DART 재무 수집 | `stock_financial` | 분기 데이터, fallback 분기 탐색 |
| 2-1-B | KIS 정량 피처 | `stock_filter_score` (UPDATE) | `morning_return`, `close_position` |
| 2-2 | 감성 분석 | `news_analysis` (+ market UPDATE) | 시장 전반은 `stock_code=NULL` |
| 2-3 | Prophet 예측 | `prophet_forecast` | UPSERT(삭제 후 삽입) |
| 4 | Gemini 결정 | `ai_trade_decision` | 매수 3 + 매도 3 |
| 5 | Safety Filter | `safety_filter_result` | `filter_checks` JSONB |
| 6 | 주문 실행 | `trade_history` (api-server) | `is_active=false`면 skip |

> **스케줄러**: `PipelineScheduler._job_wrapper`가 `run_complete_pipeline_sync()`를 호출해 **전체 파이프라인(Stage 1~6)**을 실행한다. 수동 트리거 API(`POST /api/pipeline/trigger`)는 `run_complete_pipeline()`으로 동일한 전체 경로를 실행한다.

---

## 6. 외부 연동

### 6-1. KIS Open API (`collectors/kis_client.py`)

| 기능 | 메서드 | TR_ID (모의) |
| --- | --- | --- |
| OAuth 토큰 | `get_access_token()` | — (24시간 캐시, asyncio.Lock) |
| 휴장일 체크 | `is_market_open(trade_date)` | 수급 API 실시간 테스트 |
| 수급 데이터 | `get_supply_demand(code)` | FHKST01010900 |
| 일봉 (단기) | `get_daily_ohlcv(code, days=30)` | FHKST01010400 |
| 일봉 (기간) | `get_daily_ohlcv_period(code, days=120)` | FHKST03010100 |
| 체결 거래량 | `get_daily_trade_volume(code, days=120)` | FHKST03010800 |
| 분봉 | `get_minute_data(code, date)` | FHKST03010200 (09:00~10:00) |
| 현재가/PER | `get_current_price(code)` | FHKST01010100 |
| KOSPI 지수 | `get_kospi_index(trade_date)` | FHKUP03500100 |
| 잔고(보유종목) | `get_holdings()` | VTTC8434R |

- **Rate limit**: `asyncio.Semaphore(5)` + 요청 간 0.2초 지연 → 초당 5건.
- **TR_ID 변환**: `convert_tr_id()`가 `VTTC↔TTTC`를 모드(VIRTUAL/REAL)에 따라 자동 변환.
- **OAuth 캐시**: 24시간 TTL, 동시 요청은 `asyncio.Lock`으로 단일화.

### 6-2. DART Open API (`collectors/dart_client.py`)

- `corp_code.xml` 다운로드로 `stock_code(6자리) → corp_code(8자리)` 매핑.
- 분기별 보고서 코드: Q1=11013, Q2=11012(반기), Q3=11014, Q4=11011(사업보고서).
- **CFS(연결) → OFS(개별)** fallback, 최신 분기가 비어 있으면 최대 5분기까지 거슬러 탐색(`collect_financials_with_fallback`).
- ROE·영업이익률을 추출하며, **PER은 DART에서 산출 불가(현재가·발행주식수 부재)** → Stage 2-1-A에서 KIS 시세(`get_valuations_for_stocks`)로 보강. 적자/결측 종목은 `None` 유지.

### 6-3. Gemini API (`ai/gemini_client.py`)

- 모델: `models/gemini-2.5-flash` (무료 티어, 일 1회 호출 가정).
- 입력: 30개 종목 × 11개 피처로 구성한 프롬프트.
- 출력: `{"buy_top3": [{stock_code, reason}×3], "sell_top3": [...]}` JSON.
- `GEMINI_API_KEY` 미설정 시 `_get_mock_decision()`이 `[MOCK]` 표시 결과를 반환(파이프라인 흐름 검증용).

### 6-4. 뉴스 소스 (`collectors/news_collector.py`)

| Track | 소스 | 비고 |
| --- | --- | --- |
| 1 (시장 전반) | 한경/매경/연합뉴스 RSS | `feedparser` 파싱 |
| 2 (종목별) | 네이버 금융 뉴스 JSON API (`api.stock.naver.com`) | 종목당 최신 5건 |

---

## 7. 데이터베이스 매핑

ai-agent가 직접 기록하는 테이블과 SQLAlchemy 모델 매핑은 [API_REFERENCE.md](API_REFERENCE.md)에 상세히 정리되어 있다. 핵심 주의점:

- **DataFrame 컬럼명 ≠ DB 컬럼명**: 코드 내부 DataFrame은 `final_score`/`volume_ratio`/`institution_net_buy`를 쓰지만, 저장 시 repository가 DB 컬럼 `scaler_score`/`vol_avg_multiple`/`institutional_net_buy`로 매핑한다.
- `prophet_forecast`의 트렌드 컬럼은 DB에서 `price_trend`/`volume_trend`/`price_uncertainty`이며, 기준일 컬럼은 `forecast_date`다.
- `ai_trade_decision`의 결정 컬럼은 `decision`(값 `'BUY'`/`'SELL'`), 기준일은 `decision_date`다.

자세한 스키마와 컬럼 주의사항은 [API_REFERENCE.md](API_REFERENCE.md) 참고.

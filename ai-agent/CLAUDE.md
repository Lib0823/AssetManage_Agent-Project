# CLAUDE.md — AI Agent 모듈

Claude Code가 ai-agent 모듈에서 작업할 때 참고하는 가이드. **상세 설계·스키마·운영 문서는 [`_docs/`](_docs/README.md)에 있으며, 이 파일은 핵심 오리엔테이션만 담는다.**

## 모듈 개요

Python FastAPI 서비스로, 매 거래일 KOSPI 100 종목을 분석해 매수/매도 의사결정을 생성하고 KIS 모의투자 주문까지 수행한다.

- 실행: FastAPI 상주 서버(port 8000) + APScheduler(평일 08:50 KST)
- 분석: 11개 피처(정량 7 + 감성 1 + 시계열 3) → Gemini → Safety Filter → 실행
- 저장: PostgreSQL (SQLAlchemy ORM)

## 기술 스택

| Layer | Technology |
| --- | --- |
| Framework | FastAPI (async/await), APScheduler |
| ML/Data | pandas, NumPy, scikit-learn(StandardScaler) |
| Time-Series | Prophet (CmdStanPy backend) |
| NLP | transformers — `snunlp/KR-FinBert-SC` |
| AI Decision | Gemini API — `models/gemini-2.5-flash` |
| HTTP | aiohttp (KIS/DART/뉴스 비동기 호출) |
| DB | PostgreSQL, SQLAlchemy 2.0 |

> Prophet 때문에 **반드시 venv에서 실행**한다(시스템 python3는 Prophet이 깨져 `prophet_forecast`가 NULL이 됨).

## 디렉터리 (요약)

```
main.py            FastAPI 앱 + lifespan(스케줄러/오케스트레이터 초기화)
pipeline/          orchestrator.py(전체 흐름), scheduler.py(cron)
analysis/          filter, quantitative, sentiment, timeseries
collectors/        kis_client, dart_client, news_collector
models/            kr_finbert, prophet_trainer
ai/                gemini_client, decision_generator
filters/           safety_filter
execution/         trade_executor
database/          models.py(3 모델), repository.py(저장/조회)
config/            settings.py(Pydantic), constants.py(KOSPI_100 등)
tests/             pytest 단위 테스트
_docs/             상세 문서 (진입점: _docs/README.md)
```

전체 구조와 컴포넌트별 역할: [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md).

## 파이프라인 (Stage 0~6)

```
0 휴장일 체크 → 1 Top30 필터 → 2-1 정량(KIS+DART) → 2-2 감성 → 2-3 시계열(Prophet)
  → 4 Gemini 결정 → 5 Safety Filter → 6 거래 실행
```

각 Stage 알고리즘·설계 근거: [`_docs/PIPELINE_DESIGN.md`](_docs/PIPELINE_DESIGN.md).

## 작업 전 반드시 알아야 할 사실

- **자동 스케줄이 전체 파이프라인(Stage 1~6)을 실행**한다(`scheduler._job_wrapper` → `run_complete_pipeline_sync`). 수동 트리거 `POST /api/pipeline/trigger`(`run_complete_pipeline`)도 동일. ([`_docs/STATUS.md`](_docs/STATUS.md))
- **Stage 3(matplotlib 차트)는 미구현**. `charts/`·`static/charts/` 없음. ([`_docs/STATUS.md`](_docs/STATUS.md) §2-2)
- **DataFrame 컬럼명 ≠ DB 컬럼명**: 내부 `final_score`/`volume_ratio`/`institution_net_buy`/`prophet_*`/`decision_type`/`trade_date`는 저장 시 DB 컬럼(`scaler_score`/`vol_avg_multiple`/`institutional_net_buy`/`price_trend` 등/`decision`/`score_date`·`forecast_date`·`decision_date`)으로 매핑된다. 매핑표: [`_docs/API_REFERENCE.md`](_docs/API_REFERENCE.md) §3.
- **DB 단일 출처**: 루트 [`database/schema.sql`](../database/schema.sql). 스키마를 바꾸지 말고 코드/문서를 스키마에 맞춘다.
- **PER**: DART만으로 산출 불가 → Stage 2-1-A에서 KIS 시세로 보강.

## 개발 명령

```bash
cd ai-agent
source venv/bin/activate
uvicorn main:app --reload --host 0.0.0.0 --port 8000   # 서버
pytest tests/ -v                                       # 테스트
curl -X POST http://localhost:8000/api/pipeline/trigger -H "Content-Type: application/json" -d '{}'
```

설치·환경변수·엔드포인트·트러블슈팅: [`_docs/USAGE.md`](_docs/USAGE.md).

## 코딩 규칙

- Python 4-space indent, Google style docstring.
- 실제 동작 코드만 작성(mock/stub/TODO로 핵심 기능 남기지 않기).
- 외부 API 키·민감정보는 `.env`로 관리, 커밋 금지.
- 문서가 가리키는 경로/컬럼이 코드와 다르면 코드 기준으로 문서를 고친다.

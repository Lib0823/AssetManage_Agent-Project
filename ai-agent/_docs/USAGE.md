# USAGE — 설치 · 실행 · 운영

> 이 문서는 ai-agent를 **설치하고 실행·운영**하는 방법을 정리한다.
> 구조는 [ARCHITECTURE.md](ARCHITECTURE.md), 파이프라인 설계는 [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md), 진행 상황은 [STATUS.md](STATUS.md) 참고.

---

## 1. 실행 형태

ai-agent는 종료되지 않고 상주하는 **FastAPI 서버(port 8000)**다. 기동 시 `main.py`의 lifespan이 `PipelineOrchestrator`와 `PipelineScheduler`를 초기화한다.

```
FastAPI 기동 (port 8000)
  → PipelineOrchestrator 초기화 (KIS OAuth 토큰 캐시 준비)
  → PipelineScheduler 시작 (APScheduler BackgroundScheduler)
  → 평일 08:50 KST 자동 트리거 등록
  → 서버 상주
```

> 스케줄러(`_job_wrapper`)는 Kafka `pipeline.run.requested` 이벤트를 **발행만** 하고, **전체 파이프라인(Stage 1~6) 실행은 `messaging/pipeline_run_consumer.py`의 컨슈머**가 담당한다. 수동 트리거 API(`POST /api/pipeline/trigger`)도 같은 토픽에 발행하므로 두 경로가 같은 컨슈머로 합류한다. 자세한 내용은 [ARCHITECTURE.md](ARCHITECTURE.md) 참고.

---

## 2. 설치

### 2-1. 가상환경 (필수)

> Prophet은 시스템 python3에서 깨질 수 있으므로 **반드시 venv에서 실행**한다.

```bash
cd ai-agent
python3 -m venv venv
source venv/bin/activate        # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### 2-2. 환경 변수

```bash
cp .env.example .env
```

> ⚠️ **`KIS_APP_KEY`·`KIS_APP_SECRET`는 startup 필수**다. `main.py`의 lifespan이 기동 시 `PipelineOrchestrator`→`KISClient()`를 생성하는데, 두 값이 없으면 `KISClient`가 `ValueError`를 던져 **서버가 기동되지 않는다**(Docker에서는 컨테이너가 재시작을 반복). 반면 `GEMINI_API_KEY`·`DART_API_KEY`는 비어도 기동되며 해당 분석만 mock/비활성된다.

`.env` 주요 항목:

```bash
# KIS API (실전투자, 시세/수급 데이터 수집 전용)
KIS_APP_KEY=...
KIS_APP_SECRET=...
KIS_BASE_URL=https://openapi.koreainvestment.com:9443

# Database (PostgreSQL)
DB_HOST=localhost                # Docker 사용 시: postgres
DB_PORT=5432
DB_NAME=financemanage
DB_USER=postgres
DB_PASSWORD=...

# Gemini AI (무료 티어). 미설정 시 mock 결정으로 동작
GEMINI_API_KEY=...

# DART API
DART_API_KEY=...

# Scheduler
PIPELINE_CRON=50 8 * * 1-5       # 평일 08:50
PIPELINE_TIMEZONE=Asia/Seoul
PIPELINE_ENABLED=true            # false면 스케줄러 비활성(수동 실행만)

# Logging
LOG_LEVEL=INFO
LOG_FILE=logs/pipeline.log
```

### 2-3. 로그 디렉터리 / DB 준비

```bash
mkdir -p logs
# 테이블 생성: 루트 database/schema.sql 실행
psql -h localhost -U postgres -d financemanage -f ../database/schema.sql
```

---

## 3. 서버 실행

```bash
# 개발 (자동 리로드)
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# 직접 실행
python main.py

# 프로덕션
uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4

# Docker Compose (전체 시스템)
cd ..
docker-compose up -d
docker-compose logs -f ai-agent
```

---

## 4. API 엔드포인트

`main.py`에 정의된 엔드포인트:

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | `/` | 서비스 정보 |
| GET | `/health` | 헬스 체크 |
| GET | `/api/pipeline/status` | 스케줄러 상태 + Kafka 연결/컨슈머 상태 + 진행 중·직전 실행 + 최근 실행일 |
| POST | `/api/pipeline/trigger` | **전체 파이프라인(Stage 1~6) 실행 요청 큐잉** (202 Accepted) |
| GET | `/api/pipeline/results/{trade_date}` | 특정일 필터 결과 전체 |
| GET | `/api/pipeline/selected/{trade_date}` | 특정일 선정 종목 코드 |

Swagger UI: `http://localhost:8000/docs`

### 4-1. 상태 조회

```bash
curl http://localhost:8000/api/pipeline/status
# {
#   "scheduler_running": true,
#   "next_run_time": "2026-06-22T08:50:00+09:00",
#   "latest_execution_date": "2026-06-21",
#   "kafka_connected": true,                    // 프로듀서 연결 상태
#   "consumers": {                              // 컨슈머별 생사/처리 카운트
#     "pipeline-run": {"alive": true, "connected": true, "processed": 3, "failed": 0, "error": null},
#     "trade-result": {"alive": true, "connected": true, "processed": 6, "failed": 0, "error": null}
#   },
#   "running_trade_date": null,                 // 지금 실행 중인 거래일 (없으면 null)
#   "last_run": {"trade_date": "2026-06-21", "success": true}   // 마지막으로 끝난 실행 요약
# }
```

> `running_trade_date`가 `null`이 아니면 파이프라인이 진행 중이다. 컨슈머는 그룹당 1개 +
> 순차 처리라 동시 실행되지 않으므로, 이 필드가 사실상 "실행 중" 플래그 역할을 한다.

### 4-2. 수동 트리거 (전체 파이프라인 실행 요청)

> ⚠️ 이 API는 실행을 **즉시 수행하지 않는다.** Kafka `pipeline.run.requested` 토픽에
> 요청을 발행하고 **202 Accepted**로 즉시 반환하며, 실제 실행은 `pipeline_run_consumer`가
> 이어받는다. 따라서 응답 본문에 분석 결과가 들어 있지 않다 — 진행 상황은
> `/api/pipeline/status`의 `running_trade_date`/`last_run`으로 확인한다.
> 실행이 끝나면 `is_active=true`인 유저에 한해 실제 실전투자 주문까지 전송된다.
> Kafka 프로듀서가 연결되지 않으면 **503**을 반환한다(큐잉 실패를 성공으로 위장하지 않는다).

```bash
# 오늘 날짜, 보유 종목 자동 조회
curl -X POST http://localhost:8000/api/pipeline/trigger \
  -H "Content-Type: application/json" -d '{}'

# 특정 날짜
curl -X POST http://localhost:8000/api/pipeline/trigger \
  -H "Content-Type: application/json" -d '{"trade_date": "2026-06-19"}'

# 보유 종목 지정
curl -X POST http://localhost:8000/api/pipeline/trigger \
  -H "Content-Type: application/json" \
  -d '{"holdings": ["005930", "000660"]}'
```

요청 파라미터: `trade_date`(선택, YYYY-MM-DD), `holdings`(선택, 종목코드 리스트).

응답은 Stage별 결과를 `stages` 아래에 포함한다(`stage1_filtering`, `stage2_analysis`, `stage4_gemini`, `stage5_safety_filter`, `stage6_execution`).

### 4-3. 결과 조회

```bash
curl http://localhost:8000/api/pipeline/results/2026-06-19
curl http://localhost:8000/api/pipeline/selected/2026-06-19
```

---

## 5. Python 스크립트로 직접 실행

```python
import asyncio
from datetime import date
from pipeline import PipelineOrchestrator

async def main():
    orch = PipelineOrchestrator()
    # 전체 파이프라인
    result = await orch.run_complete_pipeline(trade_date=date.today())
    # 또는 Stage 1만
    # result = await orch.run_stage1_filtering(trade_date=date.today())
    print(result)

asyncio.run(main())
```

---

## 6. 모니터링

```bash
tail -f logs/pipeline.log
docker-compose logs -f ai-agent
grep ERROR logs/pipeline.log
```

정상 로그 예시:

```
INFO - [Stage 1] Selected 30 stocks
INFO - [Stage 2-1-A] Saved 30 DART financial records ...
INFO - [Stage 2-2] Sentiment analysis complete: 30 stocks
INFO - [Stage 2-3] Time-series analysis complete: 30 stocks
INFO - [Stage 4] Gemini decisions: 3 buys, 3 sells
INFO - [Stage 5] Safety filter passed: N buys, M sells
INFO - [Stage 6] Execution status: skipped|executed
INFO - === Complete Pipeline Finished Successfully ===
```

---

## 7. 트러블슈팅

| 증상 | 점검 |
| --- | --- |
| `KIS API 401 / OAuth failed` | `KIS_APP_KEY`/`SECRET`가 실전 도메인(`KIS_BASE_URL`) 자격증명인지 확인 |
| `Database connection failed` | `pg_isready`, `.env` 접속 정보, schema.sql 적용 여부 |
| `Scheduler not triggering` | `PIPELINE_ENABLED=true`, cron `50 8 * * 1-5`, `PIPELINE_TIMEZONE=Asia/Seoul` |
| `prophet_forecast` NULL | venv에서 실행 중인지 확인(시스템 python3는 Prophet 깨짐) |
| Gemini 결과가 `[MOCK]` | `GEMINI_API_KEY` 미설정 → 키 설정 후 재실행 |
| 휴장일에 결과 없음 | 정상. Stage 0이 휴장 감지 시 즉시 종료 |

---

## 8. 테스트

```bash
pytest tests/ -v
pytest --cov=. --cov-report=html tests/
```

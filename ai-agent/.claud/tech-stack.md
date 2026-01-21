# AI Agent - Tech Stack

> 상태: 개발 예정 (2~3단계)

## 핵심 기술

| 항목 | 기술 | 버전 |
|------|------|------|
| Language | Python | 3.11 |
| Framework | FastAPI | 0.100+ |
| Package Manager | Poetry / pip | - |

## 주요 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| fastapi | 웹 프레임워크 |
| uvicorn | ASGI 서버 |
| sqlalchemy | ORM |
| asyncpg | PostgreSQL 비동기 드라이버 |
| pydantic | 데이터 검증 |
| httpx | HTTP 클라이언트 |
| celery | 백그라운드 작업 (선택) |

## ML/AI 라이브러리 (예정)

| 라이브러리 | 용도 |
|-----------|------|
| pandas | 데이터 처리 |
| numpy | 수치 연산 |
| scikit-learn | ML 기본 |
| tensorflow / pytorch | 딥러닝 (선택) |
| ta-lib | 기술적 분석 지표 |

## 데이터베이스

| DB | 용도 |
|----|------|
| PostgreSQL | 메인 DB (api-server와 공유) |
| PGMQ | 메시지 큐 (PostgreSQL 기반) |
| Redis | 캐시 (선택) |

## 환경 변수

```bash
# Database
DATABASE_URL=postgresql+asyncpg://admin:password@localhost:5432/assetdb

# API Server
API_SERVER_URL=http://localhost:8080

# PGMQ
PGMQ_DATABASE_URL=postgresql://admin:password@localhost:5432/assetdb

# AI 설정
MODEL_PATH=./models
CONFIDENCE_THRESHOLD=0.7
```

## 코딩 스타일

| 항목 | 도구/규칙 |
|------|-----------|
| 포맷터 | Black |
| 린터 | Ruff / Flake8 |
| 타입 힌트 | mypy |
| 독스트링 | Google style |

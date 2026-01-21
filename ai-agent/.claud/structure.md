# AI Agent - Project Structure

> 상태: 개발 예정 (2~3단계)

## 디렉토리 구조 (계획)

```
ai-agent/
├── app/
│   ├── main.py               # FastAPI 앱 진입점
│   ├── config.py             # 설정 관리
│   │
│   ├── api/                  # API 라우터
│   │   ├── __init__.py
│   │   ├── analysis.py       # 종목 분석 API
│   │   ├── recommendation.py # 추천 API
│   │   └── auto_trading.py   # 자동매매 API
│   │
│   ├── services/             # 비즈니스 로직
│   │   ├── __init__.py
│   │   ├── analyzer.py       # 분석 서비스
│   │   ├── recommender.py    # 추천 서비스
│   │   └── trader.py         # 자동매매 서비스
│   │
│   ├── models/               # ML 모델
│   │   ├── __init__.py
│   │   ├── signal_model.py   # 매매 신호 모델
│   │   └── risk_model.py     # 리스크 분석 모델
│   │
│   ├── data/                 # 데이터 처리
│   │   ├── __init__.py
│   │   ├── fetcher.py        # 데이터 수집
│   │   ├── processor.py      # 전처리
│   │   └── indicators.py     # 기술적 지표
│   │
│   ├── db/                   # 데이터베이스
│   │   ├── __init__.py
│   │   ├── database.py       # DB 연결
│   │   ├── models.py         # SQLAlchemy 모델
│   │   └── repositories.py   # 데이터 접근
│   │
│   ├── queue/                # 메시지 큐
│   │   ├── __init__.py
│   │   ├── producer.py       # 메시지 발행
│   │   └── consumer.py       # 메시지 소비
│   │
│   └── schemas/              # Pydantic 스키마
│       ├── __init__.py
│       ├── analysis.py
│       └── signal.py
│
├── tests/                    # 테스트
│   ├── unit/
│   └── integration/
│
├── scripts/                  # 유틸리티 스크립트
│   ├── train_model.py
│   └── backtest.py
│
├── models/                   # 학습된 모델 저장
├── pyproject.toml           # Poetry 의존성
├── Dockerfile
└── README.md
```

## 모듈별 역할

| 모듈 | 책임 |
|------|------|
| api | FastAPI 라우터, 엔드포인트 정의 |
| services | 핵심 비즈니스 로직 |
| models | ML/AI 모델 정의 및 추론 |
| data | 데이터 수집, 전처리, 지표 계산 |
| db | 데이터베이스 연동 |
| queue | PGMQ 메시지 송수신 |
| schemas | 요청/응답 데이터 검증 |

## API 엔드포인트 (계획)

| Method | Path | 설명 |
|--------|------|------|
| POST | /api/v1/analyze | 종목 분석 |
| GET | /api/v1/recommendations | 추천 종목 |
| POST | /api/v1/signals | 매매 신호 생성 |
| GET | /api/v1/health | 헬스 체크 |

## 통신 흐름

```
[Scheduler] → [AI Agent] → 분석 → [PGMQ] → [API Server] → 주문
                 ↓
            [PostgreSQL] (데이터 조회)
```

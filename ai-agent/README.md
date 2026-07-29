# AI Agent — Stock Analysis Pipeline

매 거래일 KOSPI 100 종목을 분석해 11개 피처를 산출하고, Gemini AI로 매수/매도 TOP3를 결정한 뒤 Safety Filter를 거쳐 KIS 모의투자 주문을 실행하는 Python FastAPI 파이프라인 모듈.

## 빠른 시작

```bash
cd ai-agent
python3 -m venv venv && source venv/bin/activate   # Prophet 때문에 venv 필수
pip install -r requirements.txt
cp .env.example .env                                # KIS/DB/Gemini/DART 키 입력
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

서버는 `http://localhost:8000`에서 상주하며, 스케줄러가 평일 08:50 KST에 전체 파이프라인(Stage 1~6)을 자동 실행한다. 수동 실행은 `POST /api/pipeline/trigger`.

## 문서

모든 상세 문서는 [`_docs/`](_docs/README.md)에 있다.

| 문서 | 내용 |
| --- | --- |
| [`_docs/README.md`](_docs/README.md) | 문서 인덱스 (진입점) |
| [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) | 구조 · 데이터 흐름 · 외부 연동 |
| [`_docs/PIPELINE_DESIGN.md`](_docs/PIPELINE_DESIGN.md) | Stage 0~6 기능 설계서 |
| [`_docs/STATUS.md`](_docs/STATUS.md) | 구현 상태 · 갭 · 수정 이력 |
| [`_docs/API_REFERENCE.md`](_docs/API_REFERENCE.md) | DB 스키마 · 조회 쿼리 |
| [`_docs/USAGE.md`](_docs/USAGE.md) | 설치 · 실행 · 운영 |

상세 아키텍처 가이드(Claude Code용)는 [`CLAUDE.md`](CLAUDE.md) 참고.

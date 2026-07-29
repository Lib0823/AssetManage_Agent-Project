# AI Agent 문서

ai-agent는 매 거래일 KOSPI 100 종목을 분석해 11개 피처를 산출하고, Gemini AI로 매수/매도 TOP3를 결정한 뒤 Safety Filter를 거쳐 KIS 모의투자 주문을 실행하는 **Python FastAPI 파이프라인 모듈**이다.

이 디렉터리(`_docs/`)만 읽으면 모듈의 (1) 구조, (2) 진행 상황, (3) 기능 설계를 파악할 수 있다.

---

## 문서 지도

| 문서 | 내용 | 먼저 읽으면 좋은 독자 |
| --- | --- | --- |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | 디렉터리/컴포넌트 구조, 일일 파이프라인 데이터 흐름, 외부 연동(KIS/DART/Gemini) | 전체 구조를 처음 파악하는 사람 |
| **[PIPELINE_DESIGN.md](PIPELINE_DESIGN.md)** | Stage 0~6 기능 설계서 — Top30 필터, 정량/감성/시계열 분석, AI 결정, Safety Filter, 실행. 알고리즘·설계 근거 | 분석 로직을 이해/수정하려는 AI·개발자 |
| **[STATUS.md](STATUS.md)** | Stage별 구현 상태표, 알려진 갭(스케줄러 범위·차트 미구현 등), DB 저장 수정 이력, 테스트 현황 | 무엇이 되고 안 되는지 알아야 하는 사람 |
| **[API_REFERENCE.md](API_REFERENCE.md)** | AI Agent가 기록하는 DB 테이블 실제 스키마 + Spring Boot 조회 쿼리·컬럼명 주의 | api-server(Backend) 개발자 |
| **[USAGE.md](USAGE.md)** | 설치·환경변수·서버 실행·API 엔드포인트·모니터링·트러블슈팅 | 직접 실행/운영하는 사람 |

부속 자료: [`assets/`](assets) — 분석 플로우 다이어그램 PNG.
폐기 문서: [`archive/`](archive).

---

## 빠른 시작

- **실행하고 싶다** → [USAGE.md](USAGE.md) §2 설치 → §3 실행 → §4 API
- **전체 그림을 보고 싶다** → [ARCHITECTURE.md](ARCHITECTURE.md) §5 일일 파이프라인 데이터 흐름
- **분석 알고리즘이 궁금하다** → [PIPELINE_DESIGN.md](PIPELINE_DESIGN.md)
- **api-server에서 데이터를 조회한다** → [API_REFERENCE.md](API_REFERENCE.md) §2 스키마, §4 쿼리
- **무엇이 구현됐는지 확인한다** → [STATUS.md](STATUS.md) §1 Stage별 상태

---

## 핵심 사실 (코드 기준 요약)

- **트리거**: 자동 스케줄(평일 08:50)이 **전체 파이프라인(Stage 1~6)**을 실행. 수동 트리거 `POST /api/pipeline/trigger`도 동일. ([STATUS.md](STATUS.md))
- **11개 피처**: 정량 7 + 감성 1 + 시계열 3 → Gemini(`models/gemini-2.5-flash`) 입력.
- **Stage 3(차트 생성)은 미구현**. `static/charts/` 출력 없음. ([STATUS.md](STATUS.md) §2-2)
- **DB 단일 출처**: 루트 [`database/schema.sql`](../../database/schema.sql). 내부 DataFrame 컬럼명과 DB 컬럼명이 다르므로 조회 시 [API_REFERENCE.md](API_REFERENCE.md) §3 표 참고.
- **실행 환경**: Prophet 때문에 반드시 **venv**에서 실행.

# 프로젝트 문서 지도 (Documentation Map)

`FinanceManage_Agent` 모노레포의 **최상위 문서 진입점**입니다. AI 에이전트와 사람 모두 이 문서에서 시작해 전체 시스템 구조 → 개발 현황 → 각 모듈 상세 문서로 길을 찾을 수 있도록 구성했습니다.

> 매일 KOSPI 100 종목을 ML 스코어링으로 30종목으로 추리고, 3축 분석(정량·감성·시계열)으로 11개 피처를 산출해 Gemini AI가 매수/매도를 판단한 뒤, 룰 기반 안전망을 거쳐 KIS 모의투자 API로 주문을 실행하는 AI 주식 자동매매 시스템입니다.

---

## 1. 통일 문서 구조 (Unified Layout)

루트와 세 모듈의 `_docs/`는 **동일한 코어 4파일**을 가집니다. 모듈마다 필요한 고유 문서는 추가됩니다.

| 코어 파일 | 역할 |
|----------|------|
| `README.md` | 문서 인덱스 / 지도 (진입점) |
| `ARCHITECTURE.md` | 구조 · 상세 설계 · 데이터 흐름 |
| `STATUS.md` | 개발 현황 |
| `USAGE.md` | 사용 방법 (설치 · 실행 · 운영) |

---

## 2. 어디서부터 읽어야 하나 (Reading Path)

| 당신이 ... 라면 | 시작 문서 |
|----------------|----------|
| **프로젝트를 처음 보는 사람** | [`../README.md`](../README.md) (프로젝트 소개 + 분석 프로세스) |
| **실행/설치하려는 사람** | [`USAGE.md`](USAGE.md) |
| **전체 시스템 구조를 파악하려는 사람** | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| **개발 현황을 보려는 사람** | [`STATUS.md`](STATUS.md) |
| **AI 에이전트 / Claude Code** | [`../CLAUDE.md`](../CLAUDE.md) → 작업 대상 모듈 `_docs/README.md` |
| **DB 스키마를 보려는 사람** | [`../database/README.md`](../database/README.md) → [`../database/schema.sql`](../database/schema.sql) |

---

## 3. 모듈별 문서 진입점

각 모듈 내부를 작업할 때는 먼저 해당 `_docs/README.md`를 읽으세요.

| 모듈 | 역할 | 진입점 | 코어4 외 고유 문서 |
|------|------|--------|-------------------|
| `web-app/` | Vue 3 프론트엔드 (PWA) | [`web-app/_docs/README.md`](../web-app/_docs/README.md) | `SCREENS.md` |
| `api-server/` | Spring Boot 백엔드 / 매매 실행 | [`api-server/_docs/README.md`](../api-server/_docs/README.md) | `API_DESIGN.md`, `AUTHENTICATION_FLOW.md`, `KIS_API_GUIDE.md` |
| `ai-agent/` | FastAPI ML 파이프라인 / AI 판단 | [`ai-agent/_docs/README.md`](../ai-agent/_docs/README.md) | `PIPELINE_DESIGN.md`, `API_REFERENCE.md` |
| `database/` | PostgreSQL 스키마 / ERD | [`database/README.md`](../database/README.md) | `schema.sql` (17 tables + 4 views) |

---

## 4. 최상위 문서 (이 디렉터리)

| 파일 | 내용 |
|------|------|
| `README.md` | 이 문서 — 전체 문서 지도 / 길찾기 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 시스템 전체 데이터 흐름, 서비스 통신, 일일 파이프라인 다이어그램 |
| [`STATUS.md`](STATUS.md) | 전체 개발 현황 (모듈 간 연동 매트릭스 + 기능별 진행) |
| [`USAGE.md`](USAGE.md) | 설치 · 실행 방법 |
| `architecture.png` · `analysis_flow.png` · `data_struct.png` · `story_board.png` | 설계 다이어그램 이미지 |
| `dev_note.txt` | 개발 메모 (개인 백로그) |

---

## 5. 루트 레벨 문서

| 파일 | 독자 | 역할 |
|------|------|------|
| [`../README.md`](../README.md) | 사람 | 프로젝트 소개 + 분석 프로세스 시각화 |
| [`../CLAUDE.md`](../CLAUDE.md) | AI (Claude Code) | 프로젝트 작업 지침 + 아키텍처 요약 + 문서 길찾기 |

> **README vs CLAUDE.md**: `README.md`는 사람 온보딩(무엇을, 왜, 어떻게)에, `CLAUDE.md`는 AI 작업 지침(어디에 무엇이 있고 어떤 규약을 따르나)에 집중합니다.

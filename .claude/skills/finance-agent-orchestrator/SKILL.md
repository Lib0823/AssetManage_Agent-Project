---
name: finance-agent-orchestrator
description: FinanceManage_Agent 모노레포(web-app/api-server/ai-agent/database)의 개발·유지보수 작업을 담당 전문가(들)에게 라우팅하고 조율한다. 기능 추가, 버그 수정, 리팩토링, 여러 모듈에 걸친 통합 작업, 그리고 "다시 해줘", "이어서", "업데이트", "수정 보완", "이전 작업 기반으로" 같은 후속 요청에서 반드시 사용. 단순 질문(코드 설명, 문서 조회)은 이 스킬 없이 직접 답한다.
---

# FinanceManage_Agent 하네스 오케스트레이터

## 역할
이 프로젝트는 서로 다른 언어/런타임의 3개 모듈(Vue3, Spring Boot, FastAPI)이 REST와 공유 DB로만 연결된 모노레포다. 이 스킬은 들어온 작업이 어느 모듈에 속하는지 판단해 전문 에이전트(`backend-engineer`, `ai-pipeline-engineer`, `frontend-engineer`, `integration-qa`)에게 라우팅하고, 여러 모듈에 걸친 작업일 때는 팀으로 조율한다.

## Phase 0: 컨텍스트 확인
작업 시작 전 `_workspace/`(프로젝트 루트, 없으면 생성하지 않고 넘어감) 존재 여부를 확인한다:
- `_workspace/`가 있고 사용자가 "그 부분만 다시/수정/보완"을 요청 → **부분 재실행**: 해당 담당 에이전트만 재호출, 다른 에이전트는 건드리지 않는다.
- `_workspace/`가 있고 사용자가 새로운 기능/입력을 제시 → **새 실행**: 기존 `_workspace/`를 `_workspace_prev/`로 이동한 뒤 새로 시작한다.
- `_workspace/`가 없음 → **초기 실행**.

## Phase 1: 라우팅 판단 (전문가 풀 패턴)
**실행 모드는 작업 범위에 따라 동적으로 결정한다** — 이 프로젝트의 실제 작업 패턴(최근 커밋: `feat(web-app)`, `feat(api-server)` 등)은 대부분 모듈 하나에 국한되므로, 매번 팀을 구성하는 것은 과잉이다.

1. 요청이 건드리는 모듈을 판별한다:
   - `web-app/**` 관련 → frontend-engineer
   - `api-server/**` 관련 → backend-engineer
   - `ai-agent/**` 관련 → ai-pipeline-engineer
   - `database/`, Liquibase changelog → backend-engineer(스키마 소스 소유자)가 1차 담당, ai-agent가 그 테이블을 쓰면 ai-pipeline-engineer도 참여
2. **단일 모듈 작업** → **서브 에이전트 모드**: 해당 에이전트 1명을 `Agent` 도구로 직접 호출한다(`model: "opus"` 명시). 팀 오버헤드가 불필요하다.
3. **2개 이상 모듈에 걸친 작업**(새 기능이 API 계약을 새로 만들고 프론트도 그걸 소비하는 경우 등) → **에이전트 팀 모드**: `TeamCreate`로 관련 에이전트 + `integration-qa`를 묶어 구성하고 `TaskCreate`로 작업을 나눈다. 계약을 먼저 확정하는 쪽(대개 backend-engineer)이 확정 즉시 `SendMessage`로 공유하고, 나머지는 그 계약을 향해 병렬 진행한다.
4. **여러 독립 모듈 변경이지만 서로 참조가 없는 경우**(예: 무관한 화면 2개를 동시에 손보는 요청) → **서브 에이전트 병렬**: `Agent`를 여러 번 `run_in_background: true`로 호출하고 결과만 수집한다(팀 통신 불필요).

## Phase 2: QA 트리거
2개 이상 모듈이 실제로 관련된 작업(팀 모드로 진행한 경우, 또는 서브 에이전트 모드라도 DB 스키마·API 계약이 바뀐 경우)은 완료 즉시 `integration-qa`를 호출해 관련 경계면만 점진적으로 검증한다. 단일 모듈 내부 변경(예: 화면 스타일 조정)까지 매번 QA를 부르지 않는다 — 오버헤드다.

## 데이터 전달
- **파일 기반**: `_workspace/{담당}_{작업}.md`에 각 에이전트가 변경 요약을 남긴다. 최종 결과는 실제 코드 변경 자체이고, `_workspace/`는 감사 추적용으로 보존한다.
- **팀 모드**: 계약 확정은 `SendMessage`로 실시간 공유, 작업 진행은 `TaskCreate`/`TaskUpdate`로 추적.
- **서브 에이전트 모드**: `Agent` 도구의 반환값을 오케스트레이터가 직접 수집해 사용자에게 종합 보고한다.

## 에러 핸들링
- 에이전트가 실패(테스트 실패, 빌드 에러)하면 1회 재시도 후에도 실패 시, 실패 원인과 함께 그대로 보고한다 — 조용히 건너뛰지 않는다.
- `integration-qa`가 "빌드 깨짐/런타임 크래시" 급 불일치를 발견하면 최종 종합을 기다리지 않고 즉시 담당 에이전트에게 되돌린다.
- 여러 에이전트의 발견이 상충하면(예: 백엔드는 필드명 A로 확정했다고 하는데 프론트는 B로 안다고 함) 삭제하지 않고 두 주장을 출처와 함께 병기해 사용자에게 보고한다.

## 팀 크기
현재 팀은 backend-engineer / ai-pipeline-engineer / frontend-engineer / integration-qa 4명으로, 이 프로젝트의 3개 기술 스택(Java/Python/Vue) + 경계면 검증이라는 4개 축과 정확히 대응한다. 작업 범위가 이보다 훨씬 커지지 않는 한(예: 별도 모바일 앱 추가) 팀원을 늘리지 않는다.

## 후속 작업
"다시 해줘", "그 부분만 수정", "이전 결과 기반으로 개선"류 요청은 Phase 0의 부분 재실행 경로를 탄다 — 전체를 처음부터 다시 하지 않고 관련 에이전트만 재호출한다.

## 테스트 시나리오
- **정상 흐름**: "AssetsView에 해외 자산 통화별 필터를 추가해줘" → 단일 모듈(frontend) 판단 → frontend-engineer 서브 에이전트 호출 → 기존 `assetApi` 응답으로 실데이터 구현(mock 금지) → lint 통과 → 완료 보고.
- **에러 흐름**: "매수 안전망 필터에 거래대금 조건을 추가해줘" → ai-agent(로직) + api-server(feature_threshold_config 시드 갱신) 2개 모듈 → 팀 모드 구성 → backend-engineer가 새 컬럼/시드값을 먼저 확정해 SendMessage로 공유 → ai-pipeline-engineer가 그 값으로 필터 로직 구현 중 pytest 실패 → 1회 재시도 후에도 실패 → 실패 원인(임계값 타입 불일치)과 함께 팀 전체에 보고, integration-qa가 DB 시드값과 코드 기본값이 아직 어긋나 있음을 추가로 확인해 함께 보고.

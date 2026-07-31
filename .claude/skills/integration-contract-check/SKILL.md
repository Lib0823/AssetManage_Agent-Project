---
name: integration-contract-check
description: 두 모듈 이상(web-app/api-server/ai-agent/database)에 걸친 변경 후, 또는 "경계면 확인해줘", "통합 테스트", "정합성 검증", "여러 모듈 같이 바뀌었는데 문제 없는지" 같은 요청에서 반드시 사용. API 응답 shape과 프론트 소비 코드, DB 컬럼과 백엔드/ai-agent 매핑을 교차 비교하는 절차를 다룬다.
---

# 경계면 정합성 검증 절차

## 왜 "따로 통과"가 안전을 보장하지 않는가
web-app/api-server/ai-agent는 각자 독립적으로 빌드·테스트·린트를 통과할 수 있지만, 셋을 잇는 것은 코드가 아니라 **암묵적 계약**(필드명, 컬럼명, TR_ID 상수)이다. 컴파일러도 타입체커도 이 계약을 검사해주지 않는다 — Python DataFrame 컬럼명 오타나 Java DTO 필드명 변경은 각 언어 안에서는 유효한 코드이기 때문이다. 그래서 경계면은 사람(또는 이 스킬을 쓰는 에이전트)이 직접 양쪽을 나란히 읽고 비교해야 한다.

## 확인 대상 3개 경계면

### 1. DB ↔ ai-agent
- `database/README.md`의 테이블별 컬럼 표를 기준으로 삼는다.
- ai-agent의 `DatabaseRepository`(`database/` 모듈)가 INSERT/UPDATE하는 컬럼명이 실제 Liquibase changelog가 정의한 컬럼명과 정확히 일치하는지 확인한다.
- 특히 내부 DataFrame 컬럼명과 DB 컬럼명이 다른 지점(`final_score`→`scaler_score`, `volume_ratio`→`vol_avg_multiple`, `institution_net_buy`→`institutional_net_buy`)을 우선 점검한다 — 이름이 비슷해서 오타가 나기 쉬운 지점이다.

### 2. DB/api-server ↔ web-app
- api-server 컨트롤러가 반환하는 DTO 필드명을 읽는다(예: `MarketHeatmapResponse`, `StockDetailAnalysisResponse`).
- `web-app/src/services/api.js`의 해당 호출부와, 그 결과를 소비하는 뷰 컴포넌트(`web-app/src/views/**`)가 참조하는 필드명을 같은 자리에서 비교한다.
- `ApiResponse<T>`의 `data` 내부 구조(중첩 객체/배열 여부)까지 맞춰본다 — 최상위 필드만 보고 중첩 구조를 놓치는 것이 흔한 실수다.
- null/누락 가능 필드(예: quote 비활성 시의 `notice`)를 프론트가 실제로 방어적으로 처리하는지 확인한다.

### 3. api-server ↔ ai-agent
- `TradeExecutor`(ai-agent, `execution/trade_executor.py`)가 호출하는 `POST /api/trading/execute` 요청 바디를 읽는다.
- api-server `InternalController`가 그 요청을 어떤 스키마로 파싱하는지 읽는다.
- 두 쪽의 필드명·타입·필수여부가 일치하는지 확인한다.

## 절차
1. 이번 변경이 어느 경계면(들)에 걸쳐 있는지 먼저 판단한다(변경된 파일 목록 기준).
2. 해당 경계면의 "기준 쪽"(보통 DB 스키마 또는 백엔드 DTO)을 먼저 읽는다.
3. "소비하는 쪽"(프론트 컴포넌트, ai-agent repository, InternalController)을 나란히 읽으며 필드명·타입을 한 줄씩 비교한다.
4. 불일치를 발견하면: 파일:라인, 기대값, 실제값, 심각도(빌드 깨짐/런타임 크래시/조용한 오류)를 기록한다.
5. 문제가 없는 경계면도 "무엇을 어떤 근거로 확인했는지"를 리포트에 남긴다 — 검증 없이 "이상 없음"이라고 쓰지 않는다.

## 알려진 취약 패턴 (우선 의심)
- KIS TR_ID 상수 오사용(국내 mock/real 변형과 해외 V-변형을 혼동) — 과거 실제 버그 사례(`TRADE_HISTORY_FIX_SUMMARY.md`).
- 내부 계산 변수명과 DB 컬럼명이 유사하지만 다른 경우(스코어링 로직).
- 프론트가 옵셔널 필드(`notice`, 실패 시 null)를 필수로 가정하고 렌더링해 크래시.

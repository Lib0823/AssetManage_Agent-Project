# 모의투자 제거 → 실전투자 전용 전환 설계

- 날짜: 2026-08-26
- 브랜치: `feature/timeseries-nosql-migration`
- 상태: 승인됨 (사용자 확인 완료)

## 배경

`FinanceManage_Agent`는 현재 KIS(한국투자증권) 모의투자와 실전투자를 **유저별로 선택 가능한 이중 체계**로 지원한다. 모의/실전 분기가 도메인, TR_ID, 해외주식 TR, 실시간 체결통보 등 여러 지점에 걸쳐 있어 기능이 복잡하고 유지보수 비용이 크다. `_docs/dev_note.txt`의 최종 항목("모의투자 부분 제거 후 실투자로 전환")대로, 모의투자 개념을 시스템 전체에서 제거하고 실전투자 전용으로 단순화한다.

이 문서는 "분기 반전"(if MOCK → if REAL)이 아니라 "분기 제거"(분기 자체를 지우고 실전 값을 직접 사용)를 원칙으로 한다.

## 조사 결과 요약 (현재 아키텍처)

두 개의 독립된 모의/실전 메커니즘이 존재한다:

1. **api-server — 유저별 `user_kis_accounts.account_mode`** (v1.15, 기본 MOCK). 실제 매매·잔고 조회 전체가 이 값으로 라우팅된다.
   - `KisAuthService.baseUrlFor(mode)` → 도메인(모의 `openapivts...:29443` / 실전 `openapi...:9443`)
   - `KisApiClient.convertTrId(trId, baseUrl)` → 국내 TR `VTTC↔TTTC` 자동 변환
   - `OverseasTradingService` → 해외 TR `VTTS/VTTT↔TTTS/TTTT` 자체 분기 (convertTrId 미적용 대상)
   - 실시간 체결통보(`RealtimeTr`, `UserFillsConnectionFactory`, `UserFillsUpstreamConnection`, `FillFrameParser`) → ws-url(:31000/:21000), TR(`H0STCNI9`/`H0STCNI0`, 해외 `H0GSCNI0`)
2. **ai-agent — 앱 레벨 `KIS_MODE`** (`.env`, 기본 VIRTUAL). 매매가 아니라 **시세/수급 데이터 수집 전용** 계정(Stage 1~3). Stage 6 실제 매매는 이미 Kafka 경유로 api-server가 전담하므로 이 계정은 거래를 하지 않는다.

시세/재무 조회(KisQuoteService, DART)는 원래부터 항상 실전 도메인만 사용하므로 영향 없음.

## 결정 사항 (사용자 확인 완료)

| 항목 | 결정 |
|---|---|
| ai-agent 자체 KIS 계정도 실전 전용으로 통일할 것인가 | **예** |
| 기존 모의계좌로 등록된 `user_kis_accounts` 행 처리 | **DB에서 삭제** (수동 재입력 유도 아님) |
| 실전 전환과 함께 추가 매매 안전장치(주문 한도 등)를 넣을 것인가 | **아니오, 이번 범위 밖** — 별도 작업으로 분리 |

## 범위

### 1. Database
- 신규 Liquibase changeset(`v1.28-remove-kis-account-mode.yaml`):
  1. `DELETE FROM user_kis_accounts WHERE account_mode = 'MOCK'`
  2. `account_mode` 컬럼 DROP
- 과거 changeset(`v1.15-kis-account-mode.yaml`)은 이력이므로 수정하지 않는다(Liquibase 불변 원칙).

### 2. api-server
- `KisAuthService`: `baseUrlFor(mode)` 제거, 실전 도메인 고정 상수/설정값 사용.
- `KisApiClient`: `convertTrId` 제거. 호출부(`TradingService`, `AssetService` 등)의 TR_ID 리터럴을 `TTTC*`로 직접 교체.
- `OverseasTradingService`: `VTTS/VTTT` 상수 → `TTTS/TTTT`로 교체, V→T 분기 로직 제거.
- 실시간 체결통보 관련 클래스: 모의 분기 제거, 실전 ws-url(`:21000`)·TR(`H0STCNI0`/해외 `H0GSCNI0`) 고정.
- `UserKisAccount` 엔티티 및 관련 DTO(`RegisterRequest`, `ValidateKisAccountRequest`, `UpdateKisAccountRequest`, `KisAccountResponse` 등)에서 `accountMode`/`mode` 필드 제거.
- `application.yml` / `.env.example`: `kis.base-url`(모의)와 `kis.real-base-url`(실전) 이원화를 단일 실전 도메인 설정으로 정리.
- 테스트 9개 파일(`KisApiClientTest`, `TradingServiceTest`, `OverseasTradingServiceTest`, `AuthServiceTest`, `UserServiceTest`, `AssetServiceTest`, `KisFillFrameDecryptorTest`, `TradeOrderConsumerIntegrationTest`, `KisRateLimitAndCacheIntegrationTest`): 모의 모드 케이스 삭제, 실전 케이스만 유지/보강.

### 3. ai-agent
- `collectors/kis_client.py`: `self.mode`/`convert_tr_id`의 VIRTUAL 분기 제거, TR_ID 실전값 고정.
- `config/settings.py`, `.env.example`: `KIS_MODE` 설정 제거.
- 데드코드 확인: `get_holdings()`가 프로덕션에서 호출되지 않음 — 삭제 후보(전환과 함께 정리 권장, 위험 낮음).
- `ai-agent/CLAUDE.md`의 "KIS 모의투자 주문까지 수행한다" 서술을 실제 동작(시세수집 전용, 매매는 api-server가 Kafka로 처리)에 맞게 정정.

### 4. web-app
- `RegisterFinanceView.vue`: 가입 시 모의/실전 선택 UI 제거, 항상 실전 계좌로 등록.
- `KisModeBadge.vue`: 삭제.
- `ProfileView.vue`, `AppHeader.vue`, `TradingView.vue` 등: 모드 배지·조건부 문구 제거.
- 그 외 grep 히트(`App.vue`, `stores/realtime.js`, `stores/auth.js`, `tokenStorage.js` 등)는 "REAL"이 무관한 문맥에서 매칭됐을 가능성이 있어 실행 단계에서 파일별로 실제 관련 여부를 확인한다.

### 5. 문서
- `api-server/_docs/KIS_API_GUIDE.md`: §2(두 개의 자격증명 경로 표), §4(TR_ID 매핑 표), §5.3(WebSocket URL), §5.7(체결통보)을 모의/실전 이원 체계에서 단일 실전 체계로 재작성.
- 루트/모듈 `CLAUDE.md`, `_docs/STATUS.md`, `.env.example`(루트+모듈), `database/README.md` 등에서 모의투자 관련 서술 정정.
- `_docs/dev_note.txt`의 해당 백로그 항목을 완료로 이동.

### 6. 실행 방식
- `finance-agent-orchestrator` 스킬로 팀 구성: `backend-engineer`(DB changeset·경계면 계약을 먼저 확정해 공유), `ai-pipeline-engineer`, `frontend-engineer`.
- 각 담당 작업 후 모듈별 전체 테스트 스위트 재실행.
- 완료 후 `integration-qa` 포함, 이번 QA 라운드와 동일한 review→verify 축소판으로 **사이드 이펙트 검토**(무관한 곳을 건드리지 않았는지, 모의투자 잔재가 남지 않았는지 grep 전수 확인 포함).

## 리스크 및 완화

| 리스크 | 완화 |
|---|---|
| 실전 도메인 호출은 실제 자금이 움직인다 | 이번 전환 자체는 로직/설정 변경이며 실제 주문 트리거는 기존과 동일(유저의 `is_active`/수동 주문). 새로 위험이 늘어나는 지점 없음 — 다만 "모의"라는 완충재가 사라지므로 실행 후 실제 KIS 실전 앱키로 검증 필요 |
| 기존 모의계좌 삭제는 되돌리기 어려움 | Liquibase changeset으로 실행해 감사 추적 남김. 실행 전 DB 백업 권장 |
| 광범위한 리팩토링이라 사이드이펙트 위험 | 팀별 전체 테스트 재실행 + 별도 QA 검토 라운드로 방어 |
| 문서/주석에 "모의" 언급이 대량으로 남아있어 누락 가능 | 완료 후 전수 grep(`모의`, `MOCK`, `VIRTUAL`, `account_mode`, `VTTC`, `VTTS`, `VTTT`)으로 확인 |

## 비범위 (Out of scope)

- 추가 매매 안전장치(주문 한도, 확인 절차 등) — 사용자가 별도 작업으로 분리하기로 확인함.
- 국내 정규주문 시장가 고정, 해외주문 지정가 전용 등 기존에 알려진 별개 이슈 — 이번 전환과 무관, 손대지 않음.

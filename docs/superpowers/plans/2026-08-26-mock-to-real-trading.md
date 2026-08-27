# 모의투자 제거 → 실전투자 전용 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KIS 모의투자(mock trading) 지원을 api-server·ai-agent·web-app 전체에서 제거하고, 모든 매매/조회/실시간 경로를 KIS 실전투자 도메인·TR_ID로 고정한다.

**Architecture:** 이것은 "분기 반전"이 아니라 "분기 제거"다. `account_mode`/`KIS_MODE`로 실행 시점에 도메인·TR_ID를 고르던 코드를, 컴파일 타임에 고정된 실전 값을 쓰는 코드로 바꾼다. DB에서 모의계좌 행을 지우고 `account_mode` 컬럼 자체를 드롭한다.

**Tech Stack:** Spring Boot(Java 21)/Liquibase, FastAPI(Python), Vue 3.

**참조 스펙:** `docs/superpowers/specs/2026-08-26-mock-to-real-trading-design.md`

## Global Constraints

- 실행 순서: **backend-engineer(DB changeset + api-server)가 먼저** — `account_mode` 컬럼 제거와 TR_ID 실전값이 다른 두 팀의 계약 기준점이다.
- 모든 TR_ID는 실전값(`TTTC*`, `TTTS*`, `TTTT*`, `H0STCNI0`, `H0GSCNI0`)만 남긴다. 모의값(`VTTC*`, `VTTS*`, `VTTT*`, `H0STCNI9`)은 코드에서 완전히 제거한다(문자열 리터럴로도 남기지 않는다 — 죽은 fallback을 만들지 않는다).
- Liquibase 과거 changeset(`v1.11`, `v1.15`)은 이력이므로 절대 수정하지 않는다. 새 changeset만 추가한다.
- 각 태스크의 "완료 기준"은 해당 모듈의 **전체 테스트 스위트가 회귀 없이 통과**하는 것이다. 부분 테스트만 돌리고 끝내지 않는다.
- 완료 후 반드시 `grep -rn "모의\|MOCK\|VIRTUAL\|account_mode\|VTTC\|VTTS\|VTTT\|H0STCNI9"`로 코드(prod 소스, 테스트 포함)에 잔재가 없는지 전수 확인한다. 히스토리성 문서(changelog 파일 자체, `_workspace*/`, `docs/superpowers/`)는 대상 아님.

---

## Task 1 (backend-engineer): DB changeset — `account_mode` 제거

**Files:**
- Create: `api-server/src/main/resources/db/changelog/mvp/v1.28-remove-kis-account-mode.yaml`
- Modify: `api-server/src/main/resources/db/changelog/db.changelog-master.yaml` (include 추가)

**Interfaces:**
- Produces: `user_kis_accounts` 테이블에 `account_mode` 컬럼이 더 이상 존재하지 않음. 이후 모든 태스크는 이 컬럼을 참조하지 않는다는 전제로 진행한다.

**변경 내용:**
1. `v1.15-kis-account-mode.yaml`을 읽고 `account_mode` 컬럼의 정확한 타입/제약을 확인한다.
2. 새 changeset 2개를 순서대로 작성:
   - changeSet A: `<delete tableName="user_kis_accounts"><where>account_mode = 'MOCK'</where></delete>` (raw SQL도 가능, Liquibase `delete` 태그 권장)
   - changeSet B: `<dropColumn tableName="user_kis_accounts" columnName="account_mode"/>`
   - `rollback`은 changeSet B에 대해서만 컬럼 재생성(`addColumn`, 기본값 `MOCK`)을 정의한다. changeSet A(삭제된 행)는 되돌릴 수 없으므로 rollback에 "데이터 복구 불가, 백업에서 복원" 주석만 남긴다.
3. `db.changelog-master.yaml`에 새 파일을 include 순서대로 추가한다(v1.27 다음).

**검증:**
```bash
cd api-server && ./gradlew bootRun
```
기동 로그에서 `v1.28-remove-kis-account-mode.yaml` 두 changeSet이 적용됐는지 확인(`databasechangelog` 테이블 조회로 재확인). 기동 전 `user_kis_accounts`에 `account_mode='MOCK'` 행이 있었다면 기동 후 사라졌는지 `psql`로 직접 확인.

- [ ] **Step: changeset 작성 + 기동 검증 + DB 직접 확인**
- [ ] **Step: 커밋** (`fix(db): drop mock KIS accounts and account_mode column`)

---

## Task 2 (backend-engineer): `KisAuthService` / `KisApiClient` 실전 전용화

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/KisAuthService.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/client/KisApiClient.java`
- Modify: `api-server/src/main/resources/application.yml` (`kis.base-url`/`kis.real-base-url` 정리)
- Modify: `.env.example`, `api-server/.env.example`(있다면)
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/KisAuthServiceTest.java`(있으면), `api-server/src/test/java/com/inbeom/apiserver/client/KisApiClientTest.java`

**Interfaces:**
- Consumes: Task 1에서 `account_mode` 컬럼 제거 완료.
- Produces: `KisAuthService`가 항상 실전 도메인으로 자격증명을 반환. `KisApiClient`에 `convertTrId(trId, baseUrl)` 메서드가 더 이상 존재하지 않음(호출부는 TR_ID 리터럴을 직접 씀). 이 두 가지가 Task 3~5의 전제.

**변경 내용:**
1. `KisAuthService.baseUrlFor(AccountMode mode)`(또는 유사 시그니처) 및 `UserKisAccount.getAccountMode()` 참조를 전수 검색해 제거. 도메인은 `kis.real-base-url` 설정값(이름을 `kis.base-url`로 단순화해도 되나, `KisQuoteService`가 이미 `kis.quote-base-url`을 쓰므로 명명 충돌 없이 `kis.base-url`로 통일 권장)으로 고정.
2. `application.yml`에서 모의 도메인(`https://openapivts.koreainvestment.com:29443`) 라인과 관련 주석을 삭제. `real-base-url`을 `base-url`로 리네임(참조하는 다른 코드도 함께 수정).
3. `KisApiClient.convertTrId`를 삭제한다. 이 메서드를 호출하던 지점(주로 `TradingService`, `AssetService`)에서 `VTTC*` 상수를 `TTTC*`로 직접 교체한다(예: `VTTC8434R`→`TTTC8434R`, `VTTC0802U`→`TTTC0802U`, `VTTC0801U`→`TTTC0801U`, `VTTC0081R`→`TTTC0081R`, `VTTC8908R`→`TTTC8908R`).
4. `KisApiClientTest`에서 `convertTrId`/모의 도메인 관련 테스트 케이스를 삭제. 남은 테스트가 실전 TR_ID로 요청이 나가는지 검증하도록 조정.
5. `UserKisAccount.java`에서 `accountMode` 필드와 관련 getter/setter, enum(`AccountMode` 클래스가 별도 파일이면 그것도) 삭제.

**검증:**
```bash
cd api-server && ./gradlew test --tests "*Kis*" --tests "*Trading*" --tests "*Asset*"
```
전부 통과 확인 후 전체 스위트로 넘어간다(Task 5 이후 최종 확인).

- [ ] **Step: KisAuthService/KisApiClient 수정 + 관련 단위 테스트 갱신 + 통과 확인**
- [ ] **Step: 커밋** (`refactor(api-server): remove mock KIS domain and TR_ID conversion`)

---

## Task 3 (backend-engineer): `OverseasTradingService` 실전 전용화

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/OverseasTradingService.java`
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/OverseasTradingServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 실전 도메인 상수.
- Produces: 해외주식 매매/잔고/이력 전부 실전 TR_ID만 사용.

**변경 내용:**
1. `TR_BALANCE`/`TR_BUY`/`TR_SELL`/`TR_HISTORY`/`TR_PENDING`/`TR_ORDERABLE` 상수를 각각 `VTTS3012R`→`TTTS3012R`, `VTTT1002U`→`TTTT1002U`, `VTTT1006U`→`TTTT1006U`, `VTTS3035R`→`TTTS3035R`, `VTTS3018R`→`TTTS3018R`, `VTTS3007R`→`TTTS3007R`로 교체.
2. "해외 TR 모드 변환: 실전이면 접두 V→T ... 모의면 그대로" 로직(주석에 언급된 메서드)을 삭제하고, 위 상수를 직접 사용하도록 호출부를 정리.
3. 클래스/메서드 Javadoc의 "(모의)" 표기를 "(실전)"으로 정정.
4. `OverseasTradingServiceTest`에서 모의 TR_ID를 기대하던 assertion을 실전 TR_ID로 교체.

**검증:**
```bash
cd api-server && ./gradlew test --tests "*Overseas*"
```

- [ ] **Step: OverseasTradingService 수정 + 테스트 갱신 + 통과 확인**
- [ ] **Step: 커밋** (`refactor(api-server): switch overseas trading to real TR IDs`)

---

## Task 4 (backend-engineer): 실시간 체결통보 실전 전용화

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/realtime/RealtimeTr.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/realtime/UserFillsConnectionFactory.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/realtime/UserFillsUpstreamConnection.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/realtime/FillFrameParser.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/realtime/OverseasTrKey.java`(모드 분기가 있다면)
- Modify: `api-server/src/main/resources/application.yml` (ws-url 이원화 정리)
- Test: `api-server/src/test/java/com/inbeom/apiserver/realtime/KisFillFrameDecryptorTest.java`

**Interfaces:**
- Consumes: Task 1의 `account_mode` 제거(더 이상 이 컬럼으로 ws-url/TR을 고를 수 없음 — 애초에 안 골라야 함).
- Produces: 체결통보 브리지가 항상 실전 ws-url(`:21000`)과 실전 TR(`H0STCNI0`, 해외 `H0GSCNI0`)로 연결.

**변경 내용:**
1. `RealtimeTr`(또는 유사 enum/매핑)에서 모의 TR(`H0STCNI9`)과 관련 분기를 제거, `H0STCNI0`만 남긴다.
2. `UserFillsConnectionFactory`/`UserFillsUpstreamConnection`이 계정 모드로 ws-url(`:31000`/`:21000`)을 고르던 로직을 제거하고 `:21000` 고정.
3. `application.yml`의 체결통보 관련 이원 도메인 설정(§118행대 부근 주석 참고)을 단일 실전 값으로 정리.
4. `KisFillFrameDecryptorTest`에서 모의 TR 케이스를 삭제.

**검증:**
```bash
cd api-server && ./gradlew test --tests "*Fill*" --tests "*Realtime*"
```

- [ ] **Step: 실시간 체결통보 클래스 4개 수정 + 테스트 갱신 + 통과 확인**
- [ ] **Step: 커밋** (`refactor(api-server): pin realtime fills bridge to the real KIS domain`)

---

## Task 5 (backend-engineer): DTO/엔티티 정리 + 전체 회귀 테스트

**Files:**
- Modify: `api-server/src/main/java/com/inbeom/apiserver/dto/auth/RegisterRequest.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/dto/auth/ValidateKisAccountRequest.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/dto/user/UpdateKisAccountRequest.java`
- Modify: `api-server/src/main/java/com/inbeom/apiserver/dto/user/KisAccountResponse.java`
- Modify: 그 외 Task 1~4에서 발견된 `accountMode`/`mode` 필드를 가진 DTO 전부(1차 grep 결과: `PendingOrderResponse`, `OrderableResponse`, `PlaceReservedOrderRequest`, `InternalTradeRequest`, `KisDailyCcldResponse`, `KisBalanceResponse` 등 — 각 파일을 열어 실제로 계정 모드 필드가 있는지 확인 후 있는 것만 수정. 없으면 grep 오탐이므로 건드리지 않는다)
- Modify: `api-server/src/main/java/com/inbeom/apiserver/service/UserService.java`, `AuthService.java` (회원가입/계정 등록 흐름에서 모드 read/write 제거)
- Modify: `api-server/src/main/java/com/inbeom/apiserver/controller/TradingController.java` (모드 관련 응답 필드가 있다면)
- Test: `api-server/src/test/java/com/inbeom/apiserver/service/AuthServiceTest.java`, `UserServiceTest.java`, `TradingServiceTest.java`, `AssetServiceTest.java`, `kafka/TradeOrderConsumerIntegrationTest.java`, `client/KisRateLimitAndCacheIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~4의 모든 실전 전용화.
- Produces: api-server 전체에 `account_mode`/모의 TR/모의 도메인 참조가 0건.

**변경 내용:**
1. Task 시작 전에 아래 명령으로 잔여 참조를 재확인하고, 위 "Files" 목록을 실제 grep 결과로 갱신한다:
   ```bash
   grep -rn "account_mode\|AccountMode\|\bMOCK\b\|모의\|VTTC\|VTTS\|VTTT\|H0STCNI9" api-server/src
   ```
2. 각 히트를 파일별로 열어 실제로 계정 모드와 관련 있는지 판단한다(단순 "MOCK"이 다른 테스트 mocking 라이브러리 관련 단어와 겹쳐 오탐일 수 있음 — 예: Mockito 관련 주석).
3. 관련 있는 것만 실전 전용으로 수정, 무관한 것은 그대로 둔다.
4. `RegisterFinanceView` 대응 백엔드 엔드포인트(`ValidateKisAccountRequest` 처리 서비스)에서 모드 파라미터를 받지 않도록 수정 — 기본적으로 항상 실전 검증.

**검증:**
```bash
cd api-server && ./gradlew build test redisTest kafkaTest timescaledbTest
```
4개 태스크 전부 `BUILD SUCCESSFUL`, 실패/에러 0건. 이 결과를 `_workspace/`(있으면) 또는 커밋 메시지 본문에 기록.

마지막으로:
```bash
grep -rn "account_mode\|AccountMode\|모의\|VTTC\|VTTS\|VTTT\|H0STCNI9" api-server/src
```
결과 0건 확인(Mockito 등 무관한 "mock" 단어는 예외).

- [ ] **Step: DTO/엔티티/서비스 잔여 정리**
- [ ] **Step: 전체 테스트 4개 태스크 실행 + 통과 확인**
- [ ] **Step: 잔여 참조 grep 0건 확인**
- [ ] **Step: 커밋** (`refactor(api-server): finish removing account-mode plumbing`)
- [ ] **Step: backend-engineer가 ai-pipeline-engineer/frontend-engineer에게 "account_mode 컬럼 제거 완료, TR_ID는 전부 실전값"을 통보** (finance-agent-orchestrator 팀 모드의 SendMessage 사용)

---

## Task 6 (ai-pipeline-engineer): ai-agent KIS 클라이언트 실전 전용화

**Files:**
- Modify: `ai-agent/collectors/kis_client.py`
- Modify: `ai-agent/config/settings.py`
- Modify: `.env.example` (루트), `ai-agent/.env.example`(있다면)
- Test: `ai-agent/tests/test_kis_client.py`

**Interfaces:**
- Consumes: Task 5 완료 통보(이 태스크는 api-server와 독립적으로 시작 가능하지만, 완료 보고는 통보 이후 팀 종합 시점에 맞춘다).
- Produces: `KISClient`가 `KIS_MODE` 없이 항상 실전 TR_ID/도메인 사용.

**변경 내용:**
1. `KISClient.__init__`에서 `self.mode = os.getenv('KIS_MODE', 'VIRTUAL')` 라인을 삭제.
2. `convert_tr_id(self, base_tr_id)` 메서드를 삭제. 이 메서드를 호출하던 지점(현재 `get_holdings()`가 유일한 호출부이며 프로덕션에서 호출되지 않는 데드코드로 확인됨)을 처리한다:
   - `get_holdings()`가 정말 어디서도 호출되지 않는지 `grep -rn "get_holdings" ai-agent --include="*.py" | grep -v tests/`로 재확인.
   - 호출부가 없으면 `get_holdings()` 메서드와 `convert_tr_id` 둘 다 삭제, 관련 테스트(`TestConvertTrId` 클래스, `TestGetHoldings` 클래스)도 삭제.
   - 만약 호출부가 발견되면(재확인 결과가 QA 조사와 다르면) 삭제하지 말고 TR_ID를 `TTTC8434R` 리터럴로 직접 교체.
3. `config/settings.py`에서 `KIS_MODE` 관련 필드 제거.
4. `.env.example`에서 `KIS_MODE=VIRTUAL` 라인과 "# KIS 모의투자 (주문/잔고/수집)" 주석을 "# KIS API (실전투자, 시세/수급 데이터 수집 전용)"로 정정.
5. `logger.info(f"KISClient initialized in {self.mode} mode")` 라인 삭제(또는 모드 없이 초기화 로그만 남김).

**검증:**
```bash
cd ai-agent && ./venv/bin/python -m pytest tests/test_kis_client.py -v
```
전부 통과. `TestConvertTrId`/`TestGetHoldings`가 삭제됐다면 collected 테스트 수가 그만큼 줄어든 것을 확인.

- [ ] **Step: kis_client.py/settings.py 수정 + 테스트 갱신 + 통과 확인**
- [ ] **Step: 커밋** (`refactor(ai-agent): drop KIS_MODE toggle, always use real domain`)

---

## Task 7 (ai-pipeline-engineer): ai-agent 전체 회귀 + 문서

**Files:**
- Modify: `ai-agent/CLAUDE.md`
- Test: 전체 `ai-agent/tests/`

**Interfaces:**
- Consumes: Task 6.
- Produces: ai-agent 전체에 `KIS_MODE`/`VIRTUAL`/모의 참조 0건.

**변경 내용:**
1. `ai-agent/CLAUDE.md`의 "모듈 개요" 절 — "KIS 모의투자 주문까지 수행한다"를 "KIS 실전 데이터 수집(매매 실행은 Kafka 경유 api-server가 전담)"으로 정정.
2. 잔여 확인:
   ```bash
   grep -rn "KIS_MODE\|VIRTUAL\|모의" ai-agent --include="*.py" --include="*.md" | grep -v tests/
   ```
   히트 전부 검토해 무관한 것(예: 다른 맥락의 "모의")은 남기고 관련 있는 것만 정리.

**검증:**
```bash
cd ai-agent && ./venv/bin/python -m pytest -q
```
전체 pass, 회귀 0건. 커버리지가 기존(93%)보다 크게 떨어지지 않는지 확인.

- [ ] **Step: 문서 정정 + 잔여 grep 확인**
- [ ] **Step: 전체 테스트 실행 + 통과 확인**
- [ ] **Step: 커밋** (`docs(ai-agent): correct mock-trading references after real-only conversion`)

---

## Task 8 (frontend-engineer): 가입/계정 관리 화면에서 모드 선택 제거

**Files:**
- Modify: `web-app/src/views/auth/RegisterFinanceView.vue`
- Modify: `web-app/src/views/settings/ProfileView.vue`
- Modify: `web-app/src/components/common/AppHeader.vue`
- Delete: `web-app/src/components/common/KisModeBadge.vue`
- Modify: 이 컴포넌트를 import하는 모든 파일(삭제 전 `grep -rn "KisModeBadge" web-app/src`로 사용처 전수 확인 후 import문·템플릿 태그 제거)

**Interfaces:**
- Consumes: backend-engineer의 Task 5 완료 통보(`RegisterRequest`/`KisAccountResponse`에 `accountMode` 필드가 없어짐 — 프론트가 더 이상 이 필드를 보내거나 기대하지 않아야 함).
- Produces: 가입/설정 화면에서 모의/실전 선택 UI가 완전히 사라짐.

**변경 내용:**
1. `RegisterFinanceView.vue`에서 모의/실전 라디오 버튼(또는 유사 선택 UI)과 관련 상태(`accountMode`/`mode` ref), 제출 시 보내던 필드를 제거.
2. `KisModeBadge.vue`를 사용하는 곳(`ProfileView.vue`, `AppHeader.vue`, 그 외 grep으로 발견되는 곳)에서 배지 컴포넌트와 import를 제거.
3. `KisModeBadge.vue` 파일 삭제.
4. `api.js`의 `userApi`/`authApi` 관련 메서드가 `accountMode`를 요청 바디에 싣고 있었다면 제거.

**검증:**
```bash
cd web-app && npm run lint && npm run build
```

- [ ] **Step: 컴포넌트/화면 수정 + 삭제 + lint/build 통과 확인**
- [ ] **Step: 커밋** (`refactor(web-app): remove mock/real account mode selection UI`)

---

## Task 9 (frontend-engineer): 거래 화면 잔여 정리 + 전체 확인

**Files:**
- Modify: `web-app/src/views/detail/TradingView.vue`
- Modify: `web-app/src/views/detail/TransactionsView.vue`
- Modify: `web-app/src/views/main/AssetsView.vue`
- Modify: `web-app/src/views/detail/AssetDetailView.vue`
- 확인만(오탐 가능성 높음, 실제 관련 있을 때만 수정): `web-app/src/App.vue`, `web-app/src/stores/realtime.js`, `web-app/src/stores/auth.js`, `web-app/src/utils/tokenStorage.js`, `web-app/src/services/realtime.js`, `web-app/src/services/api.js`

**Interfaces:**
- Consumes: Task 8.
- Produces: web-app 전체에 모의/실전 모드 관련 UI·상태 0건.

**변경 내용:**
1. 아래로 잔여 참조를 재확인한다:
   ```bash
   grep -rn "account_mode\|accountMode\|\bMOCK\b\|모의\|KisMode" web-app/src
   ```
2. `TradingView.vue`/`TransactionsView.vue`/`AssetsView.vue`/`AssetDetailView.vue`에서 실제로 모드 배지·조건부 문구가 있으면 제거.
3. "확인만" 목록의 파일들은 열어서 히트가 "REAL"이 다른 변수명/문맥에서 매칭된 오탐인지 실제 계정 모드 관련인지 판단한다. **오탐이면 파일을 건드리지 않는다** — 무관한 코드를 "정리"하려다 새 이슈를 만들지 않는다.

**검증:**
```bash
cd web-app && npm run lint && npm run build
```
가능하면 dev 서버(`npm run dev`) 띄워 회원가입 화면에서 모드 선택 UI가 실제로 사라졌는지 브라우저로 확인.

- [ ] **Step: 잔여 화면 정리(관련 있는 것만) + lint/build 확인**
- [ ] **Step: 브라우저 실측(가능하면)**
- [ ] **Step: 커밋** (`refactor(web-app): finish removing mock-trading UI remnants`)

---

## Task 10 (문서 — backend-engineer 또는 팀리드): `KIS_API_GUIDE.md` 재작성

**Files:**
- Modify: `api-server/_docs/KIS_API_GUIDE.md`
- Modify: 루트 `CLAUDE.md`, `_docs/STATUS.md`, `database/README.md`, `_docs/dev_note.txt`

**Interfaces:**
- Consumes: Task 1~9 전부 완료(코드가 실전 전용이어야 문서를 정확히 쓸 수 있음).

**변경 내용:**
1. `KIS_API_GUIDE.md` §2("두 개의 KIS 자격증명 경로" 표에서 모의/실전 이원 서술 제거, 단일 실전 도메인으로), §4(TR_ID 매핑 표를 전부 실전값으로 교체 — `VTTC8434R`→`TTTC8434R` 등), §5.3(WebSocket URL 표에서 모의 행 삭제), §5.7(체결통보 표에서 `H0STCNI9` 행 삭제, "모의 도메인의 체결통보 지원 여부는 불확실"이라는 MUST-VERIFY 문구도 삭제 — 이제 실전만 쓰므로 무의미).
2. 루트 `CLAUDE.md`의 KIS 관련 서술("모의투자" 언급 부분)을 실전 전용으로 정정.
3. `_docs/dev_note.txt`의 "최종 모의투자 부분 제거 후 실투자로 전환.!!" 항목을 완료 섹션으로 이동.
4. `database/README.md`에 v1.28 changeset 반영(스키마 변경 이력 언급이 있다면).

**검증:** 사람이 읽고 확인(자동 테스트 없음). `grep -rn "모의\|MOCK\|VTTC\|VTTS\|VTTT" api-server/_docs/KIS_API_GUIDE.md`로 잔여 0건 확인(과거형 서술로 "이전에는 모의투자도 지원했으나..." 같은 이력 설명 한두 줄은 허용).

- [ ] **Step: KIS_API_GUIDE.md 재작성 + 나머지 문서 정정**
- [ ] **Step: 잔여 grep 확인**
- [ ] **Step: 커밋** (`docs: rewrite KIS integration docs for real-trading-only`)

---

## Task 11 (integration-qa 또는 팀리드): 사이드 이펙트 검토

**Files:** 없음(읽기 전용 검토)

**Interfaces:**
- Consumes: Task 1~10 전부 완료.

**변경 내용:**
1. 전체 저장소에서 최종 전수 grep:
   ```bash
   grep -rn "account_mode\|AccountMode\|\bMOCK\b\|모의\|VTTC\|VTTS\|VTTT\|H0STCNI9\|KIS_MODE\|VIRTUAL" \
     api-server/src ai-agent --include="*.py" --include="*.java" \
     web-app/src
   ```
   남은 히트를 전부 검토해 무관한 오탐인지 확정한다.
2. 이번 전환 커밋들의 `git diff`를 모듈별로 훑어, 설계 문서(Task 1~10) 범위 밖의 변경이 섞이지 않았는지 확인한다(무관한 리팩토링·스타일 변경이 끼어들지 않았는지).
3. 3개 모듈 전체 테스트 스위트를 마지막으로 한 번 더 통짜로 재실행해 회귀가 없는지 재확인한다.

**검증:** 위 3가지 확인 결과를 요약해 팀리드에게 보고. 이상 없으면 완료.

- [ ] **Step: 잔여 참조 전수 확인**
- [ ] **Step: diff 범위 이탈 여부 확인**
- [ ] **Step: 3개 모듈 전체 테스트 재실행**
- [ ] **Step: 결과 보고**

---

## Self-Review 메모 (계획 작성자용, 실행 시 무시)

- 스펙의 6개 섹션(DB/api-server/ai-agent/web-app/문서/실행방식) 전부 Task 1~11에 매핑됨.
- Task 5, 9는 "1차 grep 결과가 오탐일 수 있음"을 명시해, 실행자가 무관한 파일을 건드리지 않도록 가드를 넣었다.
- Task 1의 데이터 삭제는 rollback 불가를 명시하고 백업 권장 문구를 넣었다.
- 플레이스홀더("TBD" 등) 없음. 각 태스크에 정확한 파일 경로와 실행 가능한 커맨드를 넣었다.

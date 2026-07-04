# KIS API 연동 가이드

> 한국투자증권(KIS) Open API 연동 구조를 코드 기준으로 정리합니다. 두 개의 분리된 자격증명 경로, 토큰 캐싱, TR_ID 매핑, DART/Jasypt 연동을 다룹니다.

## 목차
1. [BFF 아키텍처](#1-bff-아키텍처)
2. [두 개의 KIS 자격증명 경로](#2-두-개의-kis-자격증명-경로)
3. [KisApiClient (공통 호출)](#3-kisapiclient-공통-호출)
4. [TR_ID 매핑](#4-tr_id-매핑)
5. [실시간 시세 (WebSocket)](#5-실시간-시세-websocket)
6. [DART 연동 (DartApiClient)](#6-dart-연동-dartapiclient)
7. [Jasypt 자격증명 암호화](#7-jasypt-자격증명-암호화)
8. [예외 처리 (KisApiException)](#8-예외-처리-kisapiexception)
9. [관련 문서](#9-관련-문서)

---

## 1. BFF 아키텍처

브라우저는 KIS를 직접 호출하지 않습니다. CORS 및 자격증명 보안을 위해 Spring Boot가 BFF(Backend For Frontend)로 중계합니다.

```
Vue3 → Spring Boot → KIS
```

---

## 2. 두 개의 KIS 자격증명 경로

KIS 연동에는 목적이 다른 두 경로가 있으며, 자격증명 소스/도메인/캐시 방식이 모두 다릅니다.

| 구분 | (A) 사용자별 거래/잔고 | (B) 앱 레벨 시세/재무 |
|------|------------------------|------------------------|
| 관리 서비스 | `KisAuthService` | `KisQuoteService` |
| base URL 설정 | `kis.base-url`(모의) / `kis.real-base-url`(실전) | `kis.quote-base-url` |
| 기본 도메인 | **계정 모드(`account_mode`)로 per-user 결정** — MOCK `openapivts…:29443` / REAL `openapi…:9443` | real `https://openapi.koreainvestment.com:9443` |
| 자격증명 소스 | `user_kis_accounts` (Jasypt 암호화 appKey/appSecret) | env `KIS_QUOTE_APP_KEY` / `KIS_QUOTE_APP_SECRET` |
| 캐시 구조 | per-user `Map<Long kisAccountId, KisTokenCache>` (`ConcurrentHashMap`) | 단일 app-level `AtomicReference<QuoteTokenCache>` |
| 캐시 TTL | `kis.token-cache-ttl` 기본 `86400000ms` (24h) | 24h |
| 사용처 | 거래/잔고 (Asset, Trading) | 시세/재무 (`CompanyInfoService`, `MarketDataService` indices) |

**(A) 사용자별 토큰 캐시 성능:**

| 상황 | 지연 | 동작 |
|------|------|------|
| Cache hit | ~50ms | 메모리 캐시 반환 |
| Cache miss | ~500ms | DB 조회 + 복호화 + OAuth `POST /oauth2/tokenP` |

**(B) 앱 레벨 비활성화 조건:**
quote 키가 비어 있으면 `isQuoteEnabled()=false`가 되어 시세/재무 필드가 `null`로 반환되고 안내(notice)가 포함됩니다.

> **분리 이유:** 시세/재무 API는 mock 도메인에서 제공되지 않습니다. 따라서 (B)는 real 도메인을 사용하며 `CompanyInfoService`와 `MarketDataService` indices에서만 쓰입니다.

**(A) 사용자별 모의/실전 라우팅 (`account_mode`):**

- 사용자는 회원가입/내정보에서 **모의(MOCK)/실전(REAL)** 모드를 선택하고, 해당 모드의 KIS 앱키를 그 도메인으로 인증(`validate-kis-account`의 `mode`)한다.
- 저장된 `user_kis_accounts.account_mode`(v1.15, 기본 MOCK)를 기준으로 이후 그 사용자의 **모든 매매/잔고/체결통보 호출 도메인·TR이 결정**된다.
- `KisAuthService.baseUrlFor(mode)`가 도메인을, `getKisCredentials(...).baseUrl()`이 호출부(TradingService/AssetService/OverseasTradingService/fills)에 도메인을 전달한다. OAuth 토큰도 계정 도메인으로 발급되며 캐시는 `kisAccountId`별이라 도메인별로 자연 분리된다.
- 국내 TR은 `KisApiClient.convertTrId(trId, baseUrl)`가 도메인 보고 `VTTC↔TTTC` 자동 변환, 해외 TR은 `OverseasTradingService`가 `V↔T`를 모드로 분기, 체결통보는 모드로 ws-url(:31000/:21000)·TR(H0STCNI9/0)을 선택한다.
- 기존 계정은 `account_mode` 기본값 MOCK로 하위호환. (시세/재무 (B) 경로는 사용자 모드와 무관하게 공유 quote 키를 그대로 사용.)

---

## 3. KisApiClient (공통 호출)

`KisApiClient`는 KIS 호출의 공통 헤더와 도메인 처리를 담당합니다.

**`convertTrId(trId, baseUrl)`:** 호출의 도메인(baseUrl, 계정 모드로 결정)에 따라 `VTTC` ↔ `TTTC` 접두사를 교체합니다. `FHKST*` / `FHKUP*` 시세 TR_ID 및 해외 TR은 변경하지 않습니다. 매매/조회 호출은 `get`/`post`의 8-arg 오버로드로 `credentials.baseUrl()`을 첫 인자로 넘겨 사용자별 도메인 라우팅을 수행합니다.

**공통 헤더:**

| 헤더 | 값 |
|------|-----|
| `authorization` | `Bearer {KIS_TOKEN}` |
| `appkey` | appKey |
| `appsecret` | appSecret |
| `tr_id` | TR_ID |
| `custtype` | `P` |

**RestTemplate 타임아웃:** connect 5s, read 18s.

---

## 4. TR_ID 매핑

| TR_ID | 기능 | 엔드포인트 |
|-------|------|------------|
| `VTTC8434R` | inquire-balance (보유/현금) | `/uapi/domestic-stock/v1/trading/inquire-balance` |
| `VTTC0802U` | order-cash BUY | `/uapi/domestic-stock/v1/trading/order-cash` |
| `VTTC0801U` | order-cash SELL | `/uapi/domestic-stock/v1/trading/order-cash` (동일) |
| `VTTC0081R` | inquire-daily-ccld (거래내역 3개월) | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` |
| `VTTC8908R` | inquire-psbl-order (매수가능 조회) | `/uapi/domestic-stock/v1/trading/inquire-psbl-order` (모의 trading 도메인) |
| `FHKST01010100` | inquire-price (현재가/PER/PBR/EPS/BPS/시가총액) | quote real 도메인 |
| `FHKST01010200` | inquire-asking-price-exp-ccn (실시간 호가 10단계) | quote real 도메인 |
| `FHKST66430200` | income-statement | quote real 도메인 |
| `FHKST66430300` | financial-ratio | quote real 도메인 |
| `FHKST66430600` | stability-ratio | quote real 도메인 |
| `FHKUP03500100` | inquire-index-price (지수) | quote real 도메인 |

**해외주식 (미국, overseas)**

| TR_ID | 기능 | 엔드포인트 |
|-------|------|------------|
| `VTTS3012R` | inquire-balance (해외 잔고/보유) | `/uapi/overseas-stock/v1/trading/inquire-balance` (모의 trading 도메인) |
| `VTTT1002U` | order BUY (미국 매수, 지정가) | `/uapi/overseas-stock/v1/trading/order` (모의 trading 도메인) |
| `VTTT1006U` | order SELL (미국 매도, 지정가) | `/uapi/overseas-stock/v1/trading/order` (동일) |
| `HHDFS00000300` | price (해외 현재가) | quote real 도메인 |
| `HHDFS76200200` | price-detail (해외 현재가 상세) | quote real 도메인 |
| `HHDFS76200100` | inquire-asking-price (해외 1호가 — 미국은 1단계만) | quote real 도메인 |
| `VTTS3035R` | inquire-ccnl (해외 주문체결내역/거래내역) | `/uapi/overseas-stock/v1/trading/inquire-ccnl` (모의) |
| `VTTS3018R` | inquire-nccs (해외 미체결내역) | `/uapi/overseas-stock/v1/trading/inquire-nccs` (모의) |
| `VTTS3007R` | inquire-psamount (해외 매수가능금액) | `/uapi/overseas-stock/v1/trading/inquire-psamount` (모의) |
| `H0GSCNI0`/`H0GSCNI9` | 실시간 체결통보(미국) — WebSocket, tr_key=HTS ID, AES | KR_FILL 과 같은 연결에서 함께 구독 |

> **US 동등화:** 미국은 국내와 동일하게 거래내역·미체결·매수가능·1호가·실시간 체결가/체결통보를 제공합니다. **단 호가 depth 는 1단계만**(KIS 가 미국 10호가 미제공) — 국내 10호가와 다른 구조적 차이.

> **해외 TR 주의 (convertTrId 미변환):** `convertTrId`는 `VTTC`↔`TTTC`(국내) 접두사만 교체하며 해외 TR(`VTTS`/`VTTT`/`HHDFS`)은 변환하지 않습니다. 따라서 해외 모의투자 TR은 **V 변형을 직접 사용**합니다(`VTTS3012R`, `VTTT1002U`, `VTTT1006U`). 모의투자 해외 주문은 **지정가 전용**입니다(시장가 미지원). 해외 시세(`HHDFS*`)는 국내와 동일하게 **real quote 도메인**을 사용합니다(모의 도메인 미제공).

> **주의 (버그 수정):** 거래내역 조회는 `VTTC0081R`이 올바른 값입니다. 이전 버전에서 `VTTC8001R`을 사용한 버그가 있었으며, 현재는 `VTTC0081R`로 수정되었습니다.

> **미체결 주문 (`GET /trading/pending-orders`):** 검증되지 않은 신규 미체결 전용 TR(예: `VTTC8036R`)을 도입하지 않습니다. 거래내역과 동일한 `inquire-daily-ccld`(`VTTC0081R`) 결과를 재사용하여, 그중 미체결(잔량>0 또는 `orderStatus`가 `PENDING`/`PARTIAL`)인 행만 필터링해 제공합니다. 실데이터 기반·저위험 방식이며 예외/빈결과 시 빈 리스트를 반환합니다.

> **배당 수령·현금 입출금 내역 (미지원, 공식 확인 2026-06):** KIS 국내주식 OpenAPI에는 개인 **현금 입출금 내역(ledger)** 전용 TR이 없습니다(`ksdinfo/mand-deposit`=예탁원 의무예치일정, `pension/inquire-deposit`=퇴직연금 예수금뿐). 개인 **배당 수령 내역** 전용 TR도 없습니다. 배당 관련으로는 종목 기준 **예탁원정보(배당일정) `HHKDB669102C0`**(`ksdinfo_dividend`, 배당기준일·주당배당금·배당률)와 **배당률 상위 순위**(`국내주식-106`)만 제공됩니다. 따라서 거래내역 화면의 "기타(배당금)" 합계는 데이터 소스가 없어 제거했고, 매매 손익은 `TTTC8715R`(기간별매매손익)/`TTTC8494R`(잔고 실현손익)로만 확인 가능합니다. 향후 '배당 캘린더'가 필요하면 `HHKDB669102C0`로 별도 구현하세요. (근거: 공식 `koreainvestment/open-trading-api` 레포)

---

## 5. 실시간 시세 (WebSocket)

REST 폴링과 별개로, KIS는 실시간 호가·체결가를 **WebSocket**으로 푸시한다. 브라우저는 KIS WebSocket을 직접 연결하지 않고, REST와 동일하게 Spring Boot가 브리지(BFF) 역할로 중계한다.

> **Phase 1 (구현): 실시간 호가 + 체결가.** 종목코드(`tr_key`) 기반으로 누구나 구독.
> **Phase 2 (구현, 국내 전용, 플래그 뒤): 실시간 체결통보(`H0STCNI0`/`H0STCNI9`).** 내 주문의 체결/접수 결과를 푸시 받는다. HTS ID 기반·사용자별 연결·복호화가 필요해 Phase 1과 구조가 다르다(아래 §5.7). 기본 비활성(`kis.realtime.fills.enabled=false`). **해외 체결통보(`H0GSCNI0`/`H0GSCNI9`)도 구현** — 같은 유저당 fills 연결에서 KR_FILL 과 함께 구독(tr_id 별 ekey/iv 캡처).

### 5.1 TR (실시간 등록)

| TR_ID | 구분 | 내용 |
|-------|------|------|
| `H0STASP0` | 국내 | 실시간 호가 (10단계 매도/매수 호가·잔량) |
| `H0STCNT0` | 국내 | 실시간 체결가 (체결 단가·수량·시각) |
| `HDFSASP0` | 미국(해외) | 실시간 호가 |
| `HDFSCNT0` | 미국(해외) | 실시간 체결가 |
| `H0STCNI0` | 국내 | 실시간 체결통보(실전) — **Phase 2 구현, 플래그 뒤** (§5.7) |
| `H0STCNI9` | 국내 | 실시간 체결통보(모의) — **Phase 2 구현, 플래그 뒤** (§5.7) |
| `H0GSCNI0`/`H0GSCNI9` | 해외 | 실시간 체결통보 — **구현**(US 동등화, fills 플래그 뒤) |

### 5.2 approval_key

WebSocket 접속에는 REST OAuth 토큰이 아닌 **WebSocket 접속키(`approval_key`)**가 필요하다.

| 항목 | 값 |
|------|-----|
| 엔드포인트 | `POST /oauth2/Approval` |
| 입력 | `grant_type=client_credentials`, `appkey`, `secretkey`(= appSecret) |
| 출력 | `approval_key` (WebSocket 핸드셰이크 시 사용) |

> REST 토큰(`/oauth2/tokenP`)과는 별개의 키다. 시세용 앱 단위 자격증명(`KIS_QUOTE_APP_KEY`/`KIS_QUOTE_APP_SECRET`, 경로 (B))로 발급한다.

### 5.3 WebSocket URL

| 환경 | URL |
|------|-----|
| 실전(real) | `ws://ops.koreainvestment.com:21000` |
| 모의(mock) | `ws://ops.koreainvestment.com:31000` |

### 5.4 구독(subscribe) 프레임

KIS 업스트림에 등록/해제는 JSON 프레임으로 전송한다. `tr_type`이 등록(`1`) / 해제(`2`)를 구분한다.

```json
{
  "header": {
    "approval_key": "{approval_key}",
    "custtype": "P",
    "tr_type": "1",
    "content-type": "utf-8"
  },
  "body": {
    "input": { "tr_id": "H0STASP0", "tr_key": "005930" }
  }
}
```

| `tr_type` | 동작 |
|-----------|------|
| `1` | 등록(subscribe) |
| `2` | 해제(unsubscribe) |

- `tr_id`: 5.1의 실시간 TR (예: `H0STASP0` 호가, `H0STCNT0` 체결가).
- `tr_key`: 종목코드(국내 6자리, 미국은 거래소 접두 포함 심볼).

### 5.5 PINGPONG (연결 유지)

KIS는 주기적으로 `PINGPONG` 제어 프레임을 보낸다. 클라이언트(브리지)는 수신한 `PINGPONG`을 **그대로 되돌려 보내** 연결을 유지한다. 응답하지 않으면 세션이 끊긴다.

### 5.6 `/ws/realtime` 브리지

Spring Boot가 브라우저용 WebSocket 엔드포인트 `/ws/realtime`을 노출한다.

```
Browser ⇄ Spring /ws/realtime ⇄ KIS upstream (ws://ops.koreainvestment.com)
```

- **인증**: 브라우저는 핸드셰이크 시 쿼리 파라미터로 JWT를 전달한다 — `/ws/realtime?token={JWT}`. (WebSocket 핸드셰이크는 커스텀 헤더를 싣기 어려워 `Authorization` 헤더 대신 `?token=`을 사용.)
- **자격증명 보호**: KIS `approval_key`·appkey/appsecret은 서버에만 존재하고 브라우저로 노출하지 않는다(REST BFF와 동일 원칙).
- **PINGPONG**: 업스트림 PINGPONG 처리는 브리지가 담당하며, 브라우저 클라이언트는 신경 쓰지 않는다.

> **Phase 1**은 호가(`H0STASP0`/`HDFSASP0`)와 체결가(`H0STCNT0`/`HDFSCNT0`)만 중계한다. **체결통보(국내 `H0STCNI0`/`H0STCNI9`)는 Phase 2에서 플래그 뒤로 구현**되며 구조가 다르다(아래 §5.7). **해외 체결통보(`H0GSCNI0`)는 보류(deferred).**

### 5.7 실시간 체결통보 (Phase 2, 국내 전용, 플래그 뒤)

체결통보는 "내 주문의 체결/접수"를 푸시하는 **사용자 사적(私的) 실시간**이다. 종목코드로 구독하는 호가·체결가(Phase 1, 시세용 앱 자격증명)와 달리, **사용자별 거래 자격증명(경로 (A), `user_kis_accounts`)** 과 **HTS ID**가 필요하다. 기본 비활성(`kis.realtime.fills.enabled=false`)이며, 플래그를 켜야 동작한다.

| 항목 | 값 |
|------|-----|
| TR_ID | 국내 실전 `H0STCNI0` / 모의 `H0STCNI9` (해외 `H0GSCNI0`은 보류) |
| `tr_key` | 종목코드가 아니라 **HTS ID** (사용자 계정의 `hts_id`) |
| 자격증명 | 사용자별 거래 자격증명(경로 (A)) 으로 발급한 `approval_key` |
| 도메인 | 모의 trade 도메인 `ws://ops.koreainvestment.com:31000` + 사용자 계정키 |
| 연결 단위 | **사용자별(per-user) 연결** (호가/체결가의 종목 단위 공유 연결과 다름) |
| 암호화 | 본문이 **AES-CBC 암호화**되어 도착 — 복호화 필요 |
| 활성화 플래그 | `kis.realtime.fills.enabled` (기본 `false`) |

**AES-CBC 복호화:** 체결통보 본문은 평문이 아니라 AES-CBC로 암호화되어 온다. 복호화에 필요한 **키(`ekey`)와 IV(`iv`)는 구독 ACK(subscribe 응답 프레임)**에 담겨 내려온다(체결통보 첫 구독 시 1회). 브리지는 이 `ekey`/`iv`를 보관했다가 이후 도착하는 체결통보 본문을 복호화해 브라우저로 중계한다.

**`/ws/realtime` 프로토콜(체결통보):** 브라우저는 Phase 1과 동일한 소켓(`/ws/realtime?token={JWT}`)에서 다음 메시지로 체결통보를 구독한다.

```json
{ "action": "subscribe", "type": "fills" }
```

- `type: "fills"` → 브리지가 사용자 `hts_id`를 `tr_key`로 KIS 업스트림에 `H0STCNI0`/`H0STCNI9` 등록.
- `hts_id`가 비어 있거나 플래그가 꺼져 있으면 구독은 무시되고 notice를 반환한다(에러 아님).
- 종목 단위 시세(`type` 미지정/호가·체결가)와 달리 `tr_key`를 클라이언트가 보내지 않는다(서버가 사용자 `hts_id`로 채움).

> **HTS ID 컬럼:** 사용자별 HTS ID는 `user_kis_accounts.hts_id`(v1.11에서 추가)에 저장한다. 비어 있으면 체결통보 구독이 불가하다.

> **검증 한계(MUST-VERIFY):** 실제 체결통보 수신은 **HTS ID 설정 + 실전/스트리밍 계좌 + 정규장 시간 + 실제 체결 발생**이 모두 충족돼야 확인 가능하다. **모의(mock) 도메인의 체결통보 스트리밍 지원 여부는 불확실**하여 라이브 검증이 끝나지 않았다. 해외 체결통보(`H0GSCNI0`)는 보류(deferred).

---

## 6. DART 연동 (DartApiClient)

| 항목 | 값 |
|------|-----|
| base URL | `https://opendart.fss.or.kr/api` |
| API 키 | env `DART_API_KEY` (비어 있으면 `isEnabled=false`, DART 필드 `null`) |

**주요 메서드:**

| 메서드 | 설명 |
|--------|------|
| `getCorpCode` | 6자리 stock code → 8자리 corp code 변환. `corpCode.xml` ZIP을 StAX로 파싱, `ConcurrentHashMap` 캐시 |
| `getCompanyProfile` | `/company.json` |
| `getDisclosureList` | `/list.json` |

**status 코드:** `"000"`=정상, `"013"`=데이터 없음.

---

## 7. Jasypt 자격증명 암호화

사용자별 KIS 자격증명(`user_kis_accounts.app_key` / `app_secret`)은 Jasypt로 암호화되어 `ENC(...)` 형식으로 저장됩니다.

| 항목 | 값 |
|------|-----|
| 알고리즘 | `PBEWITHHMACSHA512ANDAES_256` |
| iterations | 1000 |
| IV generator | `RandomIvGenerator` |
| 출력 인코딩 | base64 |
| password | env `JASYPT_PASSWORD` |

> 이전의 `PBEWithMD5AndDES` 방식이 아닙니다.

`KisAuthService`는 캐시 miss 시 자격증명을 복호화하며, 복호화 실패 시 plaintext로 폴백합니다(MVP 한정).

---

## 8. 예외 처리 (KisApiException)

`KisApiException`의 팩토리 메서드:

| 메서드 | 용도 |
|--------|------|
| `clientError` | 4xx 클라이언트 오류 |
| `serverError` | 5xx 서버 오류 |
| `networkError` | 네트워크 오류 |
| `oauthFailed` | OAuth 토큰 발급 실패 (`KIS_OAUTH_FAILED=4004` 참조) |

---

## 9. 관련 문서

- [../README.md](../README.md) — 프로젝트 개요 및 실행 방법
- [API_DESIGN.md](API_DESIGN.md) — 전체 REST API 명세
- [AUTHENTICATION_FLOW.md](AUTHENTICATION_FLOW.md) — 앱 JWT 인증 흐름
- [ARCHITECTURE.md](ARCHITECTURE.md) — 시스템 아키텍처
- [STATUS.md](STATUS.md) — 구현 현황

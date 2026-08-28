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

KIS 연동에는 목적이 다른 두 경로가 있으며, 자격증명 소스/캐시 방식이 다릅니다. 두 경로 모두 **KIS 실전투자 도메인(`https://openapi.koreainvestment.com:9443`) 고정**입니다(2026-08 QA에서 모의투자 지원 전체 제거 — `docs/superpowers/specs/2026-08-26-mock-to-real-trading-design.md` 참고).

| 구분 | (A) 사용자별 거래/잔고 | (B) 앱 레벨 시세/재무 |
|------|------------------------|------------------------|
| 관리 서비스 | `KisAuthService` | `KisQuoteService` |
| base URL 설정 | `kis.base-url` | `kis.quote-base-url` |
| 도메인 | `https://openapi.koreainvestment.com:9443` (고정) | `https://openapi.koreainvestment.com:9443` (고정) |
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

**(A) 사용자별 KIS 계좌 등록:**

- 사용자는 회원가입/내정보에서 본인의 **실전투자** KIS 앱키(appKey/appSecret)를 등록하고, 서버가 실전 도메인으로 검증(`validate-kis-account`)한다.
- `getKisCredentials(kisAccountId).baseUrl()`이 호출부(TradingService/AssetService/OverseasTradingService/fills)에 고정된 실전 도메인을 전달한다. OAuth 토큰도 이 도메인으로 발급되며 캐시는 `kisAccountId`별이다.
- 국내 TR은 `TTTC*`, 해외 TR은 `TTTS*`/`TTTT*`, 체결통보는 `H0STCNI0`(국내)/`H0GSCNI0`(해외)로 전부 고정이다 — 도메인·TR을 실행 시점에 선택하는 분기 로직은 없다.

> **이력**: 2026-08-27 이전에는 유저별 `account_mode`(MOCK/REAL) 컬럼으로 모의/실전 도메인·TR을 분기했다(v1.15 도입). 시스템 복잡도를 줄이기 위해 모의투자 지원을 전체 제거했고, `account_mode` 컬럼도 v1.28 changeset으로 삭제됐다.

---

## 3. KisApiClient (공통 호출)

`KisApiClient`는 KIS 호출의 공통 헤더와 도메인 처리를 담당합니다.

TR_ID는 실전값(`TTTC*`/`TTTS*`/`TTTT*`)을 호출부가 직접 지정하며, 도메인 보고 접두사를 바꿔주는 변환 로직은 없다(과거 `convertTrId`는 모의/실전 분기 제거와 함께 삭제됨). 매매/조회 호출은 `get`/`post`의 8-arg 오버로드로 `credentials.baseUrl()`(고정 실전 도메인)을 첫 인자로 넘긴다.

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

TR_ID는 전부 **실전투자값 고정**입니다(모의/실전 분기 없음).

| TR_ID | 기능 | 엔드포인트 |
|-------|------|------------|
| `TTTC8434R` | inquire-balance (보유/현금) | `/uapi/domestic-stock/v1/trading/inquire-balance` |
| `TTTC0802U` | order-cash BUY | `/uapi/domestic-stock/v1/trading/order-cash` |
| `TTTC0801U` | order-cash SELL | `/uapi/domestic-stock/v1/trading/order-cash` (동일) |
| `TTTC0081R` | inquire-daily-ccld (거래내역 3개월) | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` |
| `TTTC8908R` | inquire-psbl-order (매수가능 조회) | `/uapi/domestic-stock/v1/trading/inquire-psbl-order` |
| `FHKST01010100` | inquire-price (현재가/PER/PBR/EPS/BPS/시가총액) | quote 도메인 |
| `FHKST01010200` | inquire-asking-price-exp-ccn (실시간 호가 10단계) | quote 도메인 |
| `FHKST66430200` | income-statement | quote 도메인 |
| `FHKST66430300` | financial-ratio | quote 도메인 |
| `FHKST66430600` | stability-ratio | quote 도메인 |
| `FHKUP03500100` | inquire-index-price (지수) | quote 도메인 |

**해외주식 (미국, overseas)**

| TR_ID | 기능 | 엔드포인트 |
|-------|------|------------|
| `TTTS3012R` | inquire-balance (해외 잔고/보유) | `/uapi/overseas-stock/v1/trading/inquire-balance` |
| `TTTT1002U` | order BUY (미국 매수, 지정가) | `/uapi/overseas-stock/v1/trading/order` |
| `TTTT1006U` | order SELL (미국 매도, 지정가) | `/uapi/overseas-stock/v1/trading/order` (동일) |
| `HHDFS00000300` | price (해외 현재가) | quote 도메인 |
| `HHDFS76200200` | price-detail (해외 현재가 상세) | quote 도메인 |
| `HHDFS76200100` | inquire-asking-price (해외 1호가 — 미국은 1단계만) | quote 도메인 |
| `TTTS3035R` | inquire-ccnl (해외 주문체결내역/거래내역) | `/uapi/overseas-stock/v1/trading/inquire-ccnl` |
| `TTTS3018R` | inquire-nccs (해외 미체결내역) | `/uapi/overseas-stock/v1/trading/inquire-nccs` |
| `TTTS3007R` | inquire-psamount (해외 매수가능금액) | `/uapi/overseas-stock/v1/trading/inquire-psamount` |
| `H0GSCNI0` | 실시간 체결통보(미국) — WebSocket, tr_key=HTS ID, AES | KR_FILL 과 같은 연결에서 함께 구독 |

> **US 동등화:** 미국은 국내와 동일하게 거래내역·미체결·매수가능·1호가·실시간 체결가/체결통보를 제공합니다. **단 호가 depth 는 1단계만**(KIS 가 미국 10호가 미제공) — 국내 10호가와 다른 구조적 차이.

> **해외 주문은 지정가 전용**입니다(시장가 미지원, `OverseasTradingService`가 `ORD_DVSN="00"` 고정). 해외 시세(`HHDFS*`)는 국내와 동일하게 quote 도메인을 사용합니다.

> **주의 (버그 수정, 이력):** 거래내역 조회는 `TTTC0081R`이 올바른 값입니다. 과거 `TTTC8001R`을 사용한 버그가 있었으며 수정되었습니다.

> **미체결 주문 (`GET /trading/pending-orders`):** 검증되지 않은 신규 미체결 전용 TR(예: `TTTC8036R`)을 도입하지 않습니다. 거래내역과 동일한 `inquire-daily-ccld`(`TTTC0081R`) 결과를 재사용하여, 그중 미체결(잔량>0 또는 `orderStatus`가 `PENDING`/`PARTIAL`)인 행만 필터링해 제공합니다. 실데이터 기반·저위험 방식이며 예외/빈결과 시 빈 리스트를 반환합니다.

**장내채권 (domestic-bond)**

| TR_ID | 기능 | 엔드포인트 |
|-------|------|------------|
| `CTSC8407R` | inquire-balance (보유 채권 — 로트 단위) | `/uapi/domestic-bond/v1/trading/inquire-balance` |
| `TTTC0958U` | sell (매도 주문) | `/uapi/domestic-bond/v1/trading/sell` |
| `CTSC8013R` | inquire-daily-ccld (일별 체결조회 = 거래내역) | `/uapi/domestic-bond/v1/trading/inquire-daily-ccld` |
| `CTPF1114R` | search-bond-info (종목 기본조회 — **검색 아님**) | `/uapi/domestic-bond/v1/quotations/search-bond-info` |
| `CTPF1101R` | issue-info (발행 정보) | `/uapi/domestic-bond/v1/quotations/issue-info` |
| `FHKBJ773400C0` | inquire-price (현재가) | `/uapi/domestic-bond/v1/quotations/inquire-price` |
| `FHKBJ773401C0` | inquire-asking-price (호가) | `/uapi/domestic-bond/v1/quotations/inquire-asking-price` |

> **채권 검색 API는 존재하지 않습니다.** `search-bond-info`는 이름과 달리 `PDNO`(12자리 종목코드)를 필수로 받는 **기본조회**이며, KIS 채권 API 18개 중 종목명·키워드로 찾는 것이 하나도 없습니다(2026-08 전수 확인). 그래서 이 프로젝트의 채권 기능은 **보유 채권 조회 + 매도**로 범위가 잡혀 있고, 진입점은 검색이 아니라 자산 화면의 채권 카드입니다. 매수는 진입 경로가 없어 함께 보류됐습니다.

> **매도는 종목이 아니라 "매수 로트" 단위입니다.** 잔고가 `pdno` + `buy_dt` + `buy_sqno` 단위로 로트를 쪼개 돌려주므로, 같은 채권을 다른 날 샀으면 별개 행입니다. 매도 요청에 `BUY_DT`/`BUY_SEQ`가 필수이며 — **응답 필드는 `buy_sqno`인데 요청 파라미터는 `BUY_SEQ`로 이름이 다릅니다.** 그대로 매핑하면 조용히 빈 값이 나갑니다.

> **매도 필수 파라미터가 매수와 다릅니다**: `ORD_DVSN`(주문구분), `SPRX_YN`(분리과세여부), `SLL_AGCO_OPPS_SLL_YN`(매도대행사반대매도여부)가 추가로 필요합니다. **`SPRX_YN`을 임의로 `N` 고정하면 안 됩니다** — 세금 처리가 달라지므로, 값이 없으면 서버가 400을 반환합니다.

> **잔고 조회는 연속조회가 필요합니다.** `INQR_CNDT`(필수), `CTX_AREA_FK200`, `CTX_AREA_NK200`을 받고 응답 헤더 `tr_cont`가 `M`이면 다음 페이지를 이어 받아야 합니다. 처리하지 않으면 보유 채권이 많을 때 첫 페이지만 보이고 총자산이 과소 계산됩니다.

> **채권 단가는 소수점을 갖습니다**(액면 기준가, 예 `9850.5`). 종목코드도 6자리 숫자가 아니라 **12자리 영숫자 혼합**(`KR2033022D33`)입니다. 주식용 정수 가격·6자리 코드 가정을 재사용하면 안 됩니다.

> **미확정 항목(실계좌 검증 필요)**: `ORD_QTY2`의 수량 단위(액면금액인지 좌수인지)가 공개 자료로 판별되지 않아, 금액 환산 계수를 `kis.bond.face-value-divisor` 설정값으로 분리해 두었습니다(기본 100은 추정). `PRDT_TYPE_CD="302"` 하드코딩 가능 여부도 미확인입니다.

> **배당 수령·현금 입출금 내역 (미지원, 공식 확인 2026-06):** KIS 국내주식 OpenAPI에는 개인 **현금 입출금 내역(ledger)** 전용 TR이 없습니다(`ksdinfo/mand-deposit`=예탁원 의무예치일정, `pension/inquire-deposit`=퇴직연금 예수금뿐). 개인 **배당 수령 내역** 전용 TR도 없습니다. 배당 관련으로는 종목 기준 **예탁원정보(배당일정) `HHKDB669102C0`**(`ksdinfo_dividend`, 배당기준일·주당배당금·배당률)와 **배당률 상위 순위**(`국내주식-106`)만 제공됩니다. 따라서 거래내역 화면의 "기타(배당금)" 합계는 데이터 소스가 없어 제거했고, 매매 손익은 `TTTC8715R`(기간별매매손익)/`TTTC8494R`(잔고 실현손익)로만 확인 가능합니다. 향후 '배당 캘린더'가 필요하면 `HHKDB669102C0`로 별도 구현하세요. (근거: 공식 `koreainvestment/open-trading-api` 레포)

---

## 5. 실시간 시세 (WebSocket)

REST 폴링과 별개로, KIS는 실시간 호가·체결가를 **WebSocket**으로 푸시한다. 브라우저는 KIS WebSocket을 직접 연결하지 않고, REST와 동일하게 Spring Boot가 브리지(BFF) 역할로 중계한다.

> **Phase 1 (구현): 실시간 호가 + 체결가.** 종목코드(`tr_key`) 기반으로 누구나 구독.
> **Phase 2 (구현, 국내 전용, 플래그 뒤): 실시간 체결통보(`H0STCNI0`).** 내 주문의 체결/접수 결과를 푸시 받는다. HTS ID 기반·사용자별 연결·복호화가 필요해 Phase 1과 구조가 다르다(아래 §5.7). 기본 비활성(`kis.realtime.fills.enabled=false`). **해외 체결통보(`H0GSCNI0`)도 구현** — 같은 유저당 fills 연결에서 국내 체결통보와 함께 구독(tr_id 별 ekey/iv 캡처).

### 5.1 TR (실시간 등록)

| TR_ID | 구분 | 내용 |
|-------|------|------|
| `H0STASP0` | 국내 | 실시간 호가 (10단계 매도/매수 호가·잔량) |
| `H0STCNT0` | 국내 | 실시간 체결가 (체결 단가·수량·시각) |
| `HDFSASP0` | 미국(해외) | 실시간 호가 |
| `HDFSCNT0` | 미국(해외) | 실시간 체결가 |
| `H0STCNI0` | 국내 | 실시간 체결통보 — **Phase 2 구현, 플래그 뒤** (§5.7) |
| `H0GSCNI0` | 해외 | 실시간 체결통보 — **구현**(US 동등화, fills 플래그 뒤) |

### 5.2 approval_key

WebSocket 접속에는 REST OAuth 토큰이 아닌 **WebSocket 접속키(`approval_key`)**가 필요하다.

| 항목 | 값 |
|------|-----|
| 엔드포인트 | `POST /oauth2/Approval` |
| 입력 | `grant_type=client_credentials`, `appkey`, `secretkey`(= appSecret) |
| 출력 | `approval_key` (WebSocket 핸드셰이크 시 사용) |

> REST 토큰(`/oauth2/tokenP`)과는 별개의 키다. 시세용 앱 단위 자격증명(`KIS_QUOTE_APP_KEY`/`KIS_QUOTE_APP_SECRET`, 경로 (B))로 발급한다.

### 5.3 WebSocket URL

`ws://ops.koreainvestment.com:21000` (실전투자, 고정).

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

> **Phase 1**은 호가(`H0STASP0`/`HDFSASP0`)와 체결가(`H0STCNT0`/`HDFSCNT0`)만 중계한다. **체결통보(국내 `H0STCNI0`, 해외 `H0GSCNI0`)는 Phase 2에서 플래그 뒤로 구현**되며 구조가 다르다(아래 §5.7).

### 5.7 실시간 체결통보 (Phase 2, 국내 전용, 플래그 뒤)

체결통보는 "내 주문의 체결/접수"를 푸시하는 **사용자 사적(私的) 실시간**이다. 종목코드로 구독하는 호가·체결가(Phase 1, 시세용 앱 자격증명)와 달리, **사용자별 거래 자격증명(경로 (A), `user_kis_accounts`)** 과 **HTS ID**가 필요하다. 기본 비활성(`kis.realtime.fills.enabled=false`)이며, 플래그를 켜야 동작한다.

| 항목 | 값 |
|------|-----|
| TR_ID | 국내 `H0STCNI0`, 해외 `H0GSCNI0` |
| `tr_key` | 종목코드가 아니라 **HTS ID** (사용자 계정의 `hts_id`) |
| 자격증명 | 사용자별 거래 자격증명(경로 (A)) 으로 발급한 `approval_key` |
| 도메인 | 실전 trade 도메인 `ws://ops.koreainvestment.com:21000` + 사용자 계정키 |
| 연결 단위 | **사용자별(per-user) 연결** (호가/체결가의 종목 단위 공유 연결과 다름) |
| 암호화 | 본문이 **AES-CBC 암호화**되어 도착 — 복호화 필요 |
| 활성화 플래그 | `kis.realtime.fills.enabled` (기본 `false`) |

**AES-CBC 복호화:** 체결통보 본문은 평문이 아니라 AES-CBC로 암호화되어 온다. 복호화에 필요한 **키(`ekey`)와 IV(`iv`)는 구독 ACK(subscribe 응답 프레임)**에 담겨 내려온다(체결통보 첫 구독 시 1회). 브리지는 이 `ekey`/`iv`를 보관했다가 이후 도착하는 체결통보 본문을 복호화해 브라우저로 중계한다.

**`/ws/realtime` 프로토콜(체결통보):** 브라우저는 Phase 1과 동일한 소켓(`/ws/realtime?token={JWT}`)에서 다음 메시지로 체결통보를 구독한다.

```json
{ "action": "subscribe", "type": "fills" }
```

- `type: "fills"` → 브리지가 사용자 `hts_id`를 `tr_key`로 KIS 업스트림에 `H0STCNI0`(국내)/`H0GSCNI0`(해외) 등록.
- `hts_id`가 비어 있거나 플래그가 꺼져 있으면 구독은 무시되고 notice를 반환한다(에러 아님).
- 종목 단위 시세(`type` 미지정/호가·체결가)와 달리 `tr_key`를 클라이언트가 보내지 않는다(서버가 사용자 `hts_id`로 채움).

> **HTS ID 컬럼:** 사용자별 HTS ID는 `user_kis_accounts.hts_id`(v1.11에서 추가)에 저장한다. 비어 있으면 체결통보 구독이 불가하다.

> **검증 한계(MUST-VERIFY):** 실제 체결통보 수신은 **HTS ID 설정 + 정규장 시간 + 실제 체결 발생**이 모두 충족돼야 확인 가능하며, 아직 라이브 검증이 끝나지 않았다.

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

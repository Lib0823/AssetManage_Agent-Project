# 장내채권 보유·매도 기능 설계

- 날짜: 2026-08-28
- 상태: 승인됨 (사용자 확인 완료). **2026-08-28 사전 검토 결과로 범위 축소 — 아래 §개정 이력 참조**
- 사전 검토 보고서: `_workspace/preflight_bond.md`, API 실측 계약: `_workspace/bond_api_contract.md`
- 관련 스펙: [`2026-08-28-upbit-coin-trading-design.md`](2026-08-28-upbit-coin-trading-design.md) — 독립 서브프로젝트

## 개정 이력

**초판(범위: 검색→시세→매수→매도 전체)은 사전 검토에서 성립 불가로 판정됐다.**

KIS 채권 API 18개를 전수 확인한 결과 **종목명·키워드로 채권을 찾는 API가 하나도 없다.** `search_bond_info`는 이름과 달리 검색이 아니라 12자리 종목코드(`PDNO`)를 입력으로 받는 **기본조회**다. 검색이 없으면 사용자가 채권 상세 화면에 도달할 경로 자체가 없고, 따라서 "검색 → 매수" 흐름은 만들 수 없다.

사용자 결정에 따라 **"보유 채권 조회 + 보유분 매도"로 범위를 축소**한다. 진입점이 잔고이므로 검색이 필요 없다. 매수는 후속 과제로 남긴다(`bond_master` 테이블을 만들어 로컬 검색을 붙이면 가능하나, 종목 목록 적재 경로 확보가 선행돼야 한다).

## 배경

`AssetTabs.vue` / `InvestmentTabs.vue`의 채권 탭은 `disabled: true`로 막혀 있고, 백엔드에 채권 코드가 전혀 없다. 이 스펙은 그 탭을 **보유 채권을 확인하고 팔 수 있는** 기능으로 만든다.

## 조사 결과 (KIS Open API — 실측)

이번 범위에서 쓰는 API:

| 기능 | TR_ID | 엔드포인트 |
|---|---|---|
| 잔고 조회(보유 채권) | `CTSC8407R` | `/uapi/domestic-bond/v1/trading/inquire-balance` |
| 기본 조회(종목 정보) | `CTPF1114R` | `/uapi/domestic-bond/v1/quotations/search-bond-info` |
| 발행 정보 | `CTPF1101R` | `/uapi/domestic-bond/v1/quotations/issue-info` |
| 현재가 | `FHKBJ773400C0` | `/uapi/domestic-bond/v1/quotations/inquire-price` |
| 호가 | `FHKBJ773401C0` | `/uapi/domestic-bond/v1/quotations/inquire-asking-price` |
| **매도 주문** | `TTTC0958U` | `/uapi/domestic-bond/v1/trading/sell` |
| **일별 체결조회(거래내역)** | `CTSC8013R` | `/uapi/domestic-bond/v1/trading/inquire-daily-ccld` |

범위 밖(존재하지만 쓰지 않음): 매수(`TTTC0952U`), 매수가능조회(`TTTC8910R`), 정정·취소(`order_rvsecncl`), 평균단가, 실시간 WebSocket, 일별 차트.

**응답 구조**: 위 API 전부 단일 `output`(output1/output2 분리 없음).
**케이싱 주의**: 조회계는 lowercase snake_case인데 **주문계(`sell`)만 UPPERCASE**다(`ODNO`, `ORD_TMD`, `KRX_FWDG_ORD_ORGNO`).

### 매도 주문의 구조적 특징 (초판이 놓친 것)

매도는 매수와 파라미터가 다르며, **종목이 아니라 "매수 로트"를 지정해서 판다.**

| 파라미터 | 설명 |
|---|---|
| `CANO`, `ACNT_PRDT_CD`, `PDNO`, `ORD_QTY2`, `BOND_ORD_UNPR` | 계좌·종목·수량·단가 |
| `SAMT_MKET_PTCI_YN`, `BOND_RTL_MKET_YN` | 소액시장참여·소매시장 여부 |
| **`ORD_DVSN`** | 주문구분 (필수) |
| **`SPRX_YN`** | 분리과세여부 (필수) — **임의로 `N` 고정 금지, 세금 처리가 달라진다** |
| **`SLL_AGCO_OPPS_SLL_YN`** | 매도대행사반대매도여부 (필수) |
| **`BUY_DT`**, **`BUY_SEQ`** | 매수일자·매수순번 — 어느 로트를 파는지 지정 |

잔고(`CTSC8407R`)가 `pdno` + `buy_dt` + `buy_sqno` 단위로 로트를 쪼개 돌려주므로, 같은 채권을 다른 날 샀으면 별개 행이다. **매도 화면은 잔고에서 로트를 골라 진입하는 흐름**이어야 한다.

`SPRX_YN`은 잔고의 `sprx_qty`(분리과세수량)/`agrx_qty`(종합과세수량) 또는 `issue_info.sprx_psbl_yn`에서 유도한다.

> **주의**: 요청 파라미터는 `BUY_SEQ`인데 잔고 응답 필드는 `buy_sqno`다. 이름이 달라 그대로 매핑하면 조용히 빈 값이 나간다.

### 잔고 조회의 필수 파라미터·연속조회

`CANO`, `ACNT_PRDT_CD` 외에 `INQR_CNDT`(필수, `"00"` 전체 / `"01"` 상품번호단위), `PDNO`, `BUY_DT`(공백 허용), `CTX_AREA_FK200`, `CTX_AREA_NK200`을 요구한다.

응답 헤더 `tr_cont == "M"`이면 `ctx_area_fk200`/`ctx_area_nk200`으로 다음 페이지를 받아야 한다. **처리하지 않으면 보유 채권이 많을 때 첫 페이지만 보이고 총자산이 조용히 과소 계산된다.**

> `KisApiClient`는 현재 `tr_cont` 요청/응답 헤더를 다루지 않는다 — 연속조회는 채권 서비스가 자체 처리하거나 `KisApiClient`를 확장해야 한다.

### 그 외 실측 사항

- **종목코드(`PDNO`)는 12자리 영숫자 혼합**(`KR2033022D33`, `KR6449111CB8`). 숫자 전용 검증 금지.
- **단가는 소수점을 갖고 문자열로 오간다**(`bond_ord_unpr="10000.0"`). 현재가 필드명은 `bond_prpr`.
- **신용등급은 단일 필드가 아니다** — 평가사별 4개(`kis_crdt_grad_text`, `kbp_crdt_grad_text`, `nice_crdt_grad_text`, `fnp_crdt_grad_text`).
- `search_bond_info`/`issue_info`가 `PRDT_TYPE_CD`를 필수로 요구한다(예제는 `"302"`). 채권 종류별로 값이 다를 수 있어 상수 하드코딩 시 특정 채권만 조회될 위험이 있다 — 실계좌 검증 시 확인.
- `search_bond_info`가 `iso_crcy_cd`(통화코드)를 준다. **외화표시채권이 섞이면 원화 합산이 틀어지므로 `KRW`가 아니면 합산에서 제외**한다.

### 미해소 항목 — 수량 단위 (사용자 확인: "나중에 확인, 일단 진행")

`ORD_QTY2`가 액면금액(원)인지 좌수인지 **예제만으로 판별되지 않는다.** 스펙 초판이 "액면 10,000원 기준"과 "액면 100원 기준 환산(`수량 × 단가 / 100`)"을 동시에 적어 100배 어긋나 있었다.

**대응**: 금액 환산 계수를 코드에 상수로 박지 않고 **설정값으로 분리**한다. 실계좌 검증 시 `inquire_psbl_order`를 알려진 단가로 1회 호출해 `buy_psbl_amt ÷ buy_psbl_qty` 비율로 판별한 뒤 확정한다(조회 전용이라 자금이 움직이지 않는다).

**그때까지의 리스크**: 매도 확인 다이얼로그의 예상 금액이 틀릴 수 있다. 화면에 "예상 금액은 참고용"임을 명시하고, 실제 체결 금액은 거래내역에서 확인하게 한다.

## 결정 사항 (사용자 확인 완료)

| 항목 | 결정 |
|---|---|
| 채권 범위 | **보유 채권 조회 + 보유분 매도** (검색·매수 제외) |
| 자산 화면 표시 | **매수금액 기준** — 잔고 API가 평가금액을 주지 않고, 종목별 시세 추가 조회는 rate limit을 잠식한다. 화면에 "매수금액 기준"을 명시 |
| 거래내역·체결 확인 | **KIS 직접 조회**(`CTSC8013R`) — 국내주식과 동일 패턴. **DB 테이블을 만들지 않는다** |
| AI 자동매매 파이프라인 | 제외 (ai-agent 무변경) |
| 실시간 시세(WebSocket) | 제외 (REST 폴링만) |

> **거래내역을 KIS 조회로 정한 결과 `bond_trade_history` 테이블이 불필요해졌다.** 초판의 DB 작업이 통째로 사라진다. 앱 안에서 "거래내역"의 의미가 자산별로 갈리지 않는다는 이점도 있다(주식=체결내역, 채권=주문요청내역이 될 뻔했다).

## 범위

### 1. Database

**변경 없음.** 신규 테이블·changeset 없음.

`user_settings.asset_order`에 이미 `bonds` 키가 있어 마이그레이션도 불필요하다(`utils/uiSettings.js`의 라벨만 수정).

### 2. api-server

- `client/KisBondClient.java` — 채권 TR_ID·경로 상수와 응답 파싱. **`KisApiClient`를 재사용**한다(공통 헤더·rate limit·실전 도메인).
- `service/BondQuoteService.java` — 기본조회/발행정보/현재가/호가. 조회 전용, graceful degrade + `notice`.
- `service/BondTradingService.java` — 잔고(연속조회 포함)/매도/거래내역. `OverseasTradingService`의 `requireKisAccountId` 널 가드 패턴을 따른다.
- `controller/BondController.java` — `/bonds/**`.
- DTO: `dto/bond/` 하위.
- `config/KisResilienceProperties.java` — **채권 시세 2개 TR(`FHKBJ773400C0`, `FHKBJ773401C0`)을 캐시 allowlist에 추가**. 잔고·주문·체결 TR은 **절대 추가하지 않는다**.
- `SecurityConfig` — 공개 조회 경로만 permitAll(개별 경로 지정, `/bonds/**` 전체 개방 금지).

**엔드포인트**

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/bonds/balance` | AUTH | 보유 채권 (`CTSC8407R`, 연속조회 처리) |
| GET | `/bonds/{bondCode}` | PUBLIC | 종목 기본정보 (`CTPF1114R`) |
| GET | `/bonds/{bondCode}/issue-info` | PUBLIC | 발행 정보 (`CTPF1101R`) |
| GET | `/bonds/{bondCode}/price` | PUBLIC | 현재가 (`FHKBJ773400C0`) |
| GET | `/bonds/{bondCode}/orderbook` | PUBLIC | 호가 (`FHKBJ773401C0`) |
| POST | `/bonds/sell` | AUTH | 매도 (`TTTC0958U`) |
| GET | `/bonds/history` | AUTH | 거래내역 (`CTSC8013R`) |

### 3. web-app

- `services/api.js`에 `bondApi` 추가.
- **`AssetsView`의 채권 카드**가 진입점이다. 보유 채권 목록을 보여주고, 항목을 누르면 상세로 간다.
- `views/detail/BondDetailView.vue` — 보유 로트 정보(매수일·수량·매수금액) + 시세·호가·발행정보.
- `views/detail/BondSellView.vue` — 매도 폼. **로트(`BUY_DT`/`BUY_SEQ`)를 상세 화면에서 넘겨받는다.**
- 라우트: `/bonds/:code`, `/bonds/:code/sell`. **검색 라우트 없음.**
- 탭 활성화: `AssetTabs.vue`·`InvestmentTabs.vue`의 `bonds` `disabled: false`.

**탭 활성화의 파급 (초판이 놓친 것)** — 두 탭 컴포넌트는 6개 화면이 공유한다. 채권 탭을 켜면 아래가 함께 처리돼야 한다:

| 화면 | 현재 상태 | 필요 조치 |
|---|---|---|
| `AssetDetailView` | `cash`/`stocks`만 분기 → 빈 화면 | 채권 분기 추가 |
| `NewsView` | `main !== 'stocks'`면 빈 목록 | "채권 뉴스 미지원" 안내 |
| `TransactionsView` | 분기 없음 → **주식 거래내역이 그대로 보임** | 채권 거래내역 분기 |
| `SearchView` | 분기 없음 → **주식 검색결과가 그대로 보임** | 채권 탭에서 "검색 미지원" 안내 |
| `FavoritesView` | 분기 없음 → **주식 관심종목이 그대로 보임** | 채권 탭에서 안내 |
| `AssetsView` | `.disabled` 카드 | 실제 카드로 교체 |

**아래 3개(`TransactionsView`, `SearchView`, `FavoritesView`)는 에러 없이 다른 자산의 데이터를 채권인 것처럼 보여준다** — 조용한 오류라 QA에서 놓치기 쉽다. 반드시 함께 처리한다.

### 4. 에러 처리

- **조회 경로**: graceful degrade — 200 + 빈 결과 + `notice`. `KisMaintenanceNotice` 재사용.
- **매도 경로**: degrade하지 않는다 — 실패 시 예외 전파 → 4xx/5xx. 국내주식 `/trading/sell`과 동일.
- `ErrorCode`: 필요 시 5004번대(5000~5003 사용 중) 추가.

### 5. 테스트

- `KisBondClientTest` — TR_ID·엔드포인트 계약 고정.
- `BondQuoteServiceTest` — graceful degrade, `BigDecimal` 단가 파싱.
- `BondTradingServiceTest` — 널 가드, 매도 파라미터 전송 형식(로트 식별 포함), 연속조회.

## 비범위 (Out of scope)

- **채권 검색·매수** (검색 API 부재. 매수는 진입점이 없어 함께 보류)
- 장외채권 (공개 API 없음)
- 정정·취소 주문, 평균단가, 일별 차트
- 실시간 WebSocket 시세
- AI 자동매매 파이프라인 연동
- 해외채권
- 채권 뉴스·관심종목

## 리스크

| 리스크 | 완화 |
|---|---|
| 수량 단위 미확정 → 예상 금액 오표시 | 환산 계수를 설정값으로 분리, 화면에 "참고용" 명시, 실계좌 검증 시 확정 |
| 채권 단가 소수점 손실 | DTO·계산 전 구간 `BigDecimal` + 왕복 테스트 |
| 잔고 연속조회 누락 → 총자산 과소 계산 | `tr_cont` 처리를 명시적 요구사항으로 두고 테스트 |
| 탭 활성화가 5개 화면을 조용히 깨뜨림 | 위 표의 6개 화면을 함께 수정 |
| 채권 시세가 캐시 대상이 아니라 주식 시세 rate limit을 잠식 | 시세 2개 TR을 캐시 allowlist에 추가(잔고·주문 TR 제외) |
| `SPRX_YN`을 임의값으로 넣으면 세금 처리가 틀어짐 | 잔고·발행정보에서 유도, 임의 고정 금지 |
| quote appKey에 채권 API 권한이 없을 수 있음 | 실계좌 조회 1회로 확인(미확인 항목) |
| 실전 매도라 실제 자금이 움직임 | 확인 다이얼로그 + 소액 검증 |

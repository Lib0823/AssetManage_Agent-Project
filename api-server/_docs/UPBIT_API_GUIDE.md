# Upbit Open API 연동 가이드

업비트 원화 마켓 코인 시세·자산·주문 연동. 코드 진입점은 `client/UpbitApiClient.java`,
`service/CoinQuoteService.java`(시세), `service/CoinTradingService.java`(자산·주문),
`service/UpbitAuthService.java`(자격증명)다.

> KIS 연동은 [`KIS_API_GUIDE.md`](KIS_API_GUIDE.md)를 참고. 두 연동은 인증 모델이 근본적으로 다르다
> — KIS는 OAuth 토큰을 발급받아 24h 캐시하지만, **업비트는 요청마다 JWT를 새로 서명**한다.
> 그래서 `UpbitAuthService`에는 토큰 캐시가 없고 하는 일이 "DB에서 꺼내 복호화"뿐이다.

---

## 1. 인증 — 요청마다 서명하는 JWT

### 페이로드

| 클레임 | 값 |
|--------|-----|
| `access_key` | 사용자 Access Key (평문) |
| `nonce` | 매 요청 새 UUID |
| `query_hash` | 쿼리스트링의 SHA512 hex (**파라미터가 있을 때만**) |
| `query_hash_alg` | `"SHA512"` (위와 세트) |

`Authorization: Bearer <jwt>` 헤더로 보낸다.

### query_hash 의 계약 — 여기가 가장 값비싼 함정

해싱 대상 문자열은 `k=v&k=v` 형식이며 **두 조건이 곧 계약**이다:

1. **URL 인코딩하지 않은 원문**
2. **정렬하지 않은 삽입 순서**

인코딩하거나 정렬하면 업비트가 계산한 해시와 어긋나 **401만 돌아오고 원인은 드러나지 않는다.**
구현은 `UpbitApiClient.toQueryString`이며, `UpbitJwtTest`가 이 규칙을 고정한다.

> **POST의 서명 대상은 바디가 아니라 "바디를 쿼리스트링으로 바꾼 문자열"이다.**
> 바디는 JSON으로 나가지만 해싱은 `market=KRW-BTC&side=bid&...` 형태로 한다. 이 비대칭이
> 이 클라이언트에서 가장 헷갈리는 지점이라 전용 테스트로 못박아 뒀다.

> **인증 GET에는 인코딩 비대칭이 잠복해 있다.** `query_hash`는 원문을 해싱하는데 URL은
> `UriComponentsBuilder.encode()`가 인코딩한다. 값에 인코딩 대상 문자가 들어가면 서명과 URL이
> 어긋난다. 현재 유일한 인증 GET(`/v1/accounts`)은 파라미터가 없어 발현하지 않으므로,
> `getAuthenticated`가 **변형되는 파라미터를 만나면 나가기 전에 IllegalArgumentException으로 끊는다**
> (`requireEncodingSafeParams`). 이 예외를 보면 값을 고칠 게 아니라 **서명과 URL이 같은 문자열을
> 쓰도록 구현을 고쳐야 한다.**

### 서명 알고리즘 — HS256, 그리고 미검증 리스크

현재 **HS256**으로 서명한다(`Jwts.SIG.HS256`). Secret Key는 **Base64 디코딩하지 않고** raw UTF-8
바이트를 그대로 쓴다 — 업비트 Secret Key는 Base64 문자열이 아니다.

> ⚠️ **업비트가 HS256을 수용하는지 실제 키로 확인되지 않았다.** 업비트 문서는 HS512를 권장하지만,
> 실제 키는 40자(320비트)라 jjwt가 HS512(512비트 요구)를 거부한다. HS256을 택한 것은 이 제약 때문이다.
>
> **업비트 키를 확보하면 가장 먼저 `GET /v1/accounts`를 1회 호출해 확인할 것.** 거부되면
> jjwt 대신 `javax.crypto.Mac("HmacSHA512")` + 수동 base64url 인코딩으로 재구현해야 한다.

### Secret Key 최소 길이

jjwt는 HS256에 최소 32바이트를 요구하며 미달 시 `WeakKeyException`(`RuntimeException`)을 던진다.
잡지 않으면 주문·자산 조회가 **500**이 되므로, 저장 시점(`UserService.updateUpbitAccount`)과
사용 시점(`UpbitAuthService.getCredentials`) 양쪽에서 검사해 **6006(400)**으로 끊는다.

- 저장 시점만 막으면: 이 검사가 생기기 전에 저장된 짧은 키가 그대로 남아 주문에서 터진다
- 사용 시점만 막으면: 사용자가 **주문을 넣어 볼 때까지** 키가 잘못됐다는 걸 모른다

실제 업비트 키는 40자라 오타·잘못된 붙여넣기에서만 걸린다.

---

## 2. 자격증명 저장

`user_upbit_accounts` 테이블(v1.29). `access_key`/`secret_key` 모두 **Jasypt(AES-256) 암호화 저장**.

| 규칙 | 내용 |
|------|------|
| **Secret Key 미노출** | 어떤 응답 DTO에도 필드 자체가 없다. 등록 여부 boolean(`secretKeyRegistered`)과 Access Key 마스킹만 노출 |
| **복호화 실패 = 6004** | 평문 폴백하지 않는다. 폴백은 "암호화가 적용되지 않았다"는 상태를 정상 동작으로 위장해, 평문 키가 업비트로 나가는 걸 감춘다 |
| **빈 값 = "그대로 두기"** | 삭제가 아니다. 조회 응답이 실제 키를 돌려주지 않으므로 프론트는 입력칸을 되채울 수 없다. 빈 값을 삭제로 해석하면 사용자가 Access Key만 고치려는 순간 Secret Key가 날아간다 |
| **최초 등록만 둘 다 필수** | 수정 시에는 채운 필드만 바뀐다 |

> KIS 쪽 `decryptForDisplay`(AppSecret 평문을 응답에 싣는다)는 실수가 아니라 **암호화 도입 이전의
> 평문 레코드 때문에 프로필 화면이 죽는 상황**을 피하려는 의도된 트레이드오프다. `user_upbit_accounts`는
> 신규 테이블이라 그 사정이 없어 같은 패턴을 물려받지 않았다. **"KIS처럼 맞추자"며 되돌리기 쉬운
> 종류의 결정**이라 `UpbitAccountSecurityTest`가 고정한다.

저장 시 `GET /v1/accounts`를 1회 호출해 키가 실제 동작하는지 확인하고 `is_verified`에 기록한다.
**검증 실패는 저장 실패가 아니다** — IP 화이트리스트 미등록처럼 키는 맞는데 환경이 안 맞는 경우가
흔하고, 그때 저장을 되돌리면 사용자가 IP를 등록한 뒤 키를 처음부터 다시 입력해야 한다.

> **알려진 한계**: 이 검증 호출이 `@Transactional` 안에서 일어난다(`UserService:434`). 클라이언트
> 타임아웃이 connect 5s + read 18s이므로 **최대 23초간 DB 커넥션을 점유**한다. 현재 실사용자가
> 소수라 즉시 문제는 아니지만, 유저 수가 늘면 업비트 점검 중에 커넥션 풀이 마를 수 있다.

---

## 3. 엔드포인트

모든 응답은 `ApiResponse<T>` 래퍼 안에 있다(`res.data`가 실제 페이로드).

| 메서드 | 경로 | 인증 | 응답 |
|--------|------|------|------|
| GET | `/coins/markets` | PUBLIC | `CoinMarketListResponse` |
| GET | `/coins/tickers?markets=A,B,C` | PUBLIC | `CoinTickerResponse[]` (**배치**) |
| GET | `/coins/{market}/orderbook` | PUBLIC | `CoinOrderbookResponse` |
| GET | `/coins/{market}/candles?unit=&count=` | PUBLIC | `CoinCandleListResponse` |
| GET | `/coins/accounts` | AUTH | `CoinAccountResponse[]` |
| POST | `/coins/buy` | AUTH | `CoinOrderResponse` |
| POST | `/coins/sell` | AUTH | `CoinOrderResponse` |
| GET | `/coins/history` | AUTH | `CoinTradeHistoryResponse[]` |
| GET·PUT | `/users/upbit-account` | AUTH | `UpbitAccountResponse` |

> **`/coins/**` 전체를 열지 않았다.** 공개 4경로만 개별 지정돼 있고 `{market}`은
> **`KRW-[A-Z0-9]{1,20}`**만 매칭한다 — `BTC-ETH` 같은 비원화 마켓은 403이다.
> 패턴을 느슨하게 고치면 `/coins/accounts`가 인증 없이 열릴 수 있다.

> **단건 ticker 엔드포인트는 없다.** 시세는 `/coins/tickers` 배치 조회만 제공한다. 종목별로
> 루프를 돌면 IP 10/s 한도를 즉시 소진해 **다른 사용자의 시세까지 막는다.** 자산 화면의
> 평가금액은 `balance × tradePrice`로 클라이언트가 계산한다(`CoinAccountResponse`에 평가금액 없음).

---

## 4. 주문 — 타입별로 파라미터가 비대칭이다

업비트는 주문 3종의 필수 파라미터가 서로 다르다. **잘못 매핑하면 실거래에서 돈이 잘못 움직인다.**

| 주문 | `ord_type` | `volume` | `price` |
|------|-----------|----------|---------|
| 지정가 (매수·매도) | `limit` | 수량 | 단가 |
| **시장가 매수** | `price` | ❌ **보내면 거부** | **총액(원)** |
| **시장가 매도** | `market` | 수량 | ❌ **보내면 거부** |

즉 **시장가는 매수가 "금액", 매도가 "수량"**이다. UI가 이를 정직하게 반영해야 한다.
매핑은 `CoinTradingService.buildOrderParams`, 고정 테스트는 `CoinOrderMappingTest`다.

### 정밀도

수량·가격은 전 구간 `BigDecimal` + `toPlainString()`으로 다룬다. `Double`을 거치거나
`toString()`을 쓰면 `0.00000123`이 `1.23E-6`으로 나가 업비트가 거부한다.
DB 컬럼도 `NUMERIC(30,8)` — 사토시 단위 정밀도가 곧 자산 금액이라 주식용 정밀도로는 조용히 잘린다.

### 멱등성

`identifier`(업비트 클라이언트 지정 식별자, 최대 64자)로 중복 주문을 막는다.
프론트가 `idempotencyKey`를 보내면 그대로 쓰고, 없으면 서버가 UUID를 만든다.

> **조회와 UNIQUE 제약은 반드시 `(user_id, identifier)` 단위다**(v1.30). v1.29는 `identifier` 단일
> 컬럼 UNIQUE + 전역 `findByIdentifier` 였는데, `idempotencyKey`는 **클라이언트가 값을 완전히
> 지정하는 문자열**이라 그 경계가 사용자를 넘었다. 결과가 두 가지였다:
>
> 1. 아무나 `"1"` 같은 값을 보내면 **그 값을 먼저 쓴 사람의 주문 내역**(주문번호·종목·수량·단가·
>    체결수량·수수료·주문시각)이 통째로 응답에 실렸다.
> 2. 그 요청은 "중복"으로 처리돼 **업비트로 나가지 않으면서 화면에는 성공으로 보였다** —
>    주문을 냈다고 믿었는데 존재하지 않는 상태.
>
> 멱등은 **같은 사용자의 재시도**를 막는 개념이지 사용자 사이에 적용될 것이 아니다.
> `CoinOrderMappingTest`의 "남이 쓴 멱등키는 내 주문을 가로막지 못한다"가 이 경계를 고정한다.

> **서버 생성 UUID는 재시도를 막지 못한다.** 매번 새 값이라 조회가 항상 비기 때문이다.
> 실제 방어는 **프론트가 재시도 때 같은 키를 다시 보낼 때만** 작동한다 — `CoinTradingView`는
> 확인 시점에 키를 만들어 두고, 주문 내용이 그대로면 재시도에 같은 키를 싣는다(매수/매도·주문방식·
> 입력값이 바뀌면 다른 주문이므로 폐기).

### `submittedState` 는 체결 상태가 아니다

`POST /v1/orders` 응답의 `state`는 접수 직후 값(대개 `wait`)이다. 주문 조회 API가 이 기능의
범위 밖이라 **이 값은 영원히 갱신되지 않는다.** 컬럼명을 `order_state`가 아니라 `submitted_state`로
둔 것도 그래서다 — "체결됨"으로 표시하면 사용자가 중복 주문을 낸다.

---

## 5. Rate Limit

`KisRateLimiter`(Redis 토큰버킷)를 재사용하되 **버킷 키를 나눈다.**

| 경로 | 업비트 한도 | 적용 단위 | 우리 설정 |
|------|-----------|----------|----------|
| 시세 | 10 req/s | **IP** | capacity 10, refill 8/s |
| 주문 | 12 req/s | Pocket(계정) | capacity 12, refill 10/s |

**시세가 IP 단위라는 점이 이 연동에서 가장 먼저 막히는 곳이다** — 전체 사용자가 서버 공인 IP 하나의
한도를 공유한다. 사용자별로 나누면 실제 한도를 넘긴다. 주문은 계정 단위라 사용자끼리 간섭하지 않아
access_key 해시로 버킷을 만든다.

한도에 딱 맞추지 않고 8/s·10/s로 두는 이유: **429가 누적되면 업비트가 418(일시 차단)로 올린다.**

자체 버킷에 걸려 업비트를 호출조차 하지 않고 거부한 경우는 **6003**이다.

---

## 6. 에러 코드

| 코드 | HTTP | 의미 |
|------|------|------|
| 6000 | 404 | 업비트 계좌 미등록 |
| 6001 | 503 | 업비트 API 오류 (일반) |
| 6002 | 403 | **서버 IP가 API 키의 허용 IP에 없음** |
| 6003 | 429 | 자체 토큰버킷에서 거부 (업비트 미호출) |
| 6004 | 500 | 저장된 자격증명 복호화 실패 → 재등록 필요 |
| 6005 | 400 | 주문 타입과 파라미터 조합이 업비트 규칙 위반 |
| 6006 | 400 | Secret Key가 서명 최소 길이(32바이트) 미달 |

**6002를 일반 401로 뭉개지 않은 이유**: 사용자가 취할 행동이 완전히 다르다. 키가 틀렸으면 재발급이지만,
IP 문제면 업비트에서 **허용 IP를 다시 등록**해야 한다. 서버 IP가 바뀌면 전 요청이 실패하므로 원인이
즉시 드러나야 한다.

---

## 7. 설정

```yaml
upbit:
  base-url: ${UPBIT_BASE_URL:https://api.upbit.com}
  quote:
    capacity: ${UPBIT_QUOTE_RATE_CAPACITY:10}
    refill-per-second: ${UPBIT_QUOTE_RATE_REFILL:8}
  order:
    capacity: ${UPBIT_ORDER_RATE_CAPACITY:12}
    refill-per-second: ${UPBIT_ORDER_RATE_REFILL:10}
```

API 키는 설정 파일이 아니라 **사용자별로 DB에 암호화 저장**된다(전역 키 없음).

**운영 전 필수**: 업비트 API 키에 **서버 공인 IP를 허용 IP로 등록**해야 한다. 미등록이면
모든 인증 호출이 6002로 실패한다.

---

## 8. 검증 상태

| 항목 | 상태 |
|------|------|
| 공개 시세 4경로 (markets/tickers/orderbook/candles) | ✅ 실서버에서 실데이터 수신 확인 |
| 인증 경로 보호 (`/coins/accounts`·`/coins/buy` → 403) | ✅ 실측 |
| Liquibase v1.29 적용 | ✅ 실 DB 확인 |
| 주문 타입 3종 매핑 | ✅ 단위 테스트 (실호출 아님) |
| **HS256 수용 여부** | ❌ **미검증 — 최대 리스크** (§1 참고) |
| `GET /v1/accounts` 실응답 필드명 | ❌ 미검증 (문서 기준 매핑) |
| 실주문 체결 | ❌ 미검증 (실자금 이동 — 최소금액 5,000원 소액 주문으로 확인 필요) |
| IP 화이트리스트 위반 시 응답 문자열 | ❌ 미검증 (다르면 6002 대신 6001로 뭉개짐) |

### 알려진 버그 이력

**다중 마켓 조회 404 (수정됨)** — `buildUrl`이 값을 `URLEncoder.encode()`로 미리 인코딩한 뒤
`UriComponentsBuilder.build(true)`에 넘겨 `%`가 다시 인코딩됐다. 콤마가 `%252C`가 되어 업비트가
마켓 코드 하나로 읽고 **404 Code not found**를 반환했다.

증상이 고약했던 이유: **단일 마켓 조회(호가·캔들)는 멀쩡히 동작했다.** 콤마가 없으면 이중 인코딩할
것도 없어서다. 그래서 **자산 화면의 평가금액 계산(배치 티커)만 조용히 비어 있었다.**
`UpbitUrlEncodingTest`가 회귀를 막는다.

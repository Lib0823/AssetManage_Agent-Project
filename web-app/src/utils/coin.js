/**
 * 업비트 코인 화면 공용 유틸 (표시 포맷 + 입력 검증 + 유의종목 라벨).
 *
 * 코인이 주식·채권과 다른 세 가지 때문에 별도 모듈로 뺐다.
 *
 * 1. **자릿수 편차가 극단적이다.** 같은 화면에 BTC(1억원대)와 알트코인(0.5원)이 함께 뜬다.
 *    `Intl.NumberFormat('ko-KR')` 기본값은 소수 3자리 초과를 잘라내므로 0.00001234 짜리
 *    코인이 전부 `0`으로 보인다. 값 크기에 따라 소수 자릿수를 바꾸는 포맷터를 여기 둔다.
 * 2. **수량이 소수 8자리다**(사토시 단위). 입력을 `Number` 로 왕복시키면 `1.23e-6` 같은
 *    지수표기로 변질돼 서버가 거부하거나 다른 수량으로 주문된다. 검증만 하고 **문자열
 *    그대로** 전송하기 위해 정규식 검증기를 함께 둔다.
 * 3. **유의/주의 종목 플래그가 있다.** 업비트가 원문 키(`PRICE_FLUCTUATIONS` 등)로 주므로
 *    한글 라벨 매핑이 필요하다. 실제 자금이 오가는 화면이라 이 정보를 버리지 않는다.
 */

/** 값 없음 표시 (— 는 "0"과 구분하기 위한 것) */
const EMPTY = '—'

/** 업비트 원화마켓 최소 주문금액. 미만이면 업비트가 거부한다. */
export const MIN_KRW_ORDER_AMOUNT = 5000

/** 코인 수량 소수 상한 (업비트는 8자리까지 받는다) */
export const MAX_QUANTITY_DECIMALS = 8

const toFiniteNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

const format = (n, max, min = 0) =>
  new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: min,
    maximumFractionDigits: max
  }).format(n)

/**
 * 코인 가격 포맷. 값의 크기에 따라 소수 자릿수를 바꾼다.
 *
 * 고정 자릿수를 쓰면 한쪽이 반드시 깨진다 — 0자리면 알트코인이 전부 `0`이 되고,
 * 8자리면 BTC 가 `107,969,000.00000000` 이 된다.
 */
export const formatCoinPrice = (value, fallback = EMPTY) => {
  const n = toFiniteNumber(value)
  if (n === null) return fallback
  const abs = Math.abs(n)
  if (abs >= 1000) return format(n, 0)
  if (abs >= 100) return format(n, 1)
  if (abs >= 1) return format(n, 2)
  if (abs >= 0.01) return format(n, 4)
  return format(n, MAX_QUANTITY_DECIMALS)
}

/** 코인 수량. 소수 8자리까지 보존한다(잘라내면 잔고가 0으로 보인다). */
export const formatCoinQuantity = (value, fallback = EMPTY) => {
  const n = toFiniteNumber(value)
  return n === null ? fallback : format(n, MAX_QUANTITY_DECIMALS)
}

/** 원화 금액(평가금액·주문총액). 원 단위 정수로 반올림해 표시한다. */
export const formatKrw = (value, fallback = EMPTY) => {
  const n = toFiniteNumber(value)
  return n === null ? fallback : format(Math.round(n), 0)
}

/**
 * 등락률 표시. 업비트 `signedChangeRate` 는 비율(0.0176 = +1.76%)이다.
 * 이미 백분율인 값을 넣으면 100배 틀리므로 호출부를 확인할 것.
 */
export const formatSignedRate = (rate, fallback = EMPTY) => {
  const n = toFiniteNumber(rate)
  if (n === null) return fallback
  const sign = n > 0 ? '+' : ''
  return `${sign}${(n * 100).toFixed(2)}%`
}

/** 부호를 붙인 가격 변동폭. */
export const formatSignedPrice = (value, fallback = EMPTY) => {
  const n = toFiniteNumber(value)
  if (n === null) return fallback
  return `${n > 0 ? '+' : ''}${formatCoinPrice(n)}`
}

/**
 * 업비트 `change` 값(`RISE`/`FALL`/`EVEN`)을 CSS 클래스로.
 * 값이 없으면 부호로 판단한다(캔들 응답에는 `change` 가 없다).
 */
export const changeClass = (change, signedValue) => {
  if (change === 'RISE') return 'positive'
  if (change === 'FALL') return 'negative'
  if (change === 'EVEN') return 'flat'
  const n = toFiniteNumber(signedValue)
  if (n === null || n === 0) return 'flat'
  return n > 0 ? 'positive' : 'negative'
}

/** `KRW-BTC` → `BTC`. 마켓 코드는 6자리 종목코드가 아니라 `통화-심볼` 형식이다. */
export const symbolOf = (market) => {
  const s = String(market ?? '')
  const idx = s.indexOf('-')
  return idx >= 0 ? s.slice(idx + 1) : s
}

/** `BTC` → `KRW-BTC`. 보유 목록(currency 만 있음)에서 마켓 코드를 만들 때 쓴다. */
export const toKrwMarket = (currency) => `KRW-${String(currency ?? '').toUpperCase()}`

/** 원화 잔고 행(마켓이 없는 행)인지. */
export const isKrwRow = (account) => String(account?.currency ?? '').toUpperCase() === 'KRW'

/**
 * 소수 8자리까지 허용하는 양수 문자열.
 * `type="number"` 는 브라우저마다 소수 처리가 달라 쓰지 않고, 이 패턴으로 직접 검증한다.
 */
export const QUANTITY_PATTERN = /^\d+(\.\d{1,8})?$/

/**
 * 코인 **가격**(지정가 단가). 수량과 같은 소수 8자리를 허용한다.
 *
 * **금액용 `AMOUNT_PATTERN`(2자리)을 여기 쓰면 안 된다.** 업비트 원화마켓의 호가 단위는
 * 1원 미만 구간에서 0.001~0.00000001 이라 소수 3~8자리가 정상 값이다. 2자리로 검증하면
 * SHIB(0.00713)·PEPE(0.00506)·BTT(0.000404) 같은 **1원 미만 코인 15종의 지정가 주문이
 * 통째로 막힌다** — 게다가 현재가를 시드로 채워 넣은 입력칸에 값이 보이는 상태라
 * "단가를 입력해 주세요"라는 안내가 사용자를 오도한다.
 */
export const PRICE_PATTERN = /^\d+(\.\d{1,8})?$/

/**
 * **원화 금액**(시장가 매수 총액). 원 단위 이하는 실무상 무의미해 2자리까지만 허용한다.
 * 가격이 아니라 "쓸 돈"이라 자릿수 규칙이 `PRICE_PATTERN` 과 다르다.
 */
export const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/

/** 수량 입력이 유효한 양수인지 (0 은 거부). */
export const isValidQuantity = (raw) => {
  const s = String(raw ?? '').trim()
  return QUANTITY_PATTERN.test(s) && Number(s) > 0
}

/** 가격(단가) 입력이 유효한 양수인지 (0 은 거부). */
export const isValidPrice = (raw) => {
  const s = String(raw ?? '').trim()
  return PRICE_PATTERN.test(s) && Number(s) > 0
}

/** 원화 금액 입력이 유효한 양수인지 (0 은 거부). */
export const isValidAmount = (raw) => {
  const s = String(raw ?? '').trim()
  return AMOUNT_PATTERN.test(s) && Number(s) > 0
}

/**
 * "주문 시도" 단위 멱등키를 만든다.
 *
 * **재시도 때 같은 값을 다시 보내야 의미가 있다.** 매번 새로 만들면 서버의
 * `findByIdentifier` 가 항상 비어 중복 억제가 작동하지 않는다 — 응답이 유실된
 * (타임아웃) 주문을 사용자가 다시 누르면 **업비트에 두 번째 실주문**이 나간다.
 *
 * 서버 `CoinOrderRequest.idempotencyKey` 상한이 64자라 UUID(36자)로 충분하다.
 */
export const newOrderAttemptKey = () => {
  // randomUUID 는 보안 컨텍스트(https/localhost)에서만 있다. 그 밖에서도 주문은 되어야 하므로
  // 충돌 확률이 충분히 낮은 폴백을 둔다(멱등키는 사용자·시점 단위라 전역 유일할 필요가 없다).
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/**
 * 업비트 주의 종목 플래그 한글 라벨.
 * 원문 키는 `/v1/market/all?isDetails=true` 의 `market_event.caution` 그대로다.
 */
export const CAUTION_LABELS = {
  PRICE_FLUCTUATIONS: '가격 급등락',
  TRADING_VOLUME_SOARING: '거래량 급등',
  DEPOSIT_AMOUNT_SOARING: '입금량 급등',
  GLOBAL_PRICE_DIFFERENCES: '글로벌 가격 차이',
  CONCENTRATION_OF_SMALL_ACCOUNTS: '소수 계정 집중'
}

/** 알 수 없는 키가 와도 버리지 않고 원문을 보여준다(업비트가 플래그를 추가할 수 있다). */
export const cautionLabel = (key) => CAUTION_LABELS[key] || String(key ?? '')

/** 유의 또는 주의 플래그가 하나라도 있는지. */
export const hasRiskFlag = (market) =>
  !!(market?.warning || (Array.isArray(market?.cautions) && market.cautions.length > 0))

/**
 * 주문 접수 상태(`submittedState`) 한글 라벨.
 *
 * **이 값은 체결 상태가 아니다.** 업비트 주문 응답의 `state` 는 접수 직후 값이고
 * (대개 `wait`), 이 프로젝트에는 주문 조회 API 가 없어 **영원히 갱신되지 않는다.**
 * "체결됨"으로 표시하면 사용자가 체결된 줄 알고 중복 주문을 낸다.
 */
export const submittedStateLabel = (state) => {
  switch (String(state ?? '').toLowerCase()) {
    case 'wait':
      return '접수됨 (체결 대기)'
    case 'watch':
      return '접수됨 (예약 대기)'
    case 'done':
      return '접수됨 (전량 체결로 접수)'
    case 'cancel':
      return '취소됨'
    default:
      return state ? `접수됨 (${state})` : '접수됨'
  }
}

/**
 * 보유 자산 응답 + 티커 배치 응답 → 화면용 보유 목록.
 *
 * **업비트는 평가금액을 주지 않는다** — `/v1/accounts` 는 수량과 매수평균가만 준다. 그래서
 * `수량 × 현재가` 를 프런트가 계산하는데, 그 현재가는 반드시 **`/coins/tickers` 배치 1회**로
 * 받아야 한다. 보유 종목마다 시세를 부르면 업비트 IP 한도(10 req/s)를 즉시 소진해
 * **다른 모든 사용자의 시세까지 막힌다.** 이 함수가 `tickers` 를 인자로 받는 이유다 —
 * 안에서 조회하게 두면 언젠가 루프 안에서 호출된다.
 *
 * 원화(KRW) 마켓만 다루므로 환율 변환은 없다.
 *
 * @param {Array} accounts `/coins/accounts` 응답
 * @param {Array} tickers `/coins/tickers` 배치 응답
 * @returns {{ holdings: Array, krwBalance: number, totalEvaluation: number }}
 */
export const buildCoinHoldings = (accounts, tickers) => {
  const rows = Array.isArray(accounts) ? accounts : []
  const priceByMarket = new Map(
    (Array.isArray(tickers) ? tickers : [])
      .filter((t) => t?.market)
      .map((t) => [t.market, toFiniteNumber(t.tradePrice)])
  )

  const krwRow = rows.find(isKrwRow)
  // 원화 잔고는 주문가능(balance) + 주문중(locked) 을 합쳐야 실제 보유 현금이 된다.
  const krwBalance = krwRow
    ? (toFiniteNumber(krwRow.balance) ?? 0) + (toFiniteNumber(krwRow.locked) ?? 0)
    : 0

  const holdings = []
  let totalEvaluation = 0

  for (const row of rows) {
    if (isKrwRow(row)) continue
    // 주문 걸린 수량(locked)도 여전히 보유 자산이다. 빼면 평가금액이 실제보다 작아진다.
    const quantity = (toFiniteNumber(row.balance) ?? 0) + (toFiniteNumber(row.locked) ?? 0)
    if (quantity <= 0) continue

    const market = row.market || toKrwMarket(row.currency)
    const currentPrice = priceByMarket.get(market) ?? null
    const avgBuyPrice = toFiniteNumber(row.avgBuyPrice)
    // 시세를 못 받은 종목은 0원으로 채우지 않는다 — "다 팔렸다"로 오인된다.
    const evaluation = currentPrice === null ? null : quantity * currentPrice
    const buyAmount = avgBuyPrice === null ? null : quantity * avgBuyPrice

    if (evaluation !== null) totalEvaluation += evaluation

    holdings.push({
      market,
      currency: row.currency,
      symbol: symbolOf(market),
      quantity,
      avgBuyPrice,
      currentPrice,
      buyAmount,
      evaluation,
      profit: evaluation !== null && buyAmount !== null ? evaluation - buyAmount : null,
      profitRate:
        evaluation !== null && buyAmount !== null && buyAmount > 0
          ? (evaluation - buyAmount) / buyAmount
          : null
    })
  }

  return { holdings, krwBalance, totalEvaluation }
}

/** 보유 목록에서 시세를 물어볼 마켓 코드들 (원화 행 제외). 배치 조회 인자로 쓴다. */
export const marketsToQuote = (accounts) =>
  (Array.isArray(accounts) ? accounts : [])
    .filter((a) => !isKrwRow(a))
    .map((a) => a.market || toKrwMarket(a.currency))
    .filter(Boolean)

/**
 * 서버 `ErrorCode` 코인 대역(6000~). `ApiResponse.code` 로 내려온다.
 * HTTP 상태만 보면 구분이 안 된다 — 미등록도 404, 없는 리소스도 404다.
 */
export const COIN_ERROR = {
  ACCOUNT_NOT_FOUND: 6000,
  API_ERROR: 6001,
  IP_NOT_ALLOWED: 6002,
  RATE_LIMITED: 6003,
  CREDENTIAL_DECRYPT_FAILED: 6004,
  INVALID_ORDER: 6005
}

/** 에러 응답에서 서버 에러코드를 꺼낸다(없으면 null). */
export const coinErrorCode = (error) => {
  const code = error?.response?.data?.code
  return typeof code === 'number' ? code : null
}

/** 업비트 계좌 미등록으로 실패한 요청인지. 이 경우는 장애가 아니라 안내 대상이다. */
export const isUpbitAccountMissing = (error) =>
  coinErrorCode(error) === COIN_ERROR.ACCOUNT_NOT_FOUND

/**
 * 사용자에게 보여줄 에러 메시지. 서버 메시지를 우선하되,
 * IP 미등록처럼 조치 방법이 정해진 건은 그 안내로 대체한다.
 */
export const coinErrorMessage = (error, fallback = '요청을 처리하지 못했습니다.') => {
  switch (coinErrorCode(error)) {
    case COIN_ERROR.ACCOUNT_NOT_FOUND:
      return '업비트 계좌가 등록되지 않았습니다. 내 정보에서 API 키를 등록해 주세요.'
    case COIN_ERROR.IP_NOT_ALLOWED:
      return '업비트 API 키에 이 서버의 IP가 등록되어 있지 않습니다. 업비트에서 허용 IP를 확인해 주세요.'
    case COIN_ERROR.RATE_LIMITED:
      return '업비트 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.'
    case COIN_ERROR.CREDENTIAL_DECRYPT_FAILED:
      return '저장된 업비트 키를 복호화하지 못했습니다. 내 정보에서 키를 다시 등록해 주세요.'
    default:
      return error?.response?.data?.message || fallback
  }
}

/** 체결 확인 경로 안내. 여러 화면이 같은 문구를 써야 오해가 없다. */
export const SUBMITTED_STATE_NOTE =
  '표시되는 상태는 업비트가 주문을 받아들인 시점의 "접수 상태"입니다. 체결 여부는 갱신되지 않으므로 업비트 앱·웹에서 확인해 주세요.'

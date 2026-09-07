/**
 * 장내채권 화면 공용 유틸 (표시 포맷 + 매수 로트 라우팅).
 *
 * 채권이 주식과 다른 두 가지 때문에 별도 모듈로 뺐다.
 *
 * 1. **단가에 소수점이 있다.** 다른 화면이 쓰는 `Intl.NumberFormat('ko-KR')` 기본값은
 *    소수 3자리 초과를 잘라내고, `toLocaleString()`도 마찬가지다. 채권 단가(9850.5, 0.0001)를
 *    그대로 통과시키는 포맷 함수를 여기 둔다 — 화면마다 제각각 포맷하면 표시가가 주문가와
 *    어긋난다.
 * 2. **매도 대상이 "종목"이 아니라 "매수 로트"다.** 잔고에서 받은 `buyDate`/`buySeq`/
 *    `separateTaxation`을 자산 → 상세 → 매도 화면까지 그대로 운반해야 하며, 하나라도 빠지면
 *    서버가 400을 준다(사용자가 입력할 수 있는 값이 아니다). 그 운반 규약을
 *    `buildBondLotQuery`/`readBondLotQuery` 한 쌍으로 고정한다.
 */

/** 값 없음 표시 (— 는 "0"과 구분하기 위한 것) */
const EMPTY = '—'

const toFiniteNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/**
 * 소수점을 유지하는 숫자 포맷. 채권 값에는 반드시 이 함수를 쓴다.
 * @param {*} value 숫자 또는 숫자 문자열
 * @param {{ min?: number, max?: number, fallback?: string }} options 소수 자릿수
 */
export const formatBondNumber = (value, { min = 0, max = 4, fallback = EMPTY } = {}) => {
  const n = toFiniteNumber(value)
  if (n === null) return fallback
  return new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: min,
    maximumFractionDigits: max
  }).format(n)
}

/** 단가(호가/체결가). 소수 4자리까지 보존한다. */
export const formatUnitPrice = (value) => formatBondNumber(value, { max: 4 })

/** 금액(매수금액·예상금액). 소수 2자리까지. */
export const formatAmount = (value) => formatBondNumber(value, { max: 2 })

/** 수량. 단위가 미확정이라 소수를 버리지 않는다. */
export const formatQuantity = (value) => formatBondNumber(value, { max: 4 })

/** 이율(%) 표시. */
export const formatRate = (value) => {
  const n = toFiniteNumber(value)
  return n === null ? EMPTY : `${formatBondNumber(n, { max: 3 })}%`
}

/** KIS 일자(yyyyMMdd) → 'yyyy.MM.dd'. 형식이 다르면 원문 그대로. */
export const formatKisDate = (value) => {
  const s = String(value ?? '')
  if (!/^\d{8}$/.test(s)) return s || EMPTY
  return `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6, 8)}`
}

/** 빈 문자열/null 을 '—' 로 접는다 (신용등급처럼 평가사가 매기지 않은 값이 빈 문자열로 온다). */
export const textOrDash = (value) => {
  const s = typeof value === 'string' ? value.trim() : value
  return s === null || s === undefined || s === '' ? EMPTY : String(s)
}

/**
 * 예상 금액 = 수량 × 단가 ÷ faceValueDivisor.
 *
 * `faceValueDivisor`는 서버 설정(`kis.bond.face-value-divisor`)을 잔고 응답으로 받은 값이다.
 * **화면이 상수를 박으면 서버와 100배 다른 금액을 보여주게 되므로** 값이 없으면 계산하지 않고
 * null 을 돌려준다. 수량 단위(액면금액/좌수)가 아직 미확정이라 이 값은 참고용이다.
 *
 * @returns {number|null}
 */
export const calcExpectedAmount = (quantity, unitPrice, faceValueDivisor) => {
  const qty = toFiniteNumber(quantity)
  const price = toFiniteNumber(unitPrice)
  const divisor = toFiniteNumber(faceValueDivisor)
  if (qty === null || price === null || divisor === null || divisor === 0) return null
  return (qty * price) / divisor
}

/** 매수 로트를 식별하는 키 (같은 채권을 다른 날 사면 별개 행이다). */
export const bondLotKey = (lot) =>
  `${lot?.bondCode ?? ''}-${lot?.buyDate ?? ''}-${lot?.buySeq ?? ''}`

/**
 * 매수 로트를 라우트 쿼리로 직렬화한다.
 * 상세·매도 화면이 잔고를 다시 부르지 못하는 상황(직접 URL 진입, KIS 일시 실패)에서도
 * 최소한 로트를 특정할 수 있게 `buyDate`/`buySeq`는 항상 싣는다.
 */
export const buildBondLotQuery = (lot) => {
  if (!lot) return {}
  const query = {}
  const put = (key, value) => {
    if (value !== null && value !== undefined && value !== '') query[key] = String(value)
  }
  put('bondName', lot.bondName)
  put('buyDate', lot.buyDate)
  put('buySeq', lot.buySeq)
  put('quantity', lot.quantity)
  put('orderableQuantity', lot.orderableQuantity)
  put('buyUnitPrice', lot.buyUnitPrice)
  put('buyAmount', lot.buyAmount)
  put('maturityDate', lot.maturityDate)
  // Boolean 은 false 도 유효한 값이므로 위 put(빈값 제거)을 쓰지 않는다.
  if (lot.separateTaxation === true || lot.separateTaxation === false) {
    query.separateTaxation = String(lot.separateTaxation)
  }
  return query
}

/** `buildBondLotQuery` 의 역변환. 없는 값은 null 로 남긴다(0 과 구분). */
export const readBondLotQuery = (query = {}) => {
  const sprx = query.separateTaxation
  return {
    bondName: query.bondName ?? null,
    buyDate: query.buyDate ?? null,
    buySeq: query.buySeq ?? null,
    quantity: toFiniteNumber(query.quantity),
    orderableQuantity: toFiniteNumber(query.orderableQuantity),
    buyUnitPrice: toFiniteNumber(query.buyUnitPrice),
    buyAmount: toFiniteNumber(query.buyAmount),
    maturityDate: query.maturityDate ?? null,
    separateTaxation: sprx === 'true' ? true : sprx === 'false' ? false : null
  }
}

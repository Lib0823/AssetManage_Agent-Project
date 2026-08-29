<script setup>
/**
 * 코인 매매 화면 (`/coins/:market/trade?side=buy|sell`).
 *
 * **이 화면의 핵심은 주문 타입에 따라 입력 필드 자체가 바뀐다는 것이다.** 업비트 규칙:
 *
 * | 선택 | 입력 | 전송 필드 |
 * |---|---|---|
 * | 지정가 매수/매도 | 수량 + 단가 | `quantity` + `price` |
 * | **시장가 매수** | **총액만** | `price` (금액) |
 * | **시장가 매도** | **수량만** | `quantity` |
 *
 * 시장가 매수에 수량을 받을 수 없는 것은 화면의 제약이 아니라 업비트의 주문 규격이다.
 * 사용자가 "왜 수량을 못 넣지?"라고 헤매지 않도록 라벨과 안내로 그 이유를 밝힌다.
 *
 * **수량·금액은 사용자가 친 문자열 그대로 전송한다.** `Number` 로 왕복시키면 소수 8자리 수량이
 * `1.23e-6` 같은 지수표기로 변질돼 다른 수량으로 주문되거나 서버가 거부한다.
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { coinApi } from '@/services/api'
import { logger } from '@/utils/logger'
import { showError } from '@/utils/toast'
import {
  MIN_KRW_ORDER_AMOUNT,
  SUBMITTED_STATE_NOTE,
  cautionLabel,
  coinErrorMessage,
  formatCoinPrice,
  formatCoinQuantity,
  formatKrw,
  isKrwRow,
  isUpbitAccountMissing,
  isValidAmount,
  isValidPrice,
  isValidQuantity,
  newOrderAttemptKey,
  submittedStateLabel,
  symbolOf,
  toKrwMarket
} from '@/utils/coin'

const route = useRoute()
const router = useRouter()

const market = computed(() => String(route.params.market || ''))
const symbol = computed(() => symbolOf(market.value))

const side = ref(route.query.side === 'sell' ? 'sell' : 'buy')
const orderType = ref('LIMIT')

const form = ref({
  quantity: '',
  price: '',
  amount: ''
})

const marketInfo = ref(null)
const ticker = ref(null)

// 업비트 계좌 상태. accountMissing 이면 주문 폼 대신 등록 안내를 보여준다.
const accountMissing = ref(false)
const accountNotice = ref('')
const krwBalance = ref(null)
const coinBalance = ref(null)

const loading = ref(true)
const submitting = ref(false)
const errorMessage = ref('')
const showConfirm = ref(false)
const orderResult = ref(null)

/**
 * 현재 "주문 시도"의 멱등키.
 *
 * **재시도에서 같은 값을 다시 보내는 것이 이 값의 존재 이유다.** 주문이 업비트에 접수됐는데
 * 응답만 유실되는 경우(read timeout)가 실제로 있고, 그때 사용자는 실패한 줄 알고 다시 누른다.
 * 키가 같으면 서버가 `findByIdentifier` 로 걸러 두 번째 실주문을 막는다.
 *
 * 그래서 **성공했을 때와 주문 내용이 바뀌었을 때만** 새로 만든다. 실패는 키를 버리는 사유가
 * 아니다 — 실패야말로 같은 키로 다시 보내야 하는 상황이다.
 */
const orderAttemptKey = ref(null)

// ── 입력 필드 구성 (이 화면의 핵심 분기) ──────────────────────────────────
const isBuy = computed(() => side.value === 'buy')
const isLimit = computed(() => orderType.value === 'LIMIT')

/** 지정가는 항상 수량을 받는다. 시장가는 **매도일 때만** 수량을 받는다. */
const needsQuantity = computed(() => isLimit.value || !isBuy.value)
/** 단가 입력은 지정가 전용. 시장가는 체결가를 시장이 정한다. */
const needsUnitPrice = computed(() => isLimit.value)
/** 총액 입력은 **시장가 매수 전용**이다(업비트가 수량이 아니라 금액을 받는다). */
const needsAmount = computed(() => !isLimit.value && isBuy.value)

const quantityLabel = computed(() => (isBuy.value ? '매수 수량' : '매도 수량'))

/** 지금 입력 구성이 왜 이런지 사용자에게 설명하는 문구. */
const fieldGuide = computed(() => {
  if (isLimit.value) {
    return '지정가 주문은 수량과 단가를 지정합니다. 지정한 단가에 도달해야 체결됩니다.'
  }
  if (isBuy.value) {
    return '시장가 매수는 업비트 규격상 수량이 아니라 주문 총액(원)을 지정합니다. 체결 수량은 주문 시점의 시세에 따라 정해집니다.'
  }
  return '시장가 매도는 수량만 지정합니다. 단가는 시장이 정하므로 입력할 수 없습니다.'
})

const loadMarketInfo = async () => {
  try {
    const res = await coinApi.getMarkets()
    const list = res?.data?.markets
    marketInfo.value = Array.isArray(list)
      ? list.find((m) => m.market === market.value) ?? null
      : null
  } catch (error) {
    logger.debug('코인 마켓 정보 조회 실패:', error)
  }
}

const loadTicker = async () => {
  try {
    const res = await coinApi.getTickers([market.value])
    const list = Array.isArray(res?.data) ? res.data : []
    ticker.value = list[0] ?? null
  } catch (error) {
    logger.debug('코인 현재가 조회 실패:', error)
  }
}

/**
 * 보유 자산 조회.
 *
 * 계좌 미등록(서버 코드 6000)은 장애가 아니라 안내 대상이다 — 시세는 그대로 보이고
 * 주문 폼만 등록 안내로 대체한다.
 */
const loadAccounts = async () => {
  accountMissing.value = false
  accountNotice.value = ''
  try {
    const res = await coinApi.getAccounts()
    const list = Array.isArray(res?.data) ? res.data : []
    const krw = list.find(isKrwRow)
    krwBalance.value = krw ? Number(krw.balance) : 0
    const held = list.find((a) => !isKrwRow(a) && toKrwMarket(a.currency) === market.value)
    coinBalance.value = held ? Number(held.balance) : 0
  } catch (error) {
    if (isUpbitAccountMissing(error)) {
      accountMissing.value = true
      return
    }
    logger.debug('코인 자산 조회 실패:', error)
    krwBalance.value = null
    coinBalance.value = null
    accountNotice.value = coinErrorMessage(error, '보유 자산을 불러오지 못했습니다.')
  }
}

onMounted(async () => {
  await Promise.allSettled([loadMarketInfo(), loadTicker(), loadAccounts()])
  // 지정가 단가의 시드로 현재가를 넣는다(사용자가 고칠 수 있다).
  if (!form.value.price && ticker.value?.tradePrice != null) {
    form.value.price = String(ticker.value.tradePrice)
  }
  loading.value = false
})

// 매수/매도나 주문 타입을 바꾸면 쓰이지 않는 입력이 남아 있을 수 있다.
// 남겨 두면 확인 다이얼로그가 이전 값을 보여줘 오해를 부르므로 비운다.
watch([side, orderType], () => {
  errorMessage.value = ''
  orderResult.value = null
  if (!needsQuantity.value) form.value.quantity = ''
  if (!needsUnitPrice.value) form.value.price = ''
  if (!needsAmount.value) form.value.amount = ''
  if (needsUnitPrice.value && !form.value.price && ticker.value?.tradePrice != null) {
    form.value.price = String(ticker.value.tradePrice)
  }
  // 매수/매도·주문방식이 바뀌면 다른 주문이다. 이전 시도의 멱등키를 물려주면
  // 서버가 새 주문을 "중복"으로 보고 삼킨다.
  orderAttemptKey.value = null
})

// 주문 내용을 고치면 다른 주문이므로 멱등키를 버린다.
// (반대로 **값을 그대로 둔 채 다시 누르는 것은 재시도**이므로 키를 유지해야 한다.)
watch(form, () => {
  orderAttemptKey.value = null
}, { deep: true })

// ── 검증 ──────────────────────────────────────────────────────────────────
const quantityRaw = computed(() => form.value.quantity.trim())
const priceRaw = computed(() => form.value.price.trim())
const amountRaw = computed(() => form.value.amount.trim())

/** 주문 총액(원). 시장가 매도는 현재가 기준 추정치라 null 이 될 수 있다. */
const estimatedTotal = computed(() => {
  if (needsAmount.value) {
    return isValidAmount(amountRaw.value) ? Number(amountRaw.value) : null
  }
  if (!isValidQuantity(quantityRaw.value)) return null
  if (isLimit.value) {
    // 단가는 금액이 아니라 가격이다 — 1원 미만 코인의 호가는 소수 8자리까지 간다.
    return isValidPrice(priceRaw.value) ? Number(quantityRaw.value) * Number(priceRaw.value) : null
  }
  const current = Number(ticker.value?.tradePrice)
  return Number.isFinite(current) ? Number(quantityRaw.value) * current : null
})

/** 시장가 매도 총액은 현재가 기준 추정치일 뿐이다(체결가는 다를 수 있다). */
const totalIsEstimate = computed(() => !isLimit.value)

const maxSellQuantity = computed(() =>
  !isBuy.value && coinBalance.value !== null ? coinBalance.value : null
)

/** 주문을 막는 이유 (없으면 null). 버튼 비활성 사유를 화면에 그대로 보여준다. */
const blockReason = computed(() => {
  if (accountMissing.value) return '업비트 계좌가 등록되지 않았습니다.'

  if (needsQuantity.value) {
    if (!isValidQuantity(quantityRaw.value)) {
      return '수량을 입력해 주세요 (소수점 8자리까지).'
    }
    if (maxSellQuantity.value !== null && Number(quantityRaw.value) > maxSellQuantity.value) {
      return `보유 수량(${formatCoinQuantity(maxSellQuantity.value)} ${symbol.value})을 초과했습니다.`
    }
  }

  // 단가는 가격이므로 소수 8자리를 허용한다. 금액용 검증기(2자리)를 쓰면
  // 1원 미만 코인(SHIB·PEPE·BTT 등)의 지정가 주문이 통째로 막힌다.
  if (needsUnitPrice.value && !isValidPrice(priceRaw.value)) {
    return '주문 단가를 입력해 주세요.'
  }

  if (needsAmount.value && !isValidAmount(amountRaw.value)) {
    return '주문 총액(원)을 입력해 주세요.'
  }

  // 최소 주문금액은 업비트 규칙이다. 미만이면 어차피 거부되므로 미리 막는다.
  // 시장가 매도의 총액은 추정치라 판단 근거로 쓰지 않는다.
  if (isBuy.value && estimatedTotal.value !== null && estimatedTotal.value < MIN_KRW_ORDER_AMOUNT) {
    return `업비트 최소 주문금액은 ${formatKrw(MIN_KRW_ORDER_AMOUNT)}원입니다.`
  }

  if (
    isBuy.value &&
    krwBalance.value !== null &&
    estimatedTotal.value !== null &&
    estimatedTotal.value > krwBalance.value
  ) {
    return `주문가능 원화(${formatKrw(krwBalance.value)}원)를 초과했습니다.`
  }

  return null
})

const canSubmit = computed(() => blockReason.value === null && !submitting.value)

const setMaxQuantity = () => {
  if (maxSellQuantity.value !== null) {
    form.value.quantity = String(maxSellQuantity.value)
  }
}

const setMaxAmount = () => {
  if (krwBalance.value !== null) {
    form.value.amount = String(Math.floor(krwBalance.value))
  }
}

const openConfirm = () => {
  errorMessage.value = ''
  if (!canSubmit.value) {
    showError(blockReason.value || '주문 정보를 확인해 주세요.')
    return
  }
  // 이 시점의 주문 내용에 멱등키를 하나 붙인다. 실패 후 값을 그대로 두고 다시 눌렀다면
  // 키가 살아 있으므로 **같은 키로 재전송**되고, 서버가 중복 접수를 막는다.
  if (!orderAttemptKey.value) {
    orderAttemptKey.value = newOrderAttemptKey()
  }
  showConfirm.value = true
}

const submitOrder = async () => {
  // 다이얼로그 표시와 별개의 최종 가드 (연타·키보드 submit 대비)
  if (!canSubmit.value) {
    showConfirm.value = false
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    // 사용자가 입력한 문자열을 그대로 보낸다 — Number 왕복은 소수 8자리를 변질시킨다.
    const payload = { market: market.value, orderType: orderType.value }
    if (needsQuantity.value) payload.quantity = quantityRaw.value
    if (needsUnitPrice.value) payload.price = priceRaw.value
    // 시장가 매수의 총액은 수량이 아니라 price 필드로 간다(업비트 규격).
    if (needsAmount.value) payload.price = amountRaw.value
    // 이 주문 시도의 멱등키. 재시도 때도 같은 값이 실려 서버가 중복 주문을 막는다.
    if (orderAttemptKey.value) payload.idempotencyKey = orderAttemptKey.value

    const res = isBuy.value ? await coinApi.buy(payload) : await coinApi.sell(payload)
    orderResult.value = res?.data ?? null
    showConfirm.value = false
    // 주문이 받아들여졌으므로 이 시도는 끝났다. 다음 주문은 새 키를 쓴다.
    orderAttemptKey.value = null
    // 폼을 비워 같은 주문을 다시 누르는 사고를 막는다.
    form.value = { quantity: '', price: '', amount: '' }
    await loadAccounts()
  } catch (error) {
    logger.debug('코인 주문 실패:', error)
    // 주문 경로는 degrade 대상이 아니다. 실패를 실패로 알린다.
    errorMessage.value = coinErrorMessage(error, '주문에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    showConfirm.value = false
  } finally {
    submitting.value = false
  }
}

const coinName = computed(() => marketInfo.value?.koreanName || symbol.value)
</script>

<template>
  <div class="coin-trading-screen">
    <AppHeader :title="`${coinName} ${isBuy ? '매수' : '매도'}`" showBack />

    <div class="content">
      <!-- 유의/주의 종목: 자금이 실제로 움직이는 화면이므로 폼보다 위에 둔다 -->
      <div v-if="marketInfo?.warning" class="risk-box warning">
        <strong>유의 종목</strong>입니다. 가격 변동과 상장폐지 위험이 큽니다.
      </div>
      <div v-if="(marketInfo?.cautions || []).length > 0" class="risk-box caution">
        <strong>주의</strong>
        <span v-for="caution in marketInfo.cautions" :key="caution" class="risk-tag">
          {{ cautionLabel(caution) }}
        </span>
      </div>

      <!-- 계좌 미등록: 주문 폼 대신 등록 안내로 degrade (시세는 정상 동작) -->
      <section v-if="accountMissing" class="card">
        <h3 class="card-title">업비트 계좌가 등록되지 않았습니다</h3>
        <p class="card-body-text">
          코인 주문과 자산 조회에는 업비트 API 키가 필요합니다. 내 정보 화면에서 Access Key와
          Secret Key를 등록해 주세요. 시세와 호가는 계좌 없이도 볼 수 있습니다.
        </p>
        <button type="button" class="link-btn" @click="router.push('/profile')">
          내 정보에서 업비트 계좌 등록
        </button>
      </section>

      <template v-else>
        <!-- 현재가 -->
        <section class="card">
          <div class="row">
            <span class="row-label">현재가</span>
            <span class="row-value">
              {{ ticker ? `${formatCoinPrice(ticker.tradePrice)}원` : '—' }}
            </span>
          </div>
          <div class="row">
            <span class="row-label">주문가능 원화</span>
            <span class="row-value">
              {{ krwBalance === null ? '—' : `${formatKrw(krwBalance)}원` }}
            </span>
          </div>
          <div class="row">
            <span class="row-label">보유 {{ symbol }}</span>
            <span class="row-value">
              {{ coinBalance === null ? '—' : formatCoinQuantity(coinBalance) }}
            </span>
          </div>
          <p v-if="accountNotice" class="warn-box">{{ accountNotice }}</p>
        </section>

        <!-- 주문 입력 -->
        <section class="card">
          <h3 class="card-title">주문 정보</h3>

          <div class="field">
            <span class="field-label">매수 / 매도</span>
            <div class="toggle-row">
              <button
                type="button"
                :class="['toggle-btn', 'buy', { active: side === 'buy' }]"
                @click="side = 'buy'"
              >
                매수
              </button>
              <button
                type="button"
                :class="['toggle-btn', 'sell', { active: side === 'sell' }]"
                @click="side = 'sell'"
              >
                매도
              </button>
            </div>
          </div>

          <div class="field">
            <span class="field-label">주문 방식</span>
            <div class="toggle-row">
              <button
                type="button"
                :class="['toggle-btn', { active: orderType === 'LIMIT' }]"
                @click="orderType = 'LIMIT'"
              >
                지정가
              </button>
              <button
                type="button"
                :class="['toggle-btn', { active: orderType === 'MARKET' }]"
                @click="orderType = 'MARKET'"
              >
                시장가
              </button>
            </div>
            <p class="field-guide">{{ fieldGuide }}</p>
          </div>

          <!--
            아래 세 입력은 주문 타입에 따라 나타나고 사라진다.
            시장가 매수에 수량 칸이 없는 것은 버그가 아니라 업비트 주문 규격이다.
          -->
          <label v-if="needsQuantity" class="field">
            <span class="field-label">{{ quantityLabel }} ({{ symbol }}, 소수점 8자리까지)</span>
            <div class="field-row">
              <input
                v-model="form.quantity"
                type="text"
                inputmode="decimal"
                class="field-input"
                placeholder="예: 0.00012345"
              />
              <button
                v-if="!isBuy"
                type="button"
                class="chip"
                :disabled="maxSellQuantity === null"
                @click="setMaxQuantity"
              >
                최대
              </button>
            </div>
          </label>

          <label v-if="needsUnitPrice" class="field">
            <span class="field-label">주문 단가 (원)</span>
            <input
              v-model="form.price"
              type="text"
              inputmode="decimal"
              class="field-input"
              placeholder="예: 107969000"
            />
          </label>

          <label v-if="needsAmount" class="field">
            <span class="field-label">주문 총액 (원)</span>
            <p class="field-hint">
              시장가 매수는 수량이 아니라 <strong>쓸 금액</strong>을 입력합니다.
            </p>
            <div class="field-row">
              <input
                v-model="form.amount"
                type="text"
                inputmode="decimal"
                class="field-input"
                placeholder="예: 10000"
              />
              <button
                type="button"
                class="chip"
                :disabled="krwBalance === null"
                @click="setMaxAmount"
              >
                최대
              </button>
            </div>
          </label>

          <div class="expected">
            <div class="expected-row">
              <span class="expected-label">
                {{ totalIsEstimate ? '예상 주문 금액' : '주문 금액' }}
              </span>
              <span class="expected-value">
                {{ estimatedTotal === null ? '—' : `${formatKrw(estimatedTotal)}원` }}
              </span>
            </div>
            <p v-if="totalIsEstimate" class="expected-note">
              시장가 주문은 체결가가 시장에서 정해지므로 실제 체결 금액이 다를 수 있습니다.
            </p>
            <p class="expected-note">
              업비트 최소 주문금액은 {{ formatKrw(MIN_KRW_ORDER_AMOUNT) }}원입니다. 수수료는 별도입니다.
            </p>
          </div>
        </section>

        <p v-if="blockReason" class="block-reason">{{ blockReason }}</p>
        <template v-if="errorMessage">
          <p class="error-box">{{ errorMessage }}</p>
          <!--
            타임아웃이면 업비트는 주문을 받았는데 응답만 유실됐을 수 있다. 그 상태에서
            사용자가 가장 알고 싶은 것은 "다시 눌러도 두 번 사는 것 아닌가"이므로 답을 준다.
            내용을 고치면 다른 주문이 되어 멱등키가 새로 발급된다는 점도 함께 밝힌다.
          -->
          <p class="retry-note">
            주문 내용을 그대로 두고 다시 시도하면 중복 주문이 접수되지 않습니다. 내용을 수정하면
            새로운 주문으로 처리됩니다.
          </p>
        </template>

        <!-- 주문 접수 결과. 체결 결과가 아니라는 점을 반드시 밝힌다. -->
        <section v-if="orderResult" class="card result-card">
          <h3 class="card-title">주문이 접수되었습니다</h3>
          <div class="row">
            <span class="row-label">접수 상태</span>
            <span class="row-value">{{ submittedStateLabel(orderResult.submittedState) }}</span>
          </div>
          <div class="row">
            <span class="row-label">주문 번호</span>
            <span class="row-value">{{ orderResult.orderUuid }}</span>
          </div>
          <div class="row">
            <span class="row-label">주문 수량</span>
            <span class="row-value">{{ formatCoinQuantity(orderResult.volume) }}</span>
          </div>
          <div class="row">
            <span class="row-label">주문 가격 / 총액</span>
            <span class="row-value">{{ formatCoinPrice(orderResult.price) }}</span>
          </div>
          <p v-if="orderResult.duplicate" class="warn-box">
            같은 주문이 이미 접수되어 중복 실행을 막았습니다.
          </p>
          <p class="result-note">{{ SUBMITTED_STATE_NOTE }}</p>
        </section>
      </template>

      <div v-if="loading" class="state-box">불러오는 중...</div>
    </div>

    <div v-if="!accountMissing" class="footer-actions">
      <button
        type="button"
        :class="['submit-button', isBuy ? 'buy' : 'sell']"
        :disabled="!canSubmit"
        @click="openConfirm"
      >
        {{ submitting ? '주문 처리 중...' : `${isLimit ? '지정가' : '시장가'} ${isBuy ? '매수' : '매도'} 주문` }}
      </button>
    </div>

    <!-- 주문 확인: 실제 자금이 움직인다. -->
    <div v-if="showConfirm" class="modal-backdrop" @click.self="showConfirm = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h3 class="modal-title">
          {{ isLimit ? '지정가' : '시장가' }} {{ isBuy ? '매수' : '매도' }} 주문을 실행할까요?
        </h3>
        <div class="rows">
          <div class="row">
            <span class="row-label">종목</span>
            <span class="row-value">{{ coinName }} ({{ market }})</span>
          </div>
          <div v-if="needsQuantity" class="row">
            <span class="row-label">수량</span>
            <span class="row-value">{{ formatCoinQuantity(form.quantity) }} {{ symbol }}</span>
          </div>
          <div v-if="needsUnitPrice" class="row">
            <span class="row-label">단가</span>
            <span class="row-value">{{ formatCoinPrice(form.price) }}원</span>
          </div>
          <div v-if="needsAmount" class="row">
            <span class="row-label">주문 총액</span>
            <span class="row-value">{{ formatKrw(form.amount) }}원</span>
          </div>
          <div class="row">
            <span class="row-label">{{ totalIsEstimate ? '예상 금액' : '주문 금액' }}</span>
            <span class="row-value">
              {{ estimatedTotal === null ? '—' : `${formatKrw(estimatedTotal)}원` }}
            </span>
          </div>
        </div>
        <p class="modal-note">
          실제 자금이 움직입니다. 주문 후에는 이 화면에서 취소할 수 없으며, 취소는 업비트 앱·웹에서
          해야 합니다.
        </p>
        <div class="modal-actions">
          <button type="button" class="modal-btn cancel" @click="showConfirm = false">취소</button>
          <button
            type="button"
            :class="['modal-btn', 'confirm', isBuy ? 'buy' : 'sell']"
            :disabled="submitting"
            @click="submitOrder"
          >
            {{ isBuy ? '매수 실행' : '매도 실행' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.coin-trading-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: 96px;
}

.coin-trading-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 20px;
  padding: 20px;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

.card-title {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 12px;
}

.card-body-text {
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.link-btn {
  margin-top: 14px;
  width: 100%;
  padding: 12px;
  border: none;
  border-radius: 12px;
  background: #F59E0B;
  color: var(--color-text-inverse);
  font-size: 14px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.risk-box {
  padding: 12px 14px;
  border-radius: 14px;
  font-size: 12px;
  line-height: 1.6;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.risk-box.warning {
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
}

.risk-box.caution {
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
}

.risk-tag {
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: rgba(245, 158, 11, 0.2);
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
}

.row-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.row-value {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  text-align: right;
  word-break: break-all;
}

.field {
  display: block;
  margin-top: 16px;
}

.field-label {
  display: block;
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}

.field-hint {
  font-size: 11px;
  color: var(--color-text-tertiary);
  margin-bottom: 8px;
}

.field-guide {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--canvas-hairline-faint);
  font-size: 11px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.field-row {
  display: flex;
  gap: 8px;
}

.field-input {
  flex: 1;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: var(--canvas-hairline-faint);
  color: var(--color-text-primary);
  font-size: 16px;
  font-weight: var(--font-weight-semibold);
}

.field-input:focus {
  outline: none;
  border-color: #F59E0B;
}

.chip {
  padding: 0 16px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}

.chip:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.toggle-row {
  display: flex;
  gap: 8px;
}

.toggle-btn {
  flex: 1;
  padding: 12px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 14px;
  font-weight: var(--font-weight-medium);
  cursor: pointer;
}

.toggle-btn.active {
  background: #F59E0B;
  border-color: #F59E0B;
  color: var(--color-text-inverse);
}

.toggle-btn.buy.active {
  background: #EF4444;
  border-color: #EF4444;
}

.toggle-btn.sell.active {
  background: #3B82F6;
  border-color: #3B82F6;
}

.expected {
  margin-top: 16px;
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.expected-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.expected-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.expected-value {
  font-size: 18px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.expected-note {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: #F59E0B;
}

.warn-box,
.block-reason {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
  font-weight: var(--font-weight-medium);
  line-height: 1.5;
}

.warn-box {
  margin-top: 12px;
}

.error-box {
  padding: 12px;
  border-radius: 12px;
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
  font-size: 13px;
  font-weight: var(--font-weight-medium);
  line-height: 1.5;
}

.retry-note {
  margin-top: -8px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.result-card {
  border: 1px solid rgba(16, 185, 129, 0.4);
}

.result-note {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
  line-height: 1.6;
}

.state-box {
  padding: 12px;
  text-align: center;
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.footer-actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px var(--spacing-lg) 20px;
  background: var(--canvas-gradient-end);
  border-top: 1px solid var(--canvas-hairline);
}

.submit-button {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 14px;
  color: #FFFFFF;
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.submit-button.buy {
  background: #EF4444;
}

.submit-button.sell {
  background: #3B82F6;
}

.submit-button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-lg);
  z-index: 100;
}

.modal {
  width: 100%;
  max-width: 360px;
  background: var(--canvas-card-start);
  border-radius: 20px;
  padding: 20px;
}

.modal-title {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 12px;
}

.modal-note {
  margin-top: 12px;
  font-size: 11px;
  line-height: 1.6;
  color: #F59E0B;
}

.modal-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.modal-btn {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.modal-btn.cancel {
  background: var(--canvas-hairline-soft);
  color: var(--color-text-secondary);
}

.modal-btn.confirm {
  color: #FFFFFF;
}

.modal-btn.confirm.buy {
  background: #EF4444;
}

.modal-btn.confirm.sell {
  background: #3B82F6;
}

.modal-btn.confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>

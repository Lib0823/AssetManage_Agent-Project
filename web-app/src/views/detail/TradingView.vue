<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { stockApi, tradingApi, overseasApi, marketApi } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { isKisOutageError } from '@/utils/kisStatus'
import { logger } from '@/utils/logger'

const route = useRoute()
const router = useRouter()

const symbol = ref(route.params.symbol || '005930')  // Default to Samsung Electronics
const stockName = ref(route.query.name || '삼성전자')  // Stock name
const activeTab = ref('buy')
const loading = ref(false)
const errorMessage = ref('')

// 해외(US) 모드 판정: ?market=US 또는 ?exchange=NASD/NYSE/AMEX
const VALID_EXCHANGES = ['NASD', 'NYSE', 'AMEX']
const rawMarket = String(route.query.market || '').toUpperCase()
const rawExchange = String(route.query.exchange || '').toUpperCase()
const isOverseas = ref(rawMarket === 'US' || VALID_EXCHANGES.includes(rawExchange))
// 거래소 코드 (잔고·매매용 OVRS_EXCG_CD). 기본 NASD.
const exchange = ref(VALID_EXCHANGES.includes(rawExchange) ? rawExchange : 'NASD')

// 실시간 스토어 (브리지 경유 KIS WS). 'disabled'/'reconnecting'/'closed'면
// REST 스냅샷을 그대로 유지하고, WS 프레임이 오면 아래 콜백이 ref를 갱신한다.
const realtimeStore = useRealtimeStore()
// store/service 프로토콜의 market: 'KR' | 'US'
const realtimeMarket = computed(() => (isOverseas.value ? 'US' : 'KR'))
// 활성 구독 해제 함수들 (심볼 변경/언마운트 시 정리 → 구독상한 관리)
let realtimeUnsubs = []

const orderForm = ref({
  type: 'market',
  time: '09:00 ~ 15:30',
  quantity: 1,
  maxQuantity: 100,
  price: 71500,
  maxPrice: 1000000
})

// 실시간 시세
const currentPrice = ref(null)
const changeRate = ref(null)
const changeAmount = ref(null)
const priceNotice = ref(null)

// 호가 (order book) — 국내 전용
const orderbookAsks = ref([]) // 매도호가 (높은 가격), 위쪽
const orderbookBids = ref([]) // 매수호가, 아래쪽
const orderbookNotice = ref(null)
// KIS 점검/연동 불가로 호가를 못 불러온 상태 (true면 가짜 사다리 대신 점검 안내 표시)
const orderbookKisDown = ref(false)

// 주문 가능 정보 — 국내 전용
const orderableNotice = ref(null)

// 환율 (USD → KRW), 해외 모드에서 KRW 병기용
const usdRate = ref(null)

const formatNumber = (num) => {
  return new Intl.NumberFormat('ko-KR').format(Math.round(Number(num) || 0))
}

// USD 금액 포맷 (소수점 2자리)
const formatUsd = (num) => {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number(num) || 0)
}

// 금액 표기 (국내=원, 해외=USD 소수점)
const formatMoney = (num) => {
  return isOverseas.value ? `$${formatUsd(num)}` : `${formatNumber(num)}원`
}

// 총 금액: 가격 * 수량 (가격·수량 양쪽에 반응)
const totalAmount = computed(() => {
  const price = Number(orderForm.value.price) || 0
  const quantity = Number(orderForm.value.quantity) || 0
  return price * quantity
})

// 해외 모드 총액 KRW 환산 (환율 있을 때만)
const totalAmountKrw = computed(() => {
  if (!isOverseas.value || usdRate.value == null) return null
  return totalAmount.value * Number(usdRate.value)
})

// 현재가 KRW 환산 (해외 모드 병기)
const currentPriceKrw = computed(() => {
  if (!isOverseas.value || usdRate.value == null || currentPrice.value == null) return null
  return Number(currentPrice.value) * Number(usdRate.value)
})

// 현재 일시 (ko-KR)
const currentDateTime = computed(() => {
  const now = new Date()
  const date = now.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  })
  const time = now.toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit'
  })
  return `${date} / ${time}`
})

// 호가창에 표시할 행 (매도 위, 매수 아래) — 국내 전용
const orderbookRows = computed(() => {
  const asks = orderbookAsks.value.map((row) => ({ ...row, side: 'ask' }))
  const bids = orderbookBids.value.map((row) => ({ ...row, side: 'bid' }))
  return [...asks, ...bids]
})

// currentPrice 에 가장 가까운 행 highlight 판단
const nearestPrice = computed(() => {
  const base = Number(currentPrice.value)
  if (!base) return null
  let best = null
  let bestDiff = Infinity
  for (const row of orderbookRows.value) {
    const diff = Math.abs(Number(row.price) - base)
    if (diff < bestDiff) {
      bestDiff = diff
      best = row.price
    }
  }
  return best
})

const selectPrice = (price) => {
  if (!price) return
  orderForm.value.price = price
}

const loadPrice = async () => {
  if (isOverseas.value) {
    await loadOverseasPrice()
    return
  }
  try {
    const response = await stockApi.getPrice(symbol.value)
    const data = response?.data || {}
    if (data.notice) {
      priceNotice.value = data.notice
    } else {
      priceNotice.value = null
    }
    if (data.currentPrice != null) {
      currentPrice.value = Number(data.currentPrice)
      changeAmount.value = data.changeAmount != null ? Number(data.changeAmount) : null
      changeRate.value = data.changeRate != null ? Number(data.changeRate) : null
      // 실제 현재가를 주문 가격 기본값으로 (수동 변경 전)
      orderForm.value.price = Number(data.currentPrice)
    }
  } catch (error) {
    logger.debug('Failed to load price:', error)
    priceNotice.value = '시세 미연동'
  }
}

// 해외 현재가: overseasApi.getPrice(symbol, exchange) → {last, base, diff, rate, currency, notice}
const loadOverseasPrice = async () => {
  try {
    const response = await overseasApi.getPrice(symbol.value, exchange.value)
    const data = response?.data || {}
    if (data.notice) {
      priceNotice.value = data.notice
    } else {
      priceNotice.value = null
    }
    if (data.last != null) {
      currentPrice.value = Number(data.last)
      changeAmount.value = data.diff != null ? Number(data.diff) : null
      changeRate.value = data.rate != null ? Number(data.rate) : null
      orderForm.value.price = Number(data.last)
    } else {
      // 시세 비활성/권한없음: 가격 없음 표기 ('—')
      currentPrice.value = null
      changeAmount.value = null
      changeRate.value = null
      if (!priceNotice.value) priceNotice.value = '해외 시세 미연동'
    }
  } catch (error) {
    logger.debug('Failed to load overseas price:', error)
    currentPrice.value = null
    changeAmount.value = null
    changeRate.value = null
    priceNotice.value = '해외 시세 미연동'
  }
}

// 환율 로드 (해외 모드 KRW 병기용). 실패해도 USD 표기는 유지.
const loadExchangeRate = async () => {
  if (!isOverseas.value) return
  try {
    const res = await marketApi.getExchangeRates()
    const list = res && res.success && Array.isArray(res.data) ? res.data : []
    const usd = list.find((r) => String(r?.currency || '').toUpperCase().includes('USD'))
    usdRate.value = usd && usd.rate != null ? Number(usd.rate) : null
  } catch (error) {
    logger.debug('Failed to load exchange rate:', error)
    usdRate.value = null
  }
}

const loadOrderbook = async () => {
  orderbookKisDown.value = false
  try {
    // 국내는 10호가(stockApi), 해외(US)는 1호가(overseasApi). 응답 shape 동일(asks/bids={price,quantity}).
    const response = isOverseas.value
      ? await overseasApi.getOrderbook(symbol.value, exchange.value)
      : await stockApi.getOrderbook(symbol.value)
    const data = response?.data || {}
    const asks = Array.isArray(data.asks) ? data.asks.filter((r) => r && r.price) : []
    const bids = Array.isArray(data.bids) ? data.bids.filter((r) => r && r.price) : []

    if (data.notice || (asks.length === 0 && bids.length === 0)) {
      // KIS 점검/미연동: 가짜 사다리를 만들지 않는다. (실제로 없는 호가에 주문하는 사고 방지)
      orderbookNotice.value = data.notice || '호가 미연동'
      orderbookAsks.value = []
      orderbookBids.value = []
      orderbookKisDown.value = true
      return
    }

    orderbookNotice.value = null
    // asks: 매도호가 높은가격 순 (내림차순) → 위쪽
    orderbookAsks.value = [...asks].sort((a, b) => Number(b.price) - Number(a.price))
    // bids: 매수호가 높은가격 순 (내림차순) → 아래쪽 상단부터
    orderbookBids.value = [...bids].sort((a, b) => Number(b.price) - Number(a.price))
  } catch (error) {
    logger.debug('Failed to load orderbook:', error)
    // KIS 점검/연동 불가: 가짜 사다리 대신 점검 안내를 표시한다.
    orderbookNotice.value = isKisOutageError(error) ? 'KIS 점검중' : '호가 미연동'
    orderbookAsks.value = []
    orderbookBids.value = []
    orderbookKisDown.value = true
  }
}

const loadOrderable = async () => {
  try {
    const price = Number(orderForm.value.price) || Number(currentPrice.value) || 0
    const response = isOverseas.value
      ? await overseasApi.getOrderable(symbol.value, exchange.value, price)
      : await tradingApi.getOrderable(symbol.value, price)
    const data = response?.data || {}
    if (data.notice) {
      orderableNotice.value = data.notice
      return
    }
    orderableNotice.value = null
    // 국내: maxBuyQuantity, 해외: maxBuyQty
    const maxQty = data.maxBuyQuantity ?? data.maxBuyQty
    if (maxQty != null) {
      orderForm.value.maxQuantity = Number(maxQty)
    }
    if (data.orderableCash != null) {
      orderForm.value.maxPrice = Number(data.orderableCash)
    }
  } catch (error) {
    logger.debug('Failed to load orderable:', error)
    orderableNotice.value = '주문가능 미연동'
  }
}

// "최대" 선택 시 수량을 주문가능 최대로 (국내 전용)
const setMaxQuantity = (event) => {
  if (event.target.checked) {
    orderForm.value.quantity = orderForm.value.maxQuantity
  }
}

const loadMarketData = async () => {
  await loadPrice()
  if (isOverseas.value) {
    await Promise.all([loadExchangeRate(), loadOrderbook(), loadOrderable()])
  } else {
    await Promise.all([loadOrderbook(), loadOrderable()])
  }
}

// ── 실시간 (KIS WS via 브리지) ─────────────────────────────────────────────
// 정책(spec 3.3): REST 스냅샷(loadPrice/loadOrderbook)이 초기 paint + 폴백.
// 그 위에 tick/orderbook 구독을 얹어 currentPrice/changeAmount/changeRate 및
// orderbookAsks/bids를 갱신한다. WS JSON은 REST와 동형이므로 정렬/computed/
// 템플릿은 무변경. disabled/reconnecting/closed면 마지막 스냅샷을 그대로 둔다.

// 체결가 프레임 → 현재가/등락 갱신.
// store 핸들러가 msg.currentPrice ?? msg.price 로 정규화하지만, 직접 콜백에서도
// 동일하게 폴백 처리해 국내(STCK_PRPR)·해외(last) 양쪽 필드명을 흡수한다.
const onTick = (msg) => {
  if (!msg) return
  const price = msg.currentPrice ?? msg.price
  if (price != null) {
    currentPrice.value = Number(price)
  }
  if (msg.changeAmount != null) {
    changeAmount.value = Number(msg.changeAmount)
  }
  if (msg.changeRate != null) {
    changeRate.value = Number(msg.changeRate)
  }
  // 주문 가격 입력값(orderForm.price)은 사용자가 만질 수 있으므로 tick으로
  // 덮어쓰지 않는다 (초기 seed는 REST loadPrice/loadOverseasPrice가 담당).
}

// 호가 프레임 → 호가창 갱신. loadOrderbook과 동일한 필터/정렬을 적용해
// 기존 렌더 경로(orderbookRows/nearestPrice)를 그대로 재사용한다.
const onOrderbook = (msg) => {
  if (!msg) return
  const asks = Array.isArray(msg.asks) ? msg.asks.filter((r) => r && r.price) : []
  const bids = Array.isArray(msg.bids) ? msg.bids.filter((r) => r && r.price) : []
  if (asks.length === 0 && bids.length === 0) return // 빈 프레임은 마지막 스냅샷 유지
  orderbookNotice.value = null
  orderbookKisDown.value = false // 실시간 실제 호가 수신 → 점검 안내 해제
  // asks: 매도호가 높은가격 순(내림차순) → 위쪽
  orderbookAsks.value = [...asks].sort((a, b) => Number(b.price) - Number(a.price))
  // bids: 매수호가 높은가격 순(내림차순) → 아래쪽 상단부터
  orderbookBids.value = [...bids].sort((a, b) => Number(b.price) - Number(a.price))
}

// 현재 심볼/시장에 대한 실시간 구독 시작. 기존 구독은 먼저 정리.
const startRealtime = () => {
  stopRealtime()
  const market = realtimeMarket.value
  const sym = symbol.value
  const exch = isOverseas.value ? exchange.value : null

  // 체결가는 국내·해외 공통으로 구독.
  realtimeUnsubs.push(realtimeStore.subscribeTick(market, sym, exch, onTick))

  // 호가: 국내는 10호가, 해외는 1호가. 둘 다 동일 렌더 경로를 사용한다
  // (해외 호가는 백엔드가 비어 보내면 onOrderbook이 무시 → 기존 '해외 호가 미지원' UI 유지).
  realtimeUnsubs.push(realtimeStore.subscribeOrderbook(market, sym, exch, onOrderbook))
}

// 실시간 구독 전체 해제 (refCount 감소 → 0이면 서버에 해제 프레임 전송).
const stopRealtime = () => {
  for (const unsub of realtimeUnsubs) {
    try {
      if (typeof unsub === 'function') unsub()
    } catch (e) {
      logger.debug('Failed to unsubscribe realtime:', e)
    }
  }
  realtimeUnsubs = []
}

const placeOrder = async () => {
  if (loading.value) return

  try {
    loading.value = true
    errorMessage.value = ''

    if (isOverseas.value) {
      await placeOverseasOrder()
      return
    }

    const orderData = {
      stockCode: symbol.value,
      stockName: stockName.value,
      quantity: parseInt(orderForm.value.quantity),
      price: orderForm.value.price
    }

    if (activeTab.value === 'buy') {
      await tradingApi.buy(orderData)
    } else {
      await tradingApi.sell(orderData)
    }

    // Success
    alert(`${activeTab.value === 'buy' ? '매수' : '매도'} 주문이 완료되었습니다.`)

    // Redirect to transactions page
    router.push('/transactions')
  } catch (error) {
    logger.debug('Order failed:', error)
    errorMessage.value = error.response?.data?.message || '주문 실행에 실패했습니다'
    alert(errorMessage.value)
  } finally {
    loading.value = false
  }
}

// 해외 주문: 지정가 전용. {success:false, notice} 형태로 graceful degrade 가능.
const placeOverseasOrder = async () => {
  const qty = parseInt(orderForm.value.quantity)
  const price = Number(orderForm.value.price)

  if (!qty || qty <= 0) {
    errorMessage.value = '수량을 입력해 주세요'
    alert(errorMessage.value)
    return
  }
  if (!price || price <= 0) {
    // 해외는 지정가 전용 → 단가 필수
    errorMessage.value = '해외 주문은 지정가 전용입니다. 단가를 입력해 주세요'
    alert(errorMessage.value)
    return
  }

  const order = {
    symbol: symbol.value,
    exchange: exchange.value,
    quantity: qty,
    price: price
  }

  const response =
    activeTab.value === 'buy'
      ? await overseasApi.buy(order)
      : await overseasApi.sell(order)

  // 백엔드 graceful degrade: { success:false, notice:"..." }
  const data = response?.data || {}
  if (response?.success === false || data.success === false) {
    const notice = data.notice || response?.message || '해외 주문에 실패했습니다'
    errorMessage.value = notice
    alert(notice)
    return
  }

  alert(`${activeTab.value === 'buy' ? '매수' : '매도'} 주문이 완료되었습니다.`)
  router.push('/transactions')
}

// 미체결 주문 (실데이터: /trading/pending-orders → daily-ccld 필터). 국내 전용.
const pendingOrders = ref([])

const loadPendingOrders = async () => {
  try {
    if (isOverseas.value) {
      // 해외: { list:[{orderNo,symbol,name,side,orderPrice}], notice }
      const response = await overseasApi.getPendingOrders(exchange.value)
      const list = Array.isArray(response?.data?.list) ? response.data.list : []
      pendingOrders.value = list.map((order) => ({
        type: (order.side || '').toUpperCase() === 'SELL' ? 'sell' : 'buy',
        name: order.name || order.symbol || '',
        symbol: order.symbol || '',
        price: Number(order.orderPrice ?? order.price) || 0,
        currency: '$'
      }))
      return
    }
    const response = await tradingApi.getPendingOrders()
    const list = Array.isArray(response?.data) ? response.data : []
    pendingOrders.value = list.map((order) => ({
      type: (order.orderType || '').toLowerCase() === 'sell' ? 'sell' : 'buy',
      name: order.stockName || order.stockCode || '',
      symbol: order.stockCode || '',
      price: Number(order.orderPrice) || 0,
      currency: '원'
    }))
  } catch (error) {
    logger.debug('Failed to load pending orders:', error)
    pendingOrders.value = []
  }
}

onMounted(() => {
  loadPendingOrders()
  // REST 스냅샷으로 초기 paint 후 실시간 구독을 얹는다 (구독 자체는 비동기 paint를
  // 기다리지 않아도 됨 — 콜백이 도착하는 대로 ref를 갱신).
  loadMarketData()
  startRealtime()
})

onBeforeUnmount(() => {
  stopRealtime()
})

// 종목 변경 시 시세/호가/주문가능 재조회 + 실시간 재구독 (이전 심볼 구독 해제).
watch(symbol, () => {
  loadMarketData()
  startRealtime()
})

// 가격 변경 시 주문가능 수량/금액 갱신 (국내 전용)
let orderablePriceTimer = null
watch(
  () => orderForm.value.price,
  () => {
    if (isOverseas.value) return
    if (orderablePriceTimer) clearTimeout(orderablePriceTimer)
    orderablePriceTimer = setTimeout(() => {
      loadOrderable()
    }, 300)
  }
)

// 예약 주문은 미지원 (추후 지원) → 항상 빈 배열
const filteredOrders = computed(() => ({
  pending: pendingOrders.value,
  reserved: []
}))

// 실시간 연결 상태 배너. open이면 숨기고, degrade 상태에서만 안내 노출
// (마지막 REST/WS 스냅샷은 그대로 유지). connecting은 잠깐이라 표시 생략.
const realtimeNotice = computed(() => {
  const state = realtimeStore.connectionState
  if (state === 'open' || state === 'connecting') return null
  if (state === 'reconnecting') return realtimeStore.notice || '실시간 연결 재시도 중…'
  if (state === 'disabled') return realtimeStore.notice || '실시간 시세 비활성 (스냅샷 표시)'
  // closed (구독 없음/종료) — 스냅샷만 표시되므로 별도 안내는 생략 가능하나
  // 명시적으로 안내해 사용자 혼선을 줄인다.
  return null
})
</script>

<template>
  <div class="trading-screen">
    <AppHeader title="실시간 매매" showBack show-kis-mode />

    <div class="content">
      <!-- Stock Header -->
      <div class="stock-header">
        <h2 class="stock-name">{{ stockName }}({{ symbol }})</h2>
        <div v-if="currentPrice != null" class="stock-price-row">
          <span class="stock-current-price">
            <template v-if="isOverseas">${{ formatUsd(currentPrice) }}</template>
            <template v-else>{{ formatNumber(currentPrice) }}원</template>
          </span>
          <span
            v-if="changeRate != null"
            :class="['stock-change', changeRate >= 0 ? 'up' : 'down']"
          >
            {{ changeRate >= 0 ? '▲' : '▼' }}
            <template v-if="changeAmount != null">
              <template v-if="isOverseas">{{ formatUsd(Math.abs(changeAmount)) }}</template>
              <template v-else>{{ formatNumber(Math.abs(changeAmount)) }}</template>
            </template>
            ({{ changeRate >= 0 ? '+' : '' }}{{ changeRate.toFixed(2) }}%)
          </span>
        </div>
        <!-- 해외 모드 KRW 병기 -->
        <p v-if="isOverseas && currentPriceKrw != null" class="krw-equivalent">
          ≈ {{ formatNumber(currentPriceKrw) }}원
        </p>
        <!-- 시세 없음: '—' 표기 (국내·해외 공통) -->
        <div v-if="currentPrice == null" class="stock-price-row">
          <span class="stock-current-price">—</span>
        </div>
        <!-- KIS 시세 미연동/점검(현재가): 다른 화면과 동일한 통일 안내 배너 -->
        <KisMaintenanceNotice v-if="priceNotice" variant="banner" class="header-notice" />
        <p v-if="realtimeNotice" class="notice-text realtime-notice">{{ realtimeNotice }}</p>
        <div class="stock-tags">
          <template v-if="isOverseas">
            <span class="tag">해외</span>
            <span class="tag">{{ exchange }}</span>
          </template>
          <template v-else>
            <span class="tag">국내</span>
            <span class="tag">KOSPI</span>
          </template>
        </div>
      </div>

      <!-- Orders Section -->
      <div class="orders-section">
        <div class="order-group" v-if="filteredOrders.pending.length > 0">
          <h3 class="order-title">미체결</h3>
          <div class="order-list">
            <div v-for="(order, idx) in filteredOrders.pending" :key="idx" class="order-item">
              <span :class="['order-type', order.type]">{{ order.type === 'sell' ? '매도' : '매수' }}</span>
              <span class="order-symbol">{{ order.name }}({{ order.symbol }})</span>
              <span class="order-price">{{ formatNumber(order.price) }}{{ order.currency }}</span>
            </div>
          </div>
        </div>

        <div class="order-group">
          <h3 class="order-title">예약 주문 (추후 지원)</h3>
          <div class="no-orders">
            <p>예약 주문 기능은 추후 지원될 예정입니다.</p>
          </div>
        </div>

        <div v-if="!isOverseas && filteredOrders.pending.length === 0" class="no-orders">
          <p>현재 미체결 주문이 없습니다.</p>
        </div>
      </div>

      <!-- Trading Form -->
      <div class="trading-form">
        <!-- Price List (Order Book) — 국내 전용 -->
        <div v-if="!isOverseas" class="price-list">
          <!-- KIS 점검/연동 불가: 가짜 호가 대신 점검 안내 (실제 없는 가격에 주문하는 사고 방지) -->
          <KisMaintenanceNotice v-if="orderbookKisDown" variant="card" />
          <template v-else>
            <div
              v-for="(row, idx) in orderbookRows"
              :key="`${row.side}-${row.price}-${idx}`"
              :class="[
                'price-item',
                row.side,
                { highlight: nearestPrice != null && row.price === nearestPrice }
              ]"
              @click="selectPrice(row.price)"
            >
              <span class="price-item-price">{{ formatNumber(row.price) }}</span>
              <span v-if="row.quantity != null" class="price-item-qty">{{ formatNumber(row.quantity) }}</span>
            </div>
            <div v-if="orderbookRows.length === 0" class="price-item empty">호가 없음</div>
            <p v-if="orderbookNotice" class="notice-text orderbook-notice">{{ orderbookNotice }}</p>
          </template>
        </div>

        <!-- 해외 호가 미지원 안내 -->
        <div v-else class="price-list overseas-orderbook">
          <div class="price-item empty">해외 호가<br />미지원</div>
        </div>

        <!-- Order Form -->
        <div class="order-form">
          <!-- Buy/Sell Tabs -->
          <div class="form-tabs">
            <button
              :class="['form-tab', { active: activeTab === 'buy' }]"
              @click="activeTab = 'buy'"
            >
              매수
            </button>
            <button
              :class="['form-tab', { active: activeTab === 'sell' }]"
              @click="activeTab = 'sell'"
            >
              매도
            </button>
          </div>

          <!-- Form Fields -->
          <div class="form-fields">
            <div class="form-row">
              <span class="form-label">구분</span>
              <span class="form-value">정규장 (지정가)</span>
            </div>
            <div class="form-row">
              <span class="form-label">시간</span>
              <span class="form-value">{{ orderForm.time }}</span>
            </div>
            <div class="form-row">
              <span class="form-label">수량</span>
              <div class="form-input-group">
                <input type="text" v-model="orderForm.quantity" class="form-input" />
                <span v-if="!isOverseas" class="form-hint">
                  주문 가능 {{ formatNumber(orderForm.maxQuantity) }}주
                </span>
                <label v-if="!isOverseas" class="checkbox-small">
                  <input type="checkbox" @change="setMaxQuantity" /> 최대
                </label>
              </div>
            </div>
            <div class="form-row">
              <span class="form-label">가격</span>
              <div class="form-input-group">
                <input type="text" v-model="orderForm.price" class="form-input" />
                <span v-if="isOverseas" class="form-hint">지정가 전용 (USD)</span>
                <span v-else class="form-hint">주문 가능 {{ formatNumber(orderForm.maxPrice) }}원</span>
              </div>
            </div>
            <div class="form-row total">
              <span class="form-label">총 {{ activeTab === 'buy' ? '매수' : '매도' }} 금액</span>
              <span class="form-total">{{ formatMoney(totalAmount) }}</span>
            </div>
            <!-- 해외 총액 KRW 병기 -->
            <div v-if="isOverseas && totalAmountKrw != null" class="form-row krw-row">
              <span class="form-label"></span>
              <span class="krw-equivalent">≈ {{ formatNumber(totalAmountKrw) }}원</span>
            </div>

            <!-- Date Time -->
            <div class="datetime-row">
              <span class="calendar-icon">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <rect x="3" y="4" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2"/>
                  <path d="M16 2V6M8 2V6M3 10H21" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                </svg>
              </span>
              <span class="datetime-value">{{ currentDateTime }}</span>
            </div>
          </div>

          <!-- Submit Button -->
          <button
            :class="['submit-btn', activeTab]"
            :disabled="loading"
            @click="placeOrder"
          >
            {{ activeTab === 'buy' ? '매수' : '매도' }} 주문
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trading-screen {
  min-height: 100vh;
  background: var(--color-bg-primary);
}

.content {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

.stock-header {
  margin-bottom: var(--spacing-lg);
}

.stock-name {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-sm);
}

.stock-price-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.stock-current-price {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.stock-change {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.stock-change.up {
  color: var(--color-stock-up);
}

.stock-change.down {
  color: var(--color-stock-down);
}

.krw-equivalent {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-sm);
}

.notice-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-sm);
}

.orderbook-notice {
  text-align: center;
}

/* 현재가 영역 KIS 점검 안내 배너 */
.header-notice {
  margin-top: var(--spacing-sm);
}

.realtime-notice {
  color: var(--color-text-secondary);
}

.stock-tags {
  display: flex;
  gap: var(--spacing-sm);
}

.tag {
  padding: var(--spacing-xs) var(--spacing-sm);
  background: var(--color-bg-highlight);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

.orders-section {
  margin-bottom: var(--spacing-lg);
}

.order-group {
  margin-bottom: var(--spacing-md);
}

.order-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-sm);
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.order-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm);
  background: var(--color-bg-secondary);
  border-radius: var(--radius-md);
}

.order-type {
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.order-type.sell {
  background: var(--color-secondary);
  color: var(--color-text-inverse);
}

.order-type.buy {
  background: #F97316;
  color: var(--color-text-inverse);
}

.order-symbol {
  flex: 1;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.order-price {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.no-orders {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.trading-form {
  display: flex;
  gap: var(--spacing-md);
}

.price-list {
  flex: 0 0 100px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.overseas-orderbook {
  justify-content: center;
}

.price-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: var(--spacing-sm);
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  background: var(--color-bg-secondary);
  cursor: pointer;
}

/* 매도호가 (asks) → 위쪽, 파란색 */
.price-item.ask {
  background: #DBEAFE;
  color: var(--color-stock-down);
}

/* 매수호가 (bids) → 아래쪽, 빨간색 */
.price-item.bid {
  background: #FEE2E2;
  color: var(--color-stock-up);
}

.price-item.empty {
  background: var(--color-bg-secondary);
  color: var(--color-text-tertiary);
  cursor: default;
}

.price-item-price {
  font-size: var(--font-size-sm);
}

.price-item-qty {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.price-item.highlight {
  font-weight: var(--font-weight-bold);
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.order-form {
  flex: 1;
}

.form-tabs {
  display: flex;
  margin-bottom: var(--spacing-md);
}

.form-tab {
  flex: 1;
  padding: var(--spacing-sm);
  border: none;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  background: var(--color-bg-tertiary);
  color: var(--color-text-secondary);
}

.form-tab.active {
  color: var(--color-text-inverse);
}

.form-tab:first-child.active {
  background: #F97316;
}

.form-tab:last-child.active {
  background: var(--color-secondary);
}

.form-fields {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.form-row {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
}

.form-row.total {
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border-light);
}

.form-row.krw-row {
  margin-top: -2px;
}

.form-label {
  width: 50px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  flex-shrink: 0;
}

.form-value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.form-input-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.form-input {
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
}

.form-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.checkbox-small {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.checkbox-small input {
  width: 14px;
  height: 14px;
}

.form-total {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-stock-up);
}

.datetime-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm);
  background: var(--color-bg-highlight);
  border-radius: var(--radius-md);
}

.calendar-icon {
  color: var(--color-text-secondary);
}

.datetime-value {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.submit-btn {
  width: 100%;
  padding: var(--spacing-md);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-inverse);
  cursor: pointer;
  margin-top: var(--spacing-md);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.submit-btn.buy {
  background: #F97316;
}

.submit-btn.sell {
  background: var(--color-secondary);
}
</style>

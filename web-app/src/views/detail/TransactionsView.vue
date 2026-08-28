<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import InvestmentTabs from '@/components/common/InvestmentTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { tradingApi, overseasApi, bondApi } from '@/services/api'
import { isKisOutageError } from '@/utils/kisStatus'
import { logger } from '@/utils/logger'
import { formatAmount } from '@/utils/bond'

const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })
const loading = ref(false)
const errorMessage = ref('')

// 해외(KIS 연동) 탭 점검중 여부 — 국내(DB 기반) 탭에는 영향 없음
const overseasKisDown = ref(false)

// 채권 거래내역 안내 (조회 실패/미연동). 채권 조회는 degrade 경로라 예외 대신 notice 가 온다.
const bondNotice = ref('')

// 거래 내역 데이터 (API에서 가져옴)
const history = ref([])

// 미체결/예약 주문 데이터
const orders = ref({
  pending: [],
  reserved: []
})

// Load trade history
// 해외(US) 탭 여부 + KIS 일시(yyyyMMddHHmmss) 파서
const isOverseas = computed(() => tabs.value.sub === 'overseas')

// 채권 탭. **분기가 없으면 이 화면은 주식 거래내역을 채권인 것처럼 보여준다**(조용한 오류).
// 채권 거래내역은 DB 가 아니라 KIS 에서 직접 조회한다 — 장내채권은 유동성이 낮아 미체결이
// 정상적으로 자주 발생하고, 주문 시점에 DB 에 쓰면 "주문"을 "체결"로 보여주게 된다.
const isBonds = computed(() => tabs.value.main === 'bonds')

const parseKisDateTime = (s) => {
  if (!s || s.length < 8) return new Date(NaN)
  const y = +s.slice(0, 4), mo = +s.slice(4, 6) - 1, d = +s.slice(6, 8)
  const h = +(s.slice(8, 10) || 0), mi = +(s.slice(10, 12) || 0), se = +(s.slice(12, 14) || 0)
  return new Date(y, mo, d, h, mi, se)
}

const loadHistory = async () => {
  try {
    loading.value = true
    errorMessage.value = ''
    overseasKisDown.value = false
    bondNotice.value = ''

    // 채권: KIS 채권 체결조회(CTSC8013R). 서버가 기간 미지정 시 최근 90일을 준다.
    if (isBonds.value) {
      const res = await bondApi.getHistory()
      const data = res?.data ?? null
      bondNotice.value = data?.notice || ''
      const bList = Array.isArray(data?.list) ? data.list : []
      history.value = bList.map((t, idx) => {
        const at = parseKisDateTime(`${t.orderDate || ''}${t.orderTime || ''}`)
        const executedQty = Number(t.executedQty) || 0
        return {
          id: t.orderNo || `bond-${idx}`,
          symbol: t.bondCode,
          name: t.bondName || t.bondCode,
          type: String(t.side || '').toUpperCase() === 'SELL' ? 'sell' : 'buy',
          quantity: executedQty || Number(t.orderQty) || 0,
          price: Number(t.executedPrice) || 0,
          amount: Number(t.executedAmount) || 0,
          orderedAt: at,
          date: at.toLocaleDateString('ko-KR'),
          time: at.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }),
          // 체결수량 0 = 미체결. 아래 요약/미체결 섹션이 이 값으로 갈린다.
          status: executedQty > 0 ? 'COMPLETED' : 'PENDING',
          // KIS 처리상태명 원문 (부분체결 등을 사용자가 구분할 수 있게 그대로 노출)
          statusText: t.status || '',
          currency: '원',
          isBond: true
        }
      })
      orders.value.pending = history.value.filter((t) => t.status === 'PENDING')
      orders.value.reserved = []
      return
    }

    // 해외(US): 체결내역 + 미체결을 overseasApi 로 조회 (USD)
    if (isOverseas.value) {
      const [hRes, pRes] = await Promise.all([
        overseasApi.getHistory(undefined),
        overseasApi.getPendingOrders(undefined)
      ])
      const hList = Array.isArray(hRes?.data?.list) ? hRes.data.list : []
      history.value = hList.map((t) => {
        const at = parseKisDateTime(t.executedAt)
        const qty = Number(t.qty) || 0
        const price = Number(t.price) || 0
        return {
          id: t.orderNo,
          symbol: t.symbol,
          name: t.name,
          type: (t.side || '').toUpperCase() === 'SELL' ? 'sell' : 'buy',
          quantity: qty,
          price,
          amount: price * qty,
          orderedAt: at,
          date: at.toLocaleDateString('ko-KR'),
          time: at.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }),
          status: 'COMPLETED',  // 체결내역(inquire-ccnl)은 체결 완료분
          currency: '$'
        }
      })
      const pList = Array.isArray(pRes?.data?.list) ? pRes.data.list : []
      orders.value.pending = pList.map((o) => ({
        type: (o.side || '').toUpperCase() === 'SELL' ? 'sell' : 'buy',
        name: o.name || o.symbol || '',
        symbol: o.symbol || '',
        price: Number(o.orderPrice ?? o.price) || 0,
        status: 'PENDING',
        currency: '$'
      }))
      orders.value.reserved = []
      return
    }

    // 거래내역(KIS 3개월 체결조회)은 시세보다 느려 전역 10s 로는 부족 → 25s.
    const response = await tradingApi.getHistory({ timeout: 25000 })

    if (response.data) {
      // TradeHistory 엔티티를 UI 형식으로 변환
      history.value = response.data.map(trade => ({
        id: trade.id,
        symbol: trade.stockCode,
        name: trade.stockName,
        type: trade.orderType.toLowerCase(),  // BUY -> buy, SELL -> sell
        quantity: trade.quantity,
        price: trade.executedPrice || trade.orderPrice,
        amount: (trade.executedPrice || trade.orderPrice) * trade.quantity,
        // 기간 필터가 날짜를 비교할 수 있도록 원본 타임스탬프(Date)를 보존한다.
        orderedAt: new Date(trade.orderedAt),
        date: new Date(trade.orderedAt).toLocaleDateString('ko-KR'),
        time: new Date(trade.orderedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }),
        status: trade.orderStatus,  // PENDING, COMPLETED 등
        aiTraded: trade.aiTraded || false,  // AI(봇) 자동매매 주문 여부
        currency: '원'
      }))

      // 미체결/예약 주문 필터링
      orders.value.pending = history.value.filter(t => t.status === 'PENDING')
      orders.value.reserved = []  // 예약 주문은 별도 API 필요 시 추가

      // 요약(총 매수/매도/기타)은 선택 기간(filteredHistory) 기준으로
      // computed(summary)에서 자동 재계산된다.
    }
  } catch (error) {
    logger.debug('Failed to load trade history:', error)

    // 해외(KIS 연동) 탭에서 KIS 장애면 점검중 안내 표시 (국내 탭은 영향 없음)
    if (isOverseas.value && isKisOutageError(error)) {
      overseasKisDown.value = true
      return
    }

    // API 키 에러 처리
    if (error.response?.status === 401 || error.response?.status === 403) {
      errorMessage.value = 'API 키를 확인해주세요'
    } else if (error.code === 'ECONNABORTED' || error.message?.includes('Network') || error.message?.includes('timeout')) {
      errorMessage.value = '네트워크 연결을 확인해주세요'
    } else if (error.response?.data?.message) {
      errorMessage.value = error.response.data.message
    } else {
      errorMessage.value = '거래 내역을 불러오는데 실패했습니다'
    }
  } finally {
    loading.value = false
  }
}

// 컴포넌트 마운트 시 데이터 로드
onMounted(() => {
  loadHistory()
})

// 탭 전환 시 데이터 소스 전환 재로드.
// main(주식/채권)도 봐야 한다 — sub 만 보면 채권 탭에서 주식 내역이 그대로 남는다.
watch(() => [tabs.value.main, tabs.value.sub], () => {
  history.value = []
  orders.value = { pending: [], reserved: [] }
  loadHistory()
})

const goToTrading = (order) => {
  // 채권은 주식 주문 화면(/trading)이 아니라 채권 상세로 보낸다.
  // (매도는 매수 로트 단위라 거래내역 행만으로는 주문을 만들 수 없다)
  if (order.isBond) {
    router.push(`/bonds/${order.symbol}`)
    return
  }
  router.push(`/trading/${order.symbol}`)
}

// 기간 선택 (달력 대신 버튼 방식)
// KIS 체결조회는 약 3개월치만 반환하므로 그 이상은 노출하지 않는다.
const selectedPeriod = ref('1month')
const periodOptions = [
  { key: '1week', label: '1주일' },
  { key: '1month', label: '1개월' },
  { key: '3months', label: '3개월' }
]

// 캘린더 직접 기간 선택. KIS 체결조회가 최근 3개월만 제공하므로 선택 범위도 3개월로 제한한다.
const showCalendar = ref(false)
const customRange = ref(null) // { start: Date, end: Date } | null

const formatDate = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}.${month}.${day}`
}

// 선택 기간으로부터 컷오프(시작) 날짜를 계산한다. (오늘 기준 상대값)
const getCutoffDate = (key) => {
  const cutoff = new Date()
  cutoff.setHours(0, 0, 0, 0)
  switch (key) {
    case '1week':
      cutoff.setDate(cutoff.getDate() - 7)
      break
    case '1month':
      cutoff.setMonth(cutoff.getMonth() - 1)
      break
    case '3months':
      cutoff.setMonth(cutoff.getMonth() - 3)
      break
  }
  return cutoff
}

const selectPeriod = (key) => {
  selectedPeriod.value = key
  customRange.value = null // 프리셋 선택 시 직접 지정 해제
}

// 캘린더 선택 가능 범위: 최근 3개월 ~ 오늘 (KIS 데이터 제공 범위)
const calendarMinDate = computed(() => getCutoffDate('3months'))
const calendarMaxDate = computed(() => new Date())

const openCalendar = () => {
  showCalendar.value = true
}

// van-calendar type="range" confirm → [startDate, endDate]
const onCalendarConfirm = (range) => {
  const [start, end] = range || []
  if (!start || !end) return
  const s = new Date(start)
  s.setHours(0, 0, 0, 0)
  const e = new Date(end)
  e.setHours(23, 59, 59, 999)
  customRange.value = { start: s, end: e }
  selectedPeriod.value = 'custom'
  showCalendar.value = false
}

// 표시용 날짜 범위: 직접 지정이면 그 범위, 아니면 프리셋(컷오프 ~ 오늘).
const dateRange = computed(() => {
  if (selectedPeriod.value === 'custom' && customRange.value) {
    return {
      start: formatDate(customRange.value.start),
      end: formatDate(customRange.value.end)
    }
  }
  return {
    start: formatDate(getCutoffDate(selectedPeriod.value)),
    end: formatDate(new Date())
  }
})

// 선택 기간 내 거래만 필터링한다. 직접 지정이면 [start, end], 아니면 컷오프 ~ 오늘. 원본 orderedAt(Date)로 비교.
const filteredHistory = computed(() => {
  if (selectedPeriod.value === 'custom' && customRange.value) {
    const { start, end } = customRange.value
    return history.value.filter(
      (t) => t.orderedAt instanceof Date && t.orderedAt >= start && t.orderedAt <= end
    )
  }
  const cutoff = getCutoffDate(selectedPeriod.value)
  return history.value.filter((t) => t.orderedAt instanceof Date && t.orderedAt >= cutoff)
})

// 요약(총 매수/총 매도)을 선택 기간(filteredHistory) 기준으로 재계산한다.
// 배당 수령액·현금 입출금 내역은 KIS 국내주식 OpenAPI에 전용 TR이 없어(개인 ledger 미제공,
// 배당은 종목 기준 '배당일정' HHKDB669102C0만 존재) 요약에서 제외한다. 체결 기반 매수/매도만 집계.
const summary = computed(() => {
  const buyTrades = filteredHistory.value.filter(t => t.type === 'buy' && t.status === 'COMPLETED')
  const sellTrades = filteredHistory.value.filter(t => t.type === 'sell' && t.status === 'COMPLETED')

  return {
    buy: { amount: buyTrades.reduce((sum, t) => sum + t.amount, 0) },
    sell: { amount: sellTrades.reduce((sum, t) => sum + t.amount, 0) }
  }
})

const formatNumber = (num) => {
  return new Intl.NumberFormat('ko-KR').format(num)
}

// 금액 표시. 채권 단가·금액은 소수를 가지므로 원화 정수 포맷터로 자르면 안 된다.
const formatMoney = (num) => (isBonds.value ? formatAmount(num) : formatNumber(num))

const getTypeLabel = (type) => {
  switch (type) {
    case 'buy': return '매수'
    case 'sell': return '매도'
    case 'dividend': return '배당'
    default: return ''
  }
}
</script>

<template>
  <div class="transactions-screen">
    <AppHeader title="거래 내역" showIcon icon="news" />

    <div class="content">
      <!-- Tabs — 채권은 국내/해외 구분이 없다(장내채권 전용) -->
      <InvestmentTabs v-model="tabs" :showSubTabs="!isBonds" />

      <!-- Loading State -->
      <div v-if="loading" class="state-container">
        <div class="spinner"></div>
        <p class="state-message">거래 내역을 불러오는 중...</p>
      </div>

      <!-- KIS Maintenance (해외 탭 전용) -->
      <KisMaintenanceNotice
        v-else-if="isOverseas && overseasKisDown"
        variant="card"
      />

      <!-- Error State -->
      <div v-else-if="errorMessage" class="state-container error-state">
        <div class="error-icon">⚠️</div>
        <p class="error-message">{{ errorMessage }}</p>
        <div class="error-actions">
          <button @click="router.push('/profile')" class="action-button primary">
            내 정보로 이동
          </button>
          <button @click="loadHistory()" class="action-button secondary">
            다시 시도
          </button>
        </div>
      </div>

      <!-- Empty State -->
      <div v-else-if="history.length === 0" class="state-container empty-state">
        <div class="empty-icon">📊</div>
        <p class="empty-message">{{ isBonds ? '채권 거래 내역이 없습니다' : '거래 내역이 없습니다' }}</p>
        <p class="empty-submessage">
          {{ bondNotice || (isBonds ? '보유 채권은 자산 화면에서 확인할 수 있습니다' : '첫 거래를 시작해보세요') }}
        </p>
      </div>

      <!-- Normal Content -->
      <template v-else>

      <!-- 채권 조회 degrade 안내 (목록은 있지만 일부만 온 경우) -->
      <p v-if="bondNotice" class="bond-notice">{{ bondNotice }}</p>

      <!-- Pending/Reserved Orders Section -->
      <section class="pending-section">
        <h3 class="section-title">미체결 / 예약 주문</h3>
        <div class="order-list">
          <div
            v-for="(order, idx) in [...orders.pending.map(o => ({...o, label: '미체결'})), ...orders.reserved.map(o => ({...o, label: '예약'}))]"
            :key="'order-'+idx"
            class="order-item"
            @click="goToTrading(order)"
          >
            <span class="order-label">{{ order.label }}</span>
            <div class="order-info">
              <span :class="['order-type', order.type]">{{ getTypeLabel(order.type) }}</span>
              <span class="order-name">{{ order.name }}</span>
            </div>
            <span class="order-price">{{ formatMoney(order.price) }}{{ order.currency }}</span>
          </div>
        </div>
      </section>

      <!-- Period Transaction History Section -->
      <section class="period-section">
        <h3 class="section-title">기간 거래 내역</h3>

        <!-- Period Selection Buttons -->
        <div class="period-selector">
          <button
            v-for="option in periodOptions"
            :key="option.key"
            :class="['period-btn', { active: selectedPeriod === option.key }]"
            @click="selectPeriod(option.key)"
          >
            {{ option.label }}
          </button>
          <!-- 캘린더 직접 기간 선택 -->
          <button
            :class="['period-btn', 'period-btn-calendar', { active: selectedPeriod === 'custom' }]"
            aria-label="기간 직접 선택"
            @click="openCalendar"
          >
            <van-icon name="calendar-o" />
            <span v-if="selectedPeriod === 'custom'">직접</span>
          </button>
        </div>

        <!-- 기간 직접 선택 캘린더 (최근 3개월 이내) -->
        <van-calendar
          v-model:show="showCalendar"
          type="range"
          :min-date="calendarMinDate"
          :max-date="calendarMaxDate"
          :allow-same-day="true"
          color="#8B5CF6"
          @confirm="onCalendarConfirm"
        />

        <div class="date-range">
          {{ dateRange.start }} - {{ dateRange.end }}
        </div>

        <!-- Summary -->
        <div class="summary-container">
          <div class="summary-card">
            <div class="summary-item">
              <span class="summary-type buy">총 매수</span>
              <span class="summary-amount">{{ formatMoney(summary.buy.amount) }}</span>
            </div>
            <div class="summary-item">
              <span class="summary-type sell">총 매도</span>
              <span class="summary-amount">{{ formatMoney(summary.sell.amount) }}</span>
            </div>
          </div>
        </div>

        <!-- History List -->
        <div class="history-list">
          <div v-for="(item, idx) in filteredHistory" :key="idx" class="history-item">
            <span :class="['history-type', item.type]">{{ getTypeLabel(item.type) }}</span>
            <span class="history-name">
              {{ item.name || item.label }}
              <span v-if="item.aiTraded" class="ai-badge">🤖 AI</span>
              <!-- 채권은 미체결/부분체결이 흔하므로 KIS 처리상태를 그대로 보여준다 -->
              <span v-if="item.statusText" class="status-badge">{{ item.statusText }}</span>
            </span>
            <span class="history-amount">{{ formatMoney(item.amount) }}{{ item.currency }}</span>
          </div>
          <p v-if="filteredHistory.length === 0" class="empty-submessage">
            선택한 기간의 거래 내역이 없습니다
          </p>
        </div>
      </section>
      </template>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.transactions-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: var(--bottom-nav-height);
}

.content {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

/* State Container (Loading, Error, Empty) */
.state-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: var(--spacing-3xl) var(--spacing-xl);
  text-align: center;
}

/* Loading State */
.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(139, 92, 246, 0.2);
  border-top-color: #8B5CF6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: var(--spacing-lg);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.state-message {
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  margin: 0;
}

/* Error State */
.error-state {
  background: rgba(239, 68, 68, 0.05);
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 16px;
}

.error-icon {
  font-size: 64px;
  margin-bottom: var(--spacing-lg);
}

.error-message {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: #EF4444;
  margin: 0 0 var(--spacing-xl) 0;
}

.error-actions {
  display: flex;
  gap: var(--spacing-md);
  flex-direction: column;
  width: 100%;
  max-width: 280px;
}

.action-button {
  padding: var(--spacing-md) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.2s;
}

.action-button.primary {
  background: linear-gradient(135deg, #8B5CF6 0%, #7C3AED 100%);
  color: var(--color-text-inverse);
}

.action-button.primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(139, 92, 246, 0.4);
}

.action-button.secondary {
  background: var(--canvas-hairline);
  color: var(--color-text-primary);
  border: 1px solid var(--canvas-hairline-strong);
}

.action-button.secondary:hover {
  background: var(--canvas-hairline-strong);
}

/* Empty State */
.empty-state {
  background: rgba(139, 92, 246, 0.05);
  border: 1px solid rgba(139, 92, 246, 0.1);
  border-radius: 16px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: var(--spacing-lg);
}

.empty-message {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 var(--spacing-xs) 0;
}

.empty-submessage {
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  margin: 0;
}

/* Pending Orders Section */
.pending-section {
  background: color-mix(in srgb, var(--canvas-card-start) 40%, transparent);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 12px;
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-xl);
}

.pending-section .section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-md);
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
  padding-right: 4px;
}

.order-list::-webkit-scrollbar {
  width: 4px;
}

.order-list::-webkit-scrollbar-track {
  background: var(--canvas-hairline-soft);
  border-radius: 2px;
}

.order-list::-webkit-scrollbar-thumb {
  background: rgba(139, 92, 246, 0.5);
  border-radius: 2px;
}

.order-list::-webkit-scrollbar-thumb:hover {
  background: rgba(139, 92, 246, 0.7);
}

.order-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 8px;
  transition: all 0.2s;
  cursor: pointer;
}

.order-item:hover {
  background: var(--canvas-hairline-soft);
  border-color: rgba(139, 92, 246, 0.3);
  transform: translateX(2px);
}

.order-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  min-width: 40px;
}

.order-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.order-type {
  padding: 4px 8px;
  border-radius: 4px;
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

.order-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.order-price {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

/* Period Transaction Section */
.period-section {
  background: color-mix(in srgb, var(--canvas-card-start) 40%, transparent);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 12px;
  padding: var(--spacing-lg);
}

.period-section .section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-md);
}

/* Period Selector */
.period-selector {
  display: flex;
  gap: 6px;
  margin-bottom: var(--spacing-md);
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.period-selector::-webkit-scrollbar {
  display: none;
}

.period-btn {
  flex-shrink: 0;
  padding: 8px 16px;
  background: var(--canvas-hairline-soft);
  border: 1px solid var(--canvas-hairline);
  border-radius: 8px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.period-btn:hover {
  background: var(--canvas-hairline-soft);
  border-color: var(--canvas-hairline-strong);
}

.period-btn.active {
  background: linear-gradient(135deg, #8B5CF6 0%, #7C3AED 100%);
  border-color: #8B5CF6;
  color: var(--color-text-inverse);
  font-weight: var(--font-weight-medium);
}

.period-btn-calendar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.date-range {
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  padding: 10px;
  background: var(--canvas-hairline-faint);
  border-radius: 8px;
  margin-bottom: var(--spacing-lg);
}

/* Summary Container */
.summary-container {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.15) 0%, rgba(124, 58, 237, 0.1) 100%);
  border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 12px;
  padding: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.summary-card {
  display: flex;
  justify-content: space-around;
  gap: 8px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.summary-type {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.summary-type.buy {
  background: #F97316;
  color: var(--color-text-inverse);
}

.summary-type.sell {
  background: var(--color-secondary);
  color: var(--color-text-inverse);
}

.summary-type.other {
  background: var(--canvas-hairline);
  color: var(--color-text-secondary);
}

.summary-amount {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.summary-detail {
  font-size: 10px;
  color: var(--color-text-tertiary);
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

/* History List */
.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 300px;
  overflow-y: auto;
  padding-right: 4px;
}

.history-list::-webkit-scrollbar {
  width: 4px;
}

.history-list::-webkit-scrollbar-track {
  background: var(--canvas-hairline-soft);
  border-radius: 2px;
}

.history-list::-webkit-scrollbar-thumb {
  background: rgba(139, 92, 246, 0.5);
  border-radius: 2px;
}

.history-list::-webkit-scrollbar-thumb:hover {
  background: rgba(139, 92, 246, 0.7);
}

.history-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 8px;
  transition: all 0.2s;
  flex-shrink: 0;
}

.history-item:hover {
  background: var(--canvas-hairline-soft);
  border-color: rgba(139, 92, 246, 0.3);
}

.history-type {
  padding: 4px 8px;
  border-radius: 4px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  min-width: 40px;
  text-align: center;
}

.history-type.sell {
  background: var(--color-secondary);
  color: var(--color-text-inverse);
}

.history-type.buy {
  background: #F97316;
  color: var(--color-text-inverse);
}

.history-type.dividend {
  background: var(--canvas-hairline);
  color: var(--color-text-secondary);
}

.history-name {
  flex: 1;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

/* 채권 처리상태(미체결/부분체결 등) 원문 배지 */
.status-badge {
  margin-left: 6px;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 10px;
  font-weight: var(--font-weight-medium);
  background: var(--canvas-hairline-soft);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

/* 채권 거래내역 degrade 안내 */
.bond-notice {
  margin: 0 var(--spacing-lg) var(--spacing-md);
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
  font-weight: var(--font-weight-medium);
}

.ai-badge {
  margin-left: 6px;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 10px;
  font-weight: var(--font-weight-medium);
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.25) 0%, rgba(124, 58, 237, 0.25) 100%);
  color: #C4B5FD;
  border: 1px solid rgba(139, 92, 246, 0.4);
  white-space: nowrap;
}

.history-amount {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

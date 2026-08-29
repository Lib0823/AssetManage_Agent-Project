<script setup>
/**
 * 코인 상세 화면 (`/coins/:market`).
 *
 * 시세·호가·캔들을 `Promise.allSettled` 로 **병렬 조회**한다. 셋 중 하나가 실패해도 나머지는
 * 그려야 하기 때문이다(업비트 조회 경로는 예외 대신 `notice` 로 degrade 한다).
 *
 * 가격 표시는 `utils/coin.js` 의 `formatCoinPrice` 만 쓴다. 코인은 BTC(1억원대)와
 * 알트코인(0.5원)이 같은 화면에 뜨므로 고정 자릿수 포맷을 쓰면 한쪽이 반드시 깨진다.
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { Line } from 'vue-chartjs'
import {
  Chart as ChartJS,
  Tooltip,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Filler
} from 'chart.js'
import { coinApi } from '@/services/api'
import { logger } from '@/utils/logger'
import {
  cautionLabel,
  changeClass,
  formatCoinPrice,
  formatCoinQuantity,
  formatKrw,
  formatSignedPrice,
  formatSignedRate,
  symbolOf
} from '@/utils/coin'

ChartJS.register(Tooltip, LineController, LineElement, PointElement, CategoryScale, LinearScale, Filler)

const route = useRoute()
const router = useRouter()

const market = computed(() => String(route.params.market || ''))

const marketInfo = ref(null)
/** 유의·주의 정보를 못 받았을 때의 안내. 비어 있으면 정상적으로 받은 것이다. */
const marketInfoNotice = ref('')
const ticker = ref(null)
const orderbook = ref(null)
const candles = ref([])

const loading = ref(true)
const tickerNotice = ref('')
const orderbookNotice = ref('')
const candleNotice = ref('')

/** 호가는 15단계까지 오지만 화면에는 상위 8단계만 쓴다(모바일 세로 공간). */
const ORDERBOOK_DEPTH = 8

const loadAll = async () => {
  loading.value = true
  tickerNotice.value = ''
  orderbookNotice.value = ''
  candleNotice.value = ''

  const [marketsRes, tickerRes, orderbookRes, candleRes] = await Promise.allSettled([
    coinApi.getMarkets(),
    // 단건 조회 엔드포인트가 없다(의도적) — 배치에 마켓 하나만 담는다.
    coinApi.getTickers([market.value]),
    coinApi.getOrderbook(market.value),
    coinApi.getCandles(market.value, 'days', 30)
  ])

  if (marketsRes.status === 'fulfilled') {
    const data = marketsRes.value?.data ?? null
    const list = data?.markets
    marketInfo.value = Array.isArray(list)
      ? list.find((m) => m.market === market.value) ?? null
      : null
    // 서버는 업비트 장애 시 예외가 아니라 200 + { markets: [], notice } 로 degrade 한다.
    // 흘려보내면 유의/주의 배지가 경고 없이 사라져 "위험 없음"처럼 보인다.
    marketInfoNotice.value = marketInfo.value
      ? ''
      : data?.notice || '유의·주의 종목 정보를 불러오지 못했습니다.'
  } else {
    logger.debug('코인 마켓 정보 조회 실패:', marketsRes.reason)
    marketInfoNotice.value = '유의·주의 종목 정보를 불러오지 못했습니다.'
  }

  if (tickerRes.status === 'fulfilled') {
    const list = Array.isArray(tickerRes.value?.data) ? tickerRes.value.data : []
    ticker.value = list[0] ?? null
    tickerNotice.value = ticker.value?.notice || (ticker.value ? '' : '현재가를 불러오지 못했습니다.')
  } else {
    logger.debug('코인 현재가 조회 실패:', tickerRes.reason)
    tickerNotice.value = '현재가를 불러오지 못했습니다.'
  }

  if (orderbookRes.status === 'fulfilled') {
    orderbook.value = orderbookRes.value?.data ?? null
    orderbookNotice.value = orderbook.value?.notice || ''
  } else {
    logger.debug('코인 호가 조회 실패:', orderbookRes.reason)
    orderbookNotice.value = '호가를 불러오지 못했습니다.'
  }

  if (candleRes.status === 'fulfilled') {
    const data = candleRes.value?.data ?? null
    candles.value = Array.isArray(data?.candles) ? data.candles : []
    candleNotice.value = data?.notice || ''
  } else {
    logger.debug('코인 캔들 조회 실패:', candleRes.reason)
    candleNotice.value = '차트 데이터를 불러오지 못했습니다.'
  }

  loading.value = false
}

onMounted(loadAll)

const coinName = computed(
  () => marketInfo.value?.koreanName || symbolOf(market.value)
)

const askUnits = computed(() => {
  const units = Array.isArray(orderbook.value?.units) ? orderbook.value.units : []
  // 매도호가는 가격이 높은 쪽이 위로 오도록 뒤집는다(호가창 관례).
  return units.slice(0, ORDERBOOK_DEPTH).slice().reverse()
})

const bidUnits = computed(() => {
  const units = Array.isArray(orderbook.value?.units) ? orderbook.value.units : []
  return units.slice(0, ORDERBOOK_DEPTH)
})

/**
 * 캔들 차트. 업비트는 **최신이 앞**인 순서로 주므로 뒤집어야 시간축이 왼쪽→오른쪽이 된다.
 * 뒤집지 않으면 차트가 통째로 좌우 반전돼 추세가 반대로 보인다.
 */
const chartRows = computed(() => candles.value.slice().reverse())

const chartData = computed(() => ({
  labels: chartRows.value.map((c) => String(c.candleDateTimeKst ?? '').slice(5, 10)),
  datasets: [
    {
      data: chartRows.value.map((c) => Number(c.tradePrice)),
      borderColor: '#F59E0B',
      backgroundColor: 'rgba(245, 158, 11, 0.12)',
      borderWidth: 2,
      pointRadius: 0,
      tension: 0.25,
      fill: true
    }
  ]
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        label: (ctx) => `${formatCoinPrice(ctx.parsed.y)}원`
      }
    }
  },
  scales: {
    x: { grid: { display: false }, ticks: { maxTicksLimit: 6 } },
    y: {
      grid: { color: 'rgba(148, 163, 184, 0.15)' },
      ticks: { callback: (value) => formatCoinPrice(value) }
    }
  }
}

const hasChart = computed(() => chartRows.value.length >= 2)

const goToTrade = (side) => {
  router.push({ path: `/coins/${market.value}/trade`, query: { side } })
}
</script>

<template>
  <div class="coin-detail-screen">
    <AppHeader :title="coinName" showBack />

    <div class="content">
      <!-- 유의/주의 종목: 매매 진입 직전 화면이므로 최상단에 둔다 -->
      <div v-if="marketInfo?.warning" class="risk-box warning">
        <strong>유의 종목</strong>으로 지정된 코인입니다. 가격 변동과 상장폐지 위험이 큽니다.
      </div>
      <div v-if="marketInfoNotice" class="risk-box unknown">
        {{ marketInfoNotice }}
      </div>
      <div v-if="(marketInfo?.cautions || []).length > 0" class="risk-box caution">
        <strong>주의 안내</strong>
        <span class="risk-tags">
          <span v-for="caution in marketInfo.cautions" :key="caution" class="risk-tag">
            {{ cautionLabel(caution) }}
          </span>
        </span>
      </div>

      <!-- 현재가 -->
      <section class="card">
        <div class="price-head">
          <div class="price-title">
            <h2 class="price-name">{{ coinName }}</h2>
            <span class="price-market">{{ market }}</span>
          </div>
        </div>

        <p v-if="tickerNotice" class="notice-box">{{ tickerNotice }}</p>

        <template v-if="ticker">
          <div class="price-main">
            <span class="price-value">{{ formatCoinPrice(ticker.tradePrice) }}<span class="unit">원</span></span>
            <span class="price-delta" :class="changeClass(ticker.change, ticker.signedChangeRate)">
              {{ formatSignedPrice(ticker.signedChangePrice) }}
              ({{ formatSignedRate(ticker.signedChangeRate) }})
            </span>
          </div>

          <div class="stat-grid">
            <div class="stat">
              <span class="stat-label">고가</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.highPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">저가</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.lowPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">시가</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.openingPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">전일 종가</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.prevClosingPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">52주 최고</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.highest52WeekPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">52주 최저</span>
              <span class="stat-value">{{ formatCoinPrice(ticker.lowest52WeekPrice) }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">24h 거래대금</span>
              <span class="stat-value">{{ formatKrw(ticker.accTradePrice24h) }}원</span>
            </div>
            <div class="stat">
              <span class="stat-label">24h 거래량</span>
              <span class="stat-value">{{ formatCoinQuantity(ticker.accTradeVolume24h) }}</span>
            </div>
          </div>
        </template>
      </section>

      <!-- 캔들 차트 (일봉 30일) -->
      <section class="card">
        <h3 class="card-title">일봉 (최근 30일)</h3>
        <p v-if="candleNotice" class="notice-box">{{ candleNotice }}</p>
        <div v-if="hasChart" class="chart-box">
          <Line :data="chartData" :options="chartOptions" />
        </div>
        <p v-else-if="!loading && !candleNotice" class="empty-text">차트 데이터가 없습니다</p>
      </section>

      <!-- 호가 -->
      <section class="card">
        <h3 class="card-title">호가 (상위 {{ ORDERBOOK_DEPTH }}단계)</h3>
        <p v-if="orderbookNotice" class="notice-box">{{ orderbookNotice }}</p>

        <div v-if="askUnits.length > 0" class="orderbook">
          <div v-for="(unit, idx) in askUnits" :key="'ask-' + idx" class="ob-row ask">
            <span class="ob-size">{{ formatCoinQuantity(unit.askSize) }}</span>
            <span class="ob-price">{{ formatCoinPrice(unit.askPrice) }}</span>
            <span class="ob-spacer"></span>
          </div>
          <div class="ob-divider"></div>
          <div v-for="(unit, idx) in bidUnits" :key="'bid-' + idx" class="ob-row bid">
            <span class="ob-spacer"></span>
            <span class="ob-price">{{ formatCoinPrice(unit.bidPrice) }}</span>
            <span class="ob-size">{{ formatCoinQuantity(unit.bidSize) }}</span>
          </div>
        </div>
        <p v-else-if="!loading && !orderbookNotice" class="empty-text">호가 정보가 없습니다</p>
      </section>

      <div v-if="loading" class="state-box">불러오는 중...</div>
    </div>

    <div class="footer-actions">
      <button type="button" class="trade-btn buy" @click="goToTrade('buy')">매수</button>
      <button type="button" class="trade-btn sell" @click="goToTrade('sell')">매도</button>
    </div>
  </div>
</template>

<style scoped>
.coin-detail-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: 96px;
}

.coin-detail-screen :deep(.app-header) {
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
  font-size: 15px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 12px;
}

.risk-box {
  padding: 12px 14px;
  border-radius: 14px;
  font-size: 12px;
  line-height: 1.6;
}

.risk-box.warning {
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
}

/* 유의·주의 정보를 못 받은 상태. "위험 없음"이 아니라 "확인 불가"임을 색으로도 구분한다. */
.risk-box.unknown {
  background: rgba(100, 116, 139, 0.12);
  color: #64748B;
}

.risk-box.caution {
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.risk-tag {
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: rgba(245, 158, 11, 0.2);
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
}

.price-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.price-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.price-name {
  font-size: 17px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.price-market {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.price-main {
  margin-top: 12px;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
}

.price-value {
  font-size: 26px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.unit {
  font-size: 14px;
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  margin-left: 2px;
}

.price-delta {
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
}

.price-delta.positive {
  color: #EF4444;
}

.price-delta.negative {
  color: #3B82F6;
}

.price-delta.flat {
  color: var(--color-text-tertiary);
}

.stat-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}

.stat {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.stat-label {
  font-size: 11px;
  color: var(--color-text-secondary);
}

.stat-value {
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  text-align: right;
  word-break: break-all;
}

.chart-box {
  height: 200px;
}

.orderbook {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ob-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 8px;
  font-size: 12px;
}

.ob-row.ask {
  background: rgba(59, 130, 246, 0.08);
}

.ob-row.bid {
  background: rgba(239, 68, 68, 0.08);
}

.ob-price {
  text-align: center;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.ob-size {
  color: var(--color-text-secondary);
  text-align: center;
  word-break: break-all;
}

.ob-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--canvas-hairline);
}

.notice-box {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
}

.empty-text,
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
  display: flex;
  gap: 8px;
  padding: 12px var(--spacing-lg) 20px;
  background: var(--canvas-gradient-end);
  border-top: 1px solid var(--canvas-hairline);
}

.trade-btn {
  flex: 1;
  padding: 14px;
  border: none;
  border-radius: 14px;
  color: #FFFFFF;
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.trade-btn.buy {
  background: #EF4444;
}

.trade-btn.sell {
  background: #3B82F6;
}
</style>

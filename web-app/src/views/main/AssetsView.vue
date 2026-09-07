<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { isKisOutageError } from '@/utils/kisStatus'
import { Doughnut, Line } from 'vue-chartjs'
import {
  Chart as ChartJS,
  ArcElement,
  Tooltip,
  Legend,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Filler
} from 'chart.js'
import { assetApi, overseasApi, marketApi, bondApi, coinApi } from '@/services/api'
import { logger } from '@/utils/logger'
import { normalizeAssetOrder, uiSettings } from '@/utils/uiSettings'
import {
  bondLotKey,
  buildBondLotQuery,
  formatAmount,
  formatKisDate,
  formatQuantity
} from '@/utils/bond'
import {
  buildCoinHoldings,
  coinErrorMessage,
  formatCoinPrice,
  formatCoinQuantity,
  formatKrw,
  isUpbitAccountMissing,
  marketsToQuote
} from '@/utils/coin'

ChartJS.register(
  ArcElement,
  Tooltip,
  Legend,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Filler
)

const router = useRouter()

const loading = ref(false)
const kisUnavailable = ref(false)

// 총자산 일별 추이 (자산 추이 라인차트용). [{ date: 'YYYY-MM-DD', totalAsset: number }]
const history = ref([])

// 보유 채권 (매수 로트 단위). 이 목록이 채권 기능의 유일한 진입점이다 —
// KIS 에 채권 검색 API 가 없어 검색 화면에서 채권으로 들어올 수 없다.
const bondHoldings = ref([])
// 채권 잔고 안내(미연동/조회 실패). 있으면 금액 대신 안내를 띄운다.
const bondNotice = ref('')
// 채권 매수금액 합계 (총자산 합산용)
const bondsTotal = ref(0)

// 보유 코인 (업비트). 자산 화면과 코인 상세를 잇는 진입점 중 하나다.
const coinHoldings = ref([])
// 코인 평가금액 합계 (총자산 합산용). 업비트가 평가금액을 안 주므로 수량 × 현재가로 계산한 값.
const coinsTotal = ref(0)
// 업비트 원화 잔고. **총자산에는 합산하지 않는다** — 현금 카드는 KIS 예수금 기준이라
// 여기에 섞으면 두 출처가 한 숫자에 뭉개진다. 대신 카드에 별도로 표시하고 그 사실을 밝힌다.
const coinKrwBalance = ref(null)
const coinNotice = ref('')
const coinAccountMissing = ref(false)

// 자산 요약 (실데이터로 채움).
const assetSummary = ref({
  totalAsset: 0,
  totalChange: 0,
  changePercent: 0,
  updatedAt: '',
  breakdown: {
    cash: { amount: 0, change: 0, changePercent: 0 },
    stocks: { amount: 0, change: 0, changePercent: 0 },
    bonds: { amount: 0, change: 0, changePercent: 0 },
    coins: { amount: 0, change: 0, changePercent: 0 }
  }
})

// 숫자 파싱 (KIS 응답은 문자열, 빈/누락 시 0)
const toNumber = (value) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

// KIS inquire-balance output2[0]에서 현금/요약 추출 (키 폴백으로 방어적 매핑)
const pick = (obj, keys) => {
  if (!obj) return 0
  for (const key of keys) {
    if (obj[key] !== undefined && obj[key] !== null && obj[key] !== '') {
      return toNumber(obj[key])
    }
  }
  return 0
}

// 총자산 일별 추이 조회 → history 정규화 저장. 실패 시 빈 배열.
const loadHistory = async () => {
  try {
    const res = await assetApi.getHistory(30)
    const rows = Array.isArray(res?.data) ? res.data : []
    history.value = rows.map((row) => ({
      date: row.date,
      totalAsset: toNumber(row.totalAsset)
    }))
  } catch (e) {
    logger.debug('자산 추이 조회 실패:', e)
    history.value = []
  }
}

/**
 * 보유 채권 잔고 조회 → 로트 목록 + 매수금액 합계.
 *
 * 금액은 **매수금액 기준**이다(평가금액이 아니다) — KIS 채권 잔고 응답에 현재가 기준
 * 평가금액 필드 자체가 없다. 카드에도 그 사실을 표시한다.
 *
 * 조회 실패는 화면을 막지 않고 안내만 남긴다(0원으로 조용히 표시하면 "채권을 다 팔았다"로
 * 오인된다). 총자산에는 0 으로 반영된다. 이 함수는 예외를 던지지 않는다 — 주식 잔고 조회가
 * 실패해도 채권 카드는 자기 상태를 표시해야 하므로 loadAssets 의 try 밖에서 호출한다.
 */
const loadBondBalance = async () => {
  bondNotice.value = ''
  try {
    const res = await bondApi.getBalance()
    const data = res?.data ?? null
    if (!data) {
      bondHoldings.value = []
      bondsTotal.value = 0
      bondNotice.value = '채권 잔고를 불러올 수 없습니다'
      return
    }
    bondHoldings.value = Array.isArray(data.holdings) ? data.holdings : []
    bondsTotal.value = toNumber(data.totalBuyAmount)
    bondNotice.value = data.notice || ''
  } catch (error) {
    logger.debug('채권 잔고 조회 실패:', error)
    bondHoldings.value = []
    bondsTotal.value = 0
    bondNotice.value = isKisOutageError(error)
      ? 'KIS 점검 중이거나 일시적인 연동 오류로 채권 잔고를 불러올 수 없어요.'
      : '채권 잔고를 불러오지 못했습니다'
  }
}

/**
 * 보유 코인 조회 → 목록 + 평가금액 합계.
 *
 * 업비트 자산 응답에는 평가금액이 없어 **수량 × 현재가**로 계산한다. 그 현재가는
 * **`/coins/tickers` 배치 1회**로 받는다 — 보유 종목마다 부르면 업비트 IP 한도(10 req/s)를
 * 즉시 소진해 다른 사용자의 시세까지 막는다.
 *
 * KIS 와 무관한 별개 연동이므로 이 함수는 예외를 던지지 않는다(주식 조회 실패와 독립).
 */
const loadCoinAccounts = async () => {
  coinNotice.value = ''
  coinAccountMissing.value = false
  try {
    const accountsRes = await coinApi.getAccounts()
    const accounts = Array.isArray(accountsRes?.data) ? accountsRes.data : []

    const markets = marketsToQuote(accounts)
    let tickers = []
    if (markets.length > 0) {
      try {
        const tickerRes = await coinApi.getTickers(markets)
        tickers = Array.isArray(tickerRes?.data) ? tickerRes.data : []
      } catch (error) {
        logger.debug('코인 시세 배치 조회 실패:', error)
        coinNotice.value = '현재가를 불러오지 못해 평가금액을 계산할 수 없습니다.'
      }
    }

    const built = buildCoinHoldings(accounts, tickers)
    coinHoldings.value = built.holdings
    coinsTotal.value = built.totalEvaluation
    coinKrwBalance.value = built.krwBalance
  } catch (error) {
    if (isUpbitAccountMissing(error)) {
      coinAccountMissing.value = true
    } else {
      logger.debug('코인 자산 조회 실패:', error)
      coinNotice.value = coinErrorMessage(error, '코인 자산을 불러오지 못했습니다.')
    }
    coinHoldings.value = []
    coinsTotal.value = 0
    coinKrwBalance.value = null
  }
}

const loadAssets = async () => {
  loading.value = true
  kisUnavailable.value = false

  // 채권·코인은 주식 잔고와 독립된 연동이라 주식 조회가 실패해도 따로 보여준다.
  // (두 로더 모두 예외를 던지지 않는다)
  await Promise.all([loadBondBalance(), loadCoinAccounts()])

  try {
    const [balanceRes, holdingsRes] = await Promise.all([
      assetApi.getBalance(),
      assetApi.getHoldings()
    ])

    // getBalance() → { balance: { output1, output2 } }
    const balance = balanceRes?.data?.balance ?? balanceRes?.data ?? {}
    // getHoldings() → { output1, output2 }
    const holdings = holdingsRes?.data ?? {}

    const summaryRow = Array.isArray(balance.output2) && balance.output2.length > 0
      ? balance.output2[0]
      : (Array.isArray(holdings.output2) && holdings.output2.length > 0 ? holdings.output2[0] : null)

    // 현금: 주문가능현금 우선, 없으면 예수금총액
    const cashAmount = pick(summaryRow, ['ord_psbl_cash', 'dnca_tot_amt', 'prvs_rcdl_excc_amt'])

    // 주식 평가금액 / 손익
    const stockEvalAmount = pick(summaryRow, ['scts_evlu_amt', 'tot_evlu_amt'])
    const stockProfit = pick(summaryRow, ['evlu_pfls_smtl_amt', 'tot_evlu_pfls_amt', 'evlu_pfls_smtl'])

    // output2에 종목평가금액이 없으면 보유종목 평가금액 합으로 보강
    let stocksAmount = stockEvalAmount
    if (stocksAmount === 0 && Array.isArray(holdings.output1)) {
      stocksAmount = holdings.output1.reduce((sum, item) => sum + pick(item, ['evlu_amt']), 0)
    }

    // 해외 주식(USD) 평가금액·손익을 KRW로 환산해 국내 주식에 합산.
    // 조회 실패/환율 없음 시 0으로 graceful (국내만 반영).
    let overseasKrw = 0
    let overseasProfitKrw = 0
    try {
      const [ovRes, fxRes] = await Promise.all([overseasApi.getBalance(), marketApi.getExchangeRates()])
      const ov = ovRes?.data ?? null
      const fxList = fxRes && fxRes.success && Array.isArray(fxRes.data) ? fxRes.data : []
      const usd = fxList.find((r) => r && r.currency === 'USD')
      const usdRate = usd && Number.isFinite(Number(usd.rate)) ? Number(usd.rate) : null
      if (ov && usdRate) {
        overseasKrw = toNumber(ov.totalEval) * usdRate
        overseasProfitKrw = toNumber(ov.totalProfitLoss) * usdRate
      }
    } catch (e) {
      logger.debug('해외 잔고/환율 합산 생략:', e)
    }

    const stocksTotal = stocksAmount + overseasKrw
    const stocksProfitTotal = stockProfit + overseasProfitKrw

    // 채권(매수금액 기준)과 코인(평가금액 기준)을 총자산에 합산한다.
    //
    // 주의: 아래 recordSnapshot 이 이 값을 asset_daily_snapshot 에 쓰므로, 새 자산 종류가
    // 합산에 들어간 날부터 총자산 시계열이 계단처럼 뛴다(자산이 늘어난 게 아니라 집계 범위가
    // 넓어진 것). 채권·코인 모두 과거 시계열을 받을 수 없어 소급 보정이 불가능하므로
    // 불연속은 불가피하며, 이 사실은 _workspace/dev_bond_frontend.md /
    // _workspace/dev_coin_frontend.md 에 기록해 뒀다.
    //
    // 업비트 원화 잔고(coinKrwBalance)는 **여기 포함하지 않는다** — 현금 카드가 KIS 예수금
    // 기준이라 두 출처를 한 숫자에 섞으면 어느 쪽 현금인지 추적할 수 없어진다.
    const totalAsset = cashAmount + stocksTotal + bondsTotal.value + coinsTotal.value

    assetSummary.value = {
      totalAsset,
      // 총자산 전일대비 추세 엔드포인트 없음 → 주식 평가손익(국내+해외)을 총 변동으로 표시
      totalChange: stocksProfitTotal,
      changePercent: totalAsset > 0 ? stocksProfitTotal / totalAsset : 0,
      updatedAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
      breakdown: {
        cash: { amount: cashAmount, change: 0, changePercent: 0 },
        stocks: {
          amount: stocksTotal,
          change: stocksProfitTotal,
          changePercent: stocksTotal > 0 ? stocksProfitTotal / stocksTotal : 0
        },
        // 채권은 매수금액 기준이라 전일대비/수익률을 계산할 근거가 없다 → 0 고정.
        bonds: { amount: bondsTotal.value, change: 0, changePercent: 0 },
        // 코인 손익은 매수평균가 기준으로 계산 가능하지만, 카드가 종목별로 이미 보여주므로
        // 요약에는 평가금액만 싣는다(주식의 '전일대비'와 성격이 달라 섞으면 오해를 부른다).
        coins: { amount: coinsTotal.value, change: 0, changePercent: 0 }
      }
    }

    // 오늘 총자산 스냅샷 기록(fire-and-forget) 후 추이 조회.
    // 스냅샷 실패는 추이 로딩을 막지 않는다.
    assetApi
      .recordSnapshot(totalAsset)
      .catch((e) => logger.debug('총자산 스냅샷 기록 생략:', e))
    await loadHistory()
  } catch (error) {
    logger.debug('Failed to load assets:', error)
    if (isKisOutageError(error)) {
      kisUnavailable.value = true
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadAssets)

const formatNumber = (num) => {
  return new Intl.NumberFormat('ko-KR').format(num)
}

const formatChange = (change) => {
  const sign = change >= 0 ? '+' : ''
  return `${sign}${formatNumber(change)}`
}

const formatPercent = (percent) => {
  const sign = percent >= 0 ? '+' : ''
  return `${sign}${(percent * 100).toFixed(2)}%`
}

const calculatePercentage = (amount, total) => {
  if (!total) return '0.0'
  return ((amount / total) * 100).toFixed(1)
}

const pieChartData = computed(() => ({
  labels: ['현금', '주식', '채권', '코인'],
  datasets: [{
    data: [
      assetSummary.value.breakdown.cash.amount,
      assetSummary.value.breakdown.stocks.amount,
      assetSummary.value.breakdown.bonds.amount,
      assetSummary.value.breakdown.coins.amount
    ],
    backgroundColor: ['#3B82F6', '#F97316', '#10B981', '#A855F7'],
    borderWidth: 0,
    hoverOffset: 4
  }]
}))

const pieChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      callbacks: {
        label: function(context) {
          const label = context.label || ''
          const value = formatNumber(context.parsed)
          const percentage = calculatePercentage(context.parsed, assetSummary.value.totalAsset)
          return `${label}: ${value}원 (${percentage}%)`
        }
      }
    }
  },
  cutout: '70%'
}

// 자산 추이 라인차트 — 데이터 2건 이상일 때만 표시
const hasHistory = computed(() => history.value.length >= 2)

// 'YYYY-MM-DD' → 'MM/DD'
const formatShortDate = (date) => {
  if (typeof date !== 'string') return ''
  const parts = date.split('-')
  return parts.length === 3 ? `${parts[1]}/${parts[2]}` : date
}

const lineChartData = computed(() => ({
  labels: history.value.map((row) => formatShortDate(row.date)),
  datasets: [{
    label: '총 자산',
    data: history.value.map((row) => row.totalAsset),
    borderColor: '#8B5CF6',
    backgroundColor: 'rgba(139, 92, 246, 0.15)',
    pointBackgroundColor: '#8B5CF6',
    pointBorderColor: '#8B5CF6',
    pointRadius: 3,
    pointHoverRadius: 5,
    borderWidth: 2,
    tension: 0.35,
    fill: true
  }]
}))

const lineChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  interaction: {
    mode: 'index',
    intersect: false
  },
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      callbacks: {
        label: (context) => `총 자산: ${formatNumber(context.parsed.y)}원`
      }
    }
  },
  scales: {
    x: {
      grid: {
        color: 'rgba(255, 255, 255, 0.05)'
      },
      ticks: {
        color: '#94A3B8',
        font: { size: 11 },
        maxRotation: 0,
        autoSkip: true,
        maxTicksLimit: 7
      }
    },
    y: {
      grid: {
        color: 'rgba(255, 255, 255, 0.05)'
      },
      ticks: {
        color: '#94A3B8',
        font: { size: 11 },
        callback: (value) => formatNumber(value)
      }
    }
  }
}

const assetColors = {
  cash: '#3B82F6',
  stocks: '#F97316',
  bonds: '#10B981',
  coins: '#A855F7'
}

const assetIcons = {
  cash: '💰',
  stocks: '📈',
  bonds: '📊',
  coins: '🪙'
}

// 설정 > '관심 자산 순위'(uiSettings.assetOrder)를 이 화면의 카드 배치에 반영한다.
// 설정 목록의 국내/해외 주식은 이 화면에서 통합 '주식' 카드 하나로 접히고,
// 현금은 설정 목록에 없는 항목이므로 항상 최상단에 고정한다.
const SECTION_BY_ASSET_KEY = {
  stocks_domestic: 'stocks',
  stocks_overseas: 'stocks',
  coins: 'coins',
  bonds: 'bonds'
}
const ALL_SECTIONS = ['cash', 'stocks', 'bonds', 'coins']

const orderedSections = computed(() => {
  const sections = ['cash']

  for (const item of normalizeAssetOrder(uiSettings.value.assetOrder)) {
    const section = SECTION_BY_ASSET_KEY[item.key]
    if (section && !sections.includes(section)) {
      sections.push(section)
    }
  }

  // 저장값에 없는 섹션도 빠뜨리지 않는다
  for (const section of ALL_SECTIONS) {
    if (!sections.includes(section)) sections.push(section)
  }

  return sections
})

const goToDetail = (type) => {
  router.push({
    path: '/assets/detail',
    query: {
      main: type,
      // 주식 상세는 국내 탭으로 진입하되, 상세 화면의 국내/해외 서브탭으로 해외도 조회 가능.
      sub: type === 'stocks' ? 'domestic' : undefined
    }
  })
}

// 보유 로트 → 채권 상세.
// buyDate/buySeq(+ 분리과세 추정값)를 쿼리로 함께 넘긴다. 매도 화면까지 그대로 운반되며,
// 없으면 어느 매수분을 파는지 특정할 수 없어 매도 요청이 400 이 된다.
const goToBondDetail = (lot) => {
  if (!lot?.bondCode) return
  router.push({ path: `/bonds/${lot.bondCode}`, query: buildBondLotQuery(lot) })
}

// 보유 코인 → 코인 상세. 채권과 달리 운반할 로트 정보가 없어 마켓 코드만 있으면 된다.
const goToCoinDetail = (holding) => {
  if (!holding?.market) return
  router.push(`/coins/${holding.market}`)
}

const goToCoinSearch = () => {
  router.push('/coins')
}

const handleRefresh = () => {
  loadAssets()
}
</script>

<template>
  <div class="assets-screen">
    <AppHeader title="자산 정보" showIcon icon="assets" />

    <div class="header-actions">
      <span class="update-time">기준 일시: {{ assetSummary.updatedAt }}</span>
      <button class="refresh-button" @click="handleRefresh">
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21.5 2v6h-6M2.5 22v-6h6M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.2"/>
        </svg>
      </button>
    </div>

    <div class="content">
      <!-- KIS 점검중 안내 (잔고/보유 조회 실패 시) -->
      <KisMaintenanceNotice v-if="kisUnavailable" variant="card" />

      <!-- Total Asset Summary -->
      <section v-if="!kisUnavailable" class="total-section">
        <div class="total-header">
          <h2 class="total-label">총 자산</h2>
          <div class="total-badge">
            <span :class="['badge-change', assetSummary.totalChange >= 0 ? 'positive' : 'negative']">
              {{ formatChange(assetSummary.totalChange) }}
            </span>
            <span :class="['badge-percent', assetSummary.changePercent >= 0 ? 'positive' : 'negative']">
              {{ formatPercent(assetSummary.changePercent) }}
            </span>
          </div>
        </div>
        <div class="total-value">{{ formatNumber(assetSummary.totalAsset) }}<span class="currency">원</span></div>

        <!-- Asset Distribution -->
        <div class="distribution-section">
          <div class="pie-chart-container">
            <div class="pie-chart">
              <Doughnut :data="pieChartData" :options="pieChartOptions" />
              <div class="chart-center">
                <div class="center-label">총 자산</div>
                <div class="center-value">100%</div>
              </div>
            </div>
          </div>

          <div class="legend-list">
            <div class="legend-item" v-for="(item, key) in assetSummary.breakdown" :key="key">
              <div class="legend-color" :style="{ backgroundColor: assetColors[key] }"></div>
              <div class="legend-info">
                <span class="legend-name">{{ key === 'cash' ? '현금' : key === 'stocks' ? '주식' : key === 'bonds' ? '채권' : '코인' }}</span>
                <span class="legend-percent">{{ calculatePercentage(item.amount, assetSummary.totalAsset) }}%</span>
              </div>
              <span class="legend-value">{{ formatNumber(item.amount) }}원</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Asset Trend (일별 총자산 추이) -->
      <section v-if="!kisUnavailable" class="trend-section">
        <div class="trend-header">
          <h2 class="trend-label">자산 추이</h2>
          <span class="trend-sub">최근 30일 총자산</span>
        </div>
        <div v-if="hasHistory" class="trend-chart">
          <Line :data="lineChartData" :options="lineChartOptions" />
        </div>
        <div v-else class="trend-empty">
          자산 추이는 며칠 사용하면 표시됩니다.
        </div>
      </section>

      <!-- Asset Cards — 순서는 설정 > 관심 자산 순위(orderedSections)를 따른다 -->
      <div class="asset-cards">
        <template v-for="section in orderedSections" :key="section">
          <!-- Cash -->
          <section v-if="section === 'cash' && !kisUnavailable" class="asset-card cash" @click="goToDetail('cash')">
            <div class="card-header">
              <div class="card-icon">{{ assetIcons.cash }}</div>
              <div class="card-title-group">
                <h3 class="card-title">현금</h3>
                <span class="card-percentage">{{ calculatePercentage(assetSummary.breakdown.cash.amount, assetSummary.totalAsset) }}%</span>
              </div>
              <div class="card-arrow">→</div>
            </div>

            <div class="card-body">
              <div class="card-value-section">
                <div class="value-label">보유 금액</div>
                <div class="value-amount">{{ formatNumber(assetSummary.breakdown.cash.amount) }}<span class="unit">원</span></div>
              </div>

              <div class="card-stats">
                <div class="stat-item">
                  <span class="stat-label">전일 대비</span>
                  <span :class="['stat-value', assetSummary.breakdown.cash.change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(assetSummary.breakdown.cash.change) }}
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">변동률</span>
                  <span :class="['stat-value', assetSummary.breakdown.cash.changePercent >= 0 ? 'positive' : 'negative']">
                    {{ formatPercent(assetSummary.breakdown.cash.changePercent) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="card-indicator" :style="{ backgroundColor: assetColors.cash }"></div>
          </section>

          <!-- Stocks -->
          <section v-else-if="section === 'stocks' && !kisUnavailable" class="asset-card stocks" @click="goToDetail('stocks')">
            <div class="card-header">
              <div class="card-icon">{{ assetIcons.stocks }}</div>
              <div class="card-title-group">
                <h3 class="card-title">주식</h3>
                <span class="card-percentage">{{ calculatePercentage(assetSummary.breakdown.stocks.amount, assetSummary.totalAsset) }}%</span>
              </div>
              <div class="card-arrow">→</div>
            </div>

            <div class="card-body">
              <div class="card-value-section">
                <div class="value-label">평가 금액</div>
                <div class="value-amount">{{ formatNumber(assetSummary.breakdown.stocks.amount) }}<span class="unit">원</span></div>
              </div>

              <div class="card-stats">
                <div class="stat-item">
                  <span class="stat-label">평가 손익</span>
                  <span :class="['stat-value', assetSummary.breakdown.stocks.change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(assetSummary.breakdown.stocks.change) }}
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">수익률</span>
                  <span :class="['stat-value', assetSummary.breakdown.stocks.changePercent >= 0 ? 'positive' : 'negative']">
                    {{ formatPercent(assetSummary.breakdown.stocks.changePercent) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="card-indicator" :style="{ backgroundColor: assetColors.stocks }"></div>
          </section>

          <!--
            Bonds — 채권 기능의 유일한 진입점.
            KIS 에 채권 검색 API 가 없어 검색 화면에서 채권으로 들어올 수 없다.
            여기서 로트를 눌러야만 상세·매도로 갈 수 있다.
            주식 잔고와 독립된 TR 이므로 kisUnavailable 과 무관하게 자기 상태를 표시한다.
          -->
          <section v-else-if="section === 'bonds'" class="asset-card bonds">
            <div class="card-header">
              <div class="card-icon">{{ assetIcons.bonds }}</div>
              <div class="card-title-group">
                <h3 class="card-title">채권</h3>
                <span class="card-percentage">{{ calculatePercentage(bondsTotal, assetSummary.totalAsset) }}%</span>
              </div>
            </div>

            <div class="card-body">
              <div class="card-value-section">
                <!-- KIS 채권 잔고에는 평가금액 필드가 없다. 매수금액임을 반드시 밝힌다. -->
                <div class="value-label">매수 금액 기준 (평가 금액 아님)</div>
                <!--
                  assetSummary.breakdown 이 아니라 bondsTotal 을 직접 읽는다.
                  주식 잔고 조회가 실패하면 assetSummary 가 갱신되지 않아, breakdown 을 쓰면
                  로트는 보이는데 합계만 0원인 화면이 된다.
                -->
                <div class="value-amount">{{ formatAmount(bondsTotal) }}<span class="unit">원</span></div>
              </div>

              <!--
                notice 는 목록과 배타가 아니다 — 연속조회 상한에 걸리면 백엔드가
                holdings(일부) + notice 를 함께 내려준다. v-else 체인으로 묶으면
                그 경우 로트가 한 건도 안 보여, 채권 기능의 주 진입점이 막힌다.
                AssetDetailView 와 같은 구조로 둔다(배너는 독립 v-if).
              -->
              <KisMaintenanceNotice v-if="bondNotice" variant="banner" :message="bondNotice" />

              <p v-if="bondHoldings.length === 0" class="bond-empty">
                보유 중인 채권이 없습니다
              </p>

              <div v-else class="bond-lot-list">
                <button
                  v-for="lot in bondHoldings"
                  :key="bondLotKey(lot)"
                  type="button"
                  class="bond-lot"
                  @click="goToBondDetail(lot)"
                >
                  <span class="bond-lot-main">
                    <span class="bond-lot-name">{{ lot.bondName || lot.bondCode }}</span>
                    <span class="bond-lot-sub">매수일 {{ formatKisDate(lot.buyDate) }} · {{ formatQuantity(lot.quantity) }}</span>
                  </span>
                  <span class="bond-lot-amount">{{ formatAmount(lot.buyAmount) }}원</span>
                  <span class="bond-lot-arrow">›</span>
                </button>
              </div>
            </div>

            <div class="card-indicator" :style="{ backgroundColor: assetColors.bonds }"></div>
          </section>

          <!--
            Coins — 업비트 원화마켓. 주식·채권과 독립된 연동이므로 kisUnavailable 과 무관하게
            자기 상태를 표시한다. 평가금액은 업비트가 주지 않아 수량 × 현재가로 계산한 값이며,
            그 현재가는 배치 티커 1회로 받는다.
          -->
          <section v-else-if="section === 'coins'" class="asset-card coins">
            <div class="card-header">
              <div class="card-icon">{{ assetIcons.coins }}</div>
              <div class="card-title-group">
                <h3 class="card-title">코인</h3>
                <span class="card-percentage">{{ calculatePercentage(coinsTotal, assetSummary.totalAsset) }}%</span>
              </div>
            </div>

            <div class="card-body">
              <div class="card-value-section">
                <div class="value-label">평가 금액 (현재가 기준)</div>
                <!--
                  breakdown 이 아니라 coinsTotal 을 직접 읽는다 — 주식 잔고 조회가 실패하면
                  assetSummary 가 갱신되지 않아 보유는 보이는데 합계만 0원인 화면이 된다.
                -->
                <div class="value-amount">{{ formatKrw(coinsTotal) }}<span class="unit">원</span></div>
              </div>

              <!-- 계좌 미등록은 장애가 아니다. 등록 경로를 함께 준다. -->
              <div v-if="coinAccountMissing" class="coin-guide">
                <p class="coin-guide-text">
                  업비트 계좌가 등록되지 않았습니다. 내 정보에서 API 키를 등록하면 보유 코인이
                  표시됩니다.
                </p>
                <button type="button" class="coin-guide-btn" @click="router.push('/profile')">
                  내 정보로 이동
                </button>
              </div>

              <template v-else>
                <KisMaintenanceNotice v-if="coinNotice" variant="banner" :message="coinNotice" />

                <p v-if="coinHoldings.length === 0" class="bond-empty">
                  보유 중인 코인이 없습니다
                </p>

                <div v-else class="bond-lot-list">
                  <button
                    v-for="holding in coinHoldings"
                    :key="holding.market"
                    type="button"
                    class="bond-lot"
                    @click="goToCoinDetail(holding)"
                  >
                    <span class="bond-lot-main">
                      <span class="bond-lot-name">{{ holding.symbol }}</span>
                      <span class="bond-lot-sub">
                        {{ formatCoinQuantity(holding.quantity) }} · 매수평균
                        {{ formatCoinPrice(holding.avgBuyPrice) }}
                      </span>
                    </span>
                    <span class="bond-lot-amount">
                      {{ holding.evaluation === null ? '—' : formatKrw(holding.evaluation) + '원' }}
                    </span>
                    <span class="bond-lot-arrow">›</span>
                  </button>
                </div>

                <!--
                  업비트 원화는 KIS 예수금과 출처가 달라 현금 카드에 섞지 않았다.
                  총자산에서 빠져 있다는 사실을 숨기지 않고 그대로 밝힌다.
                -->
                <p v-if="coinKrwBalance !== null" class="coin-krw-note">
                  업비트 원화 {{ formatKrw(coinKrwBalance) }}원 (현금 카드는 KIS 예수금 기준이라
                  총자산에 합산되지 않습니다)
                </p>

                <button type="button" class="coin-search-btn" @click="goToCoinSearch">
                  코인 검색·매매
                </button>
              </template>
            </div>

            <div class="card-indicator" :style="{ backgroundColor: assetColors.coins }"></div>
          </section>
        </template>
      </div>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.assets-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.assets-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
}

/* Header Actions */
.header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 5px 14px 1px;
}

.refresh-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  background: transparent;
  border: none;
  padding: 0;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.3s;
}

.refresh-button:hover {
  color: var(--color-text-primary);
  transform: rotate(180deg);
}

.refresh-button:active {
  transform: rotate(180deg) scale(0.9);
}

.update-time {
  font-size: 11px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.total-section {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 24px;
  padding: 24px;
  margin-bottom: var(--spacing-xl);
  box-shadow: 0 8px 32px var(--canvas-card-shadow);
}

.total-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.total-label {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.total-badge {
  display: flex;
  gap: 8px;
  align-items: center;
}

.badge-change, .badge-percent {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  padding: 4px 12px;
  border-radius: 12px;
  background: var(--canvas-hairline-soft);
}

.badge-change.positive, .badge-percent.positive {
  color: #10B981;
  background: rgba(16, 185, 129, 0.1);
}

.badge-change.negative, .badge-percent.negative {
  color: #EF4444;
  background: rgba(239, 68, 68, 0.1);
}

.total-value {
  font-size: 36px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 24px;
  letter-spacing: -0.02em;
}

.currency {
  font-size: 24px;
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
  margin-left: 4px;
}

/* Distribution Section */
.distribution-section {
  margin-bottom: 24px;
}

.pie-chart-container {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}

.pie-chart {
  position: relative;
  width: 160px;
  height: 160px;
}

.chart-center {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.center-label {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}

.center-value {
  font-size: 20px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.legend-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.legend-color {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.legend-name {
  font-size: 14px;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.legend-percent {
  font-size: 12px;
  color: var(--color-text-secondary);
  background: var(--canvas-hairline-soft);
  padding: 2px 8px;
  border-radius: 8px;
}

.legend-value {
  font-size: 14px;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

/* Asset Trend Section */
.trend-section {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 24px;
  padding: 24px;
  margin-bottom: var(--spacing-xl);
  box-shadow: 0 8px 32px var(--canvas-card-shadow);
}

.trend-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;
}

.trend-label {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.trend-sub {
  font-size: 12px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

.trend-chart {
  position: relative;
  width: 100%;
  height: 200px;
}

.trend-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

/* Asset Cards */
.asset-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.asset-card {
  position: relative;
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 20px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

.asset-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, transparent 0%, var(--canvas-hairline-soft) 100%);
  opacity: 0;
  transition: opacity 0.3s;
}

.asset-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.asset-card:hover::before {
  opacity: 1;
}

/* 비활성 카드도 .asset-cards(flex, gap 16px) 안에 들어와 순서 배치를 받는다.
   상단에 올 수도 있어 margin-top 대신 컨테이너 gap 으로 간격을 맞춘다. */
.asset-card.disabled {
  background: color-mix(in srgb, var(--canvas-card-start) 50%, transparent);
  cursor: default;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 80px;
}

.asset-card.disabled:hover {
  transform: none;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

.disabled-text {
  font-size: var(--font-size-base);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

/* 채권·코인 카드는 카드 전체가 아니라 각 행이 링크다(행마다 다른 상세로 간다). */
.asset-card.bonds,
.asset-card.coins {
  cursor: default;
}

.asset-card.bonds:hover,
.asset-card.coins:hover {
  transform: none;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

/* 코인 카드 — 목록은 .bond-lot* 를 그대로 재사용한다(같은 모양의 "누르면 상세" 행) */
.coin-guide {
  padding: 14px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
  text-align: center;
}

.coin-guide-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.coin-guide-btn {
  margin-top: 12px;
  padding: 10px 20px;
  border: none;
  border-radius: 12px;
  background: #F59E0B;
  color: var(--color-text-inverse);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
}

.coin-krw-note {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);
}

.coin-search-btn {
  width: 100%;
  margin-top: 12px;
  padding: 10px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
}

.bond-empty {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
  text-align: center;
}

.bond-lot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bond-lot {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 12px;
  border: none;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition: background 0.2s;
}

.bond-lot:hover {
  background: var(--canvas-hairline-soft);
}

.bond-lot-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.bond-lot-name {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bond-lot-sub {
  font-size: 11px;
  color: var(--color-text-secondary);
}

.bond-lot-amount {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  white-space: nowrap;
}

.bond-lot-arrow {
  font-size: 18px;
  color: var(--color-text-tertiary);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.card-icon {
  font-size: 32px;
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.2));
}

.card-title-group {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-title {
  font-size: 18px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.card-percentage {
  font-size: 12px;
  color: var(--color-text-secondary);
  background: var(--canvas-hairline-soft);
  padding: 4px 10px;
  border-radius: 10px;
  font-weight: var(--font-weight-semibold);
}

.card-arrow {
  font-size: 20px;
  color: var(--color-text-tertiary);
  transition: transform 0.3s;
}

.asset-card:hover .card-arrow {
  transform: translateX(4px);
  color: var(--color-text-secondary);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-value-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.value-label {
  font-size: 12px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

.value-amount {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.unit {
  font-size: 18px;
  color: var(--color-text-secondary);
  margin-left: 4px;
}

.card-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.stat-label {
  font-size: 11px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.stat-value {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
}

.stat-value.positive {
  color: #10B981;
}

.stat-value.negative {
  color: #EF4444;
}

.card-indicator {
  position: absolute;
  top: 0;
  left: 0;
  width: 4px;
  height: 100%;
  border-radius: 20px 0 0 20px;
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

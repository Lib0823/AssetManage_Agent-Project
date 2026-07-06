<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { isKisOutageError } from '@/utils/kisStatus'
import { Doughnut } from 'vue-chartjs'
import {
  Chart as ChartJS,
  ArcElement,
  Tooltip,
  Legend
} from 'chart.js'
import { assetApi } from '@/services/api'
import { logger } from '@/utils/logger'

ChartJS.register(ArcElement, Tooltip, Legend)

const router = useRouter()

const loading = ref(false)
const kisUnavailable = ref(false)

// 자산 요약 (실데이터로 채움). 채권/코인은 추후 지원 → 0.
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

const loadAssets = async () => {
  loading.value = true
  kisUnavailable.value = false
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
    // 수익률(%) → 소수 비율로 변환 (formatPercent가 *100 함)
    const stockProfitRatePct = pick(summaryRow, ['asst_icdc_erng_rt', 'evlu_pfls_rt'])
    const stockChangePercent = stockProfitRatePct / 100

    // output2에 종목평가금액이 없으면 보유종목 평가금액 합으로 보강
    let stocksAmount = stockEvalAmount
    if (stocksAmount === 0 && Array.isArray(holdings.output1)) {
      stocksAmount = holdings.output1.reduce((sum, item) => sum + pick(item, ['evlu_amt']), 0)
    }

    const totalAsset = cashAmount + stocksAmount

    assetSummary.value = {
      totalAsset,
      // 총자산 전일대비 추세 엔드포인트 없음 → 주식 평가손익을 총 변동으로 표시
      totalChange: stockProfit,
      changePercent: totalAsset > 0 ? stockProfit / totalAsset : 0,
      updatedAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
      breakdown: {
        cash: { amount: cashAmount, change: 0, changePercent: 0 },
        stocks: { amount: stocksAmount, change: stockProfit, changePercent: stockChangePercent },
        bonds: { amount: 0, change: 0, changePercent: 0 },
        coins: { amount: 0, change: 0, changePercent: 0 }
      }
    }
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

const goToDetail = (type) => {
  router.push({
    path: '/assets/detail',
    query: {
      main: type,
      // 해외 주식은 추후 지원 → 국내 탭으로 진입
      sub: type === 'stocks' ? 'domestic' : undefined
    }
  })
}

const handleRefresh = () => {
  loadAssets()
}
</script>

<template>
  <div class="assets-screen">
    <AppHeader title="자산 정보" showIcon icon="assets" show-kis-mode />

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

      <!-- Asset Cards -->
      <div v-if="!kisUnavailable" class="asset-cards">
        <!-- Cash -->
        <section class="asset-card cash" @click="goToDetail('cash')">
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
        <section class="asset-card stocks" @click="goToDetail('stocks')">
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
      </div>

      <!-- Bonds (추후 지원) -->
      <section class="asset-card disabled">
        <div class="disabled-text">채권 (추후 지원)</div>
      </section>

      <!-- Coins (추후 지원) -->
      <section class="asset-card disabled">
        <div class="disabled-text">코인 (추후 지원)</div>
      </section>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.assets-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0F172A 0%, #1E293B 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.assets-screen :deep(.app-header) {
  background: #0F172A;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
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
  color: #94A3B8;
  cursor: pointer;
  transition: all 0.3s;
}

.refresh-button:hover {
  color: #F1F5F9;
  transform: rotate(180deg);
}

.refresh-button:active {
  transform: rotate(180deg) scale(0.9);
}

.update-time {
  font-size: 11px;
  color: #94A3B8;
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.total-section {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: 24px;
  padding: 24px;
  margin-bottom: var(--spacing-xl);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
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
  color: #F1F5F9;
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
  background: rgba(255, 255, 255, 0.05);
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
  color: #F1F5F9;
  margin-bottom: 24px;
  letter-spacing: -0.02em;
}

.currency {
  font-size: 24px;
  font-weight: var(--font-weight-normal);
  color: #94A3B8;
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
  color: #94A3B8;
  margin-bottom: 4px;
}

.center-value {
  font-size: 20px;
  font-weight: var(--font-weight-bold);
  color: #F1F5F9;
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
  background: rgba(255, 255, 255, 0.03);
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
  color: #F1F5F9;
  font-weight: var(--font-weight-medium);
}

.legend-percent {
  font-size: 12px;
  color: #94A3B8;
  background: rgba(255, 255, 255, 0.05);
  padding: 2px 8px;
  border-radius: 8px;
}

.legend-value {
  font-size: 14px;
  color: #F1F5F9;
  font-weight: var(--font-weight-semibold);
}

/* Asset Cards */
.asset-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.asset-card {
  position: relative;
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: 20px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

.asset-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, transparent 0%, rgba(255, 255, 255, 0.05) 100%);
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

.asset-card.disabled {
  background: rgba(30, 41, 59, 0.5);
  cursor: default;
  display: flex;
  margin-top: 10px;
  align-items: center;
  justify-content: center;
  min-height: 80px;
}

.asset-card.disabled:hover {
  transform: none;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

.disabled-text {
  font-size: var(--font-size-base);
  color: #64748B;
  font-weight: var(--font-weight-medium);
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
  color: #F1F5F9;
}

.card-percentage {
  font-size: 12px;
  color: #94A3B8;
  background: rgba(255, 255, 255, 0.08);
  padding: 4px 10px;
  border-radius: 10px;
  font-weight: var(--font-weight-semibold);
}

.card-arrow {
  font-size: 20px;
  color: #64748B;
  transition: transform 0.3s;
}

.asset-card:hover .card-arrow {
  transform: translateX(4px);
  color: #94A3B8;
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
  color: #94A3B8;
  font-weight: var(--font-weight-medium);
}

.value-amount {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: #F1F5F9;
  letter-spacing: -0.02em;
}

.unit {
  font-size: 18px;
  color: #94A3B8;
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
  background: rgba(255, 255, 255, 0.03);
  border-radius: 12px;
}

.stat-label {
  font-size: 11px;
  color: #94A3B8;
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

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { marketAnalysisApi } from '@/services/api'

const router = useRouter()

// 분석 날짜
const analysisDate = ref(null)
const analysisDateStr = ref('')

// Loading state
const loading = ref(true)
const error = ref(null)

// TOP3 toggle state
const activeTop3 = ref('buy') // 'buy' or 'sell'

// KOSPI 정보
const kospiInfo = ref({
  index: null,
  change_rate: null,
  volume: null
})

// 시장 수급 정보 (합계)
const supplyDemand = ref({
  foreign_net_buy: null,
  institutional_net_buy: null
})

// 30종목 히트맵 통계
const heatmapStats = ref({
  totalStocks: 30,
  buyCandidate: 0,
  sellCandidate: 0,
  neutral: 0
})

// 히트맵 상세 데이터 (백엔드에서 scaler_score DESC 정렬됨)
const heatmapData = ref([])

// 백엔드가 제공하는 summary (옵셔널)
const heatmapSummary = ref(null)

// 클라이언트에서 계산한 인사이트 (summary fallback)
const heatmapInsights = ref({
  avgForeignNetBuy: 0,
  avgInstitutionalNetBuy: 0,
  avgSentiment: 0,
  topStock: { name: '', score: 0 }
})

// Gemini AI 매수 TOP3
const buyTop3 = ref([])

// Gemini AI 매도 TOP3
const sellTop3 = ref([])

// 시장 전반 감성분석 데이터 (snake_case 통일)
const marketSentiment = ref({
  score: 0,
  label: '중립',
  distribution: {
    positive: { count: 0, percent: 0, color: '#f87171' },
    neutral: { count: 0, percent: 0, color: '#4a5568' },
    negative: { count: 0, percent: 0, color: '#60a5fa' }
  },
  time_range: '전날 18:00 — 당일 08:50',
  sources: '한경 · 매경 · 연합'
})

// 5일 예측 전망 (Prophet 기반)
const forecastOutlook = ref(null)

// 펀더멘탈 분석 (DART 기반)
const financialHealth = ref(null)

// 수급 매트릭스 (스마트머니 4분면)
const smartMoneyFlow = ref(null)

// D+1~D+5 시장 평균 예측 추이
const marketForecastTrend = ref(null)

// 히트맵 전체 보기 토글 (기본: 상위 10종목 / 펼치면 최대 30종목)
const showAllHeatmap = ref(false)

// 히트맵 매트릭스 표시용 (기본 10종목, 펼치면 전체 최대 30종목)
const topHeatmapStocks = computed(() =>
  heatmapData.value.slice(0, showAllHeatmap.value ? 30 : 10)
)

// 현재 화면에 표시 중인 히트맵 종목 수
const shownHeatmapCount = computed(() => topHeatmapStocks.value.length)

const toggleHeatmap = () => {
  showAllHeatmap.value = !showAllHeatmap.value
}

// 히트맵 매트릭스의 피처 컬럼 정의
const heatmapFeatures = [
  { key: 'foreign_net_buy', label: '외국인', format: 'money' },
  { key: 'institutional_net_buy', label: '기관', format: 'money' },
  { key: 'sentiment_score', label: '감성', format: 'decimal' },
  { key: 'expected_return_5d', label: '5일 전망', format: 'expected_return' },
  { key: 'vol_avg_multiple', label: '거래량', format: 'multiple' }
]

// Fetch market data
const fetchMarketData = async () => {
  try {
    loading.value = true
    error.value = null

    // 1. Get latest analysis date first
    const latestDateResponse = await marketAnalysisApi.getLatestDate()
    const latestDate = latestDateResponse.data.latest_date

    if (!latestDate) {
      error.value = '분석 데이터가 없습니다.'
      loading.value = false
      return
    }

    analysisDate.value = latestDate

    // Format date for display (handle ISO date string format)
    const dateObj = new Date(latestDate + 'T00:00:00')
    analysisDateStr.value = `${dateObj.getFullYear()}.${String(dateObj.getMonth() + 1).padStart(2, '0')}.${String(dateObj.getDate()).padStart(2, '0')}`

    // 2. Fetch all market data in parallel using the latest date
    const [summaryResponse, sentimentResponse, decisionsResponse, heatmapResponse] = await Promise.all([
      marketAnalysisApi.getSummary(latestDate),
      marketAnalysisApi.getSentiment(latestDate),
      marketAnalysisApi.getDecisions(latestDate),
      marketAnalysisApi.getHeatmap(latestDate)
    ])

    // Process summary data (KOSPI + statistics + supply_demand)
    if (summaryResponse && summaryResponse.data) {
      const summary = summaryResponse.data

      // KOSPI 정보
      if (summary.kospi) {
        kospiInfo.value = {
          index: summary.kospi.index ?? null,
          change_rate: summary.kospi.change_rate ?? null,
          volume: summary.kospi.volume ?? null
        }
      }

      // 통계 정보
      if (summary.statistics) {
        heatmapStats.value = {
          totalStocks: summary.statistics.total ?? 30,
          buyCandidate: summary.statistics.buy_candidate ?? 0,
          sellCandidate: summary.statistics.sell_candidate ?? 0,
          neutral: summary.statistics.neutral ?? 0
        }
      }

      // 수급 정보
      if (summary.supply_demand) {
        supplyDemand.value = {
          foreign_net_buy: summary.supply_demand.foreign_net_buy ?? null,
          institutional_net_buy: summary.supply_demand.institutional_net_buy ?? null
        }
      }
    } else {
      console.warn('Market summary data is empty for date:', latestDate)
    }

    // Process heatmap data
    if (heatmapResponse && heatmapResponse.data && heatmapResponse.data.stocks) {
      const stocks = heatmapResponse.data.stocks
      heatmapData.value = stocks

      // 백엔드 summary 활용 (옵셔널)
      heatmapSummary.value = heatmapResponse.data.summary || null

      // 신규: 5일 예측 전망 / 펀더멘탈 분석 / 수급 매트릭스 / 5일 평균 예측 추이
      if (heatmapResponse.data.summary) {
        forecastOutlook.value = heatmapResponse.data.summary.forecast_outlook || null
        financialHealth.value = heatmapResponse.data.summary.financial_health || null
        smartMoneyFlow.value = heatmapResponse.data.summary.smart_money_flow || null
        marketForecastTrend.value = heatmapResponse.data.summary.market_forecast_trend || null
      }

      // Calculate insights (fallback when summary missing)
      if (stocks.length > 0) {
        const totalForeignNetBuy = stocks.reduce((sum, s) => sum + (s.foreign_net_buy || 0), 0)
        const totalInstitutionalNetBuy = stocks.reduce((sum, s) => sum + (s.institutional_net_buy || 0), 0)
        const totalSentiment = stocks.reduce((sum, s) => sum + (s.sentiment_score || 0), 0)

        heatmapInsights.value.avgForeignNetBuy = Math.round(totalForeignNetBuy / stocks.length)
        heatmapInsights.value.avgInstitutionalNetBuy = Math.round(totalInstitutionalNetBuy / stocks.length)
        heatmapInsights.value.avgSentiment = Number((totalSentiment / stocks.length).toFixed(2))

        // Find top stock by combined positive factors
        let topStock = stocks[0]
        let maxScore = 0

        stocks.forEach(stock => {
          const score = (
            (stock.foreign_net_buy > 0 ? 1 : 0) +
            (stock.institutional_net_buy > 0 ? 1 : 0) +
            (stock.sentiment_score > 0 ? 1 : 0) +
            (stock.price_trend > 0 ? 1 : 0) +
            (stock.volume_trend > 0 ? 1 : 0)
          )

          if (score > maxScore) {
            maxScore = score
            topStock = stock
          }
        })

        heatmapInsights.value.topStock = {
          name: topStock.stock_name,
          score: maxScore
        }
      }
    }

    // Process sentiment data (snake_case preserved as-is)
    if (sentimentResponse && sentimentResponse.data) {
      const sentiment = sentimentResponse.data
      marketSentiment.value = {
        score: sentiment.score ?? 0,
        label: sentiment.label ?? '중립',
        distribution: sentiment.distribution ?? marketSentiment.value.distribution,
        time_range: sentiment.time_range ?? '전날 18:00 — 당일 08:50',
        sources: sentiment.sources ?? '한경 · 매경 · 연합'
      }
    }

    // Process decisions data
    if (decisionsResponse && decisionsResponse.data) {
      const decisions = decisionsResponse.data

      const hasBuyData = (decisions.buy_top3 || []).some(s => s.stock_code !== null)
      const hasSellData = (decisions.sell_top3 || []).some(s => s.stock_code !== null)

      if (!hasBuyData && !hasSellData) {
        console.warn('AI trade decisions are empty for date:', latestDate)
      }

      buyTop3.value = (decisions.buy_top3 || []).map(stock => {
        return {
          rank: stock.rank,
          icon: getStockIcon(stock.stock_name),
          iconBg: getIconBg(stock.rank),
          iconColor: getIconColor(stock.rank),
          name: stock.stock_name,
          reason: stock.reason || '',
          isEmpty: !stock.stock_code
        }
      })

      sellTop3.value = (decisions.sell_top3 || []).map(stock => {
        return {
          rank: stock.rank,
          icon: getStockIcon(stock.stock_name),
          iconBg: getIconBg(stock.rank),
          iconColor: getIconColor(stock.rank),
          name: stock.stock_name,
          reason: stock.reason || '',
          isEmpty: !stock.stock_code
        }
      })
    }

  } catch (err) {
    console.error('Failed to fetch market data:', err)
    error.value = '시장 데이터를 불러올 수 없습니다.'
  } finally {
    loading.value = false
  }
}

// Helper functions
const getStockIcon = (name) => {
  if (!name || name === '해당 없음') return '—'
  return name.substring(0, 2)
}

const getIconBg = (rank) => {
  const colors = [
    'rgba(248,113,113,.12)',
    'rgba(251,191,36,.12)',
    'rgba(52,211,153,.12)'
  ]
  return colors[rank - 1] || 'var(--color-bg-tertiary)'
}

const getIconColor = (rank) => {
  const colors = ['#f87171', '#fbbf24', '#34d399']
  return colors[rank - 1] || 'var(--color-text-tertiary)'
}

const toggleTop3 = (type) => {
  activeTop3.value = type
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '—'
  if (num === 0) return '0'
  const absNum = Math.abs(num)
  if (absNum >= 100000000) {
    return `${(num / 100000000).toFixed(1)}억`
  } else if (absNum >= 10000) {
    return `${(num / 10000).toFixed(1)}만`
  }
  return num.toLocaleString()
}

const formatKospiIndex = (value) => {
  if (!Number.isFinite(value)) return '—'
  return value.toLocaleString('ko-KR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}

const formatVolume = (value) => {
  if (value === null || value === undefined) return '—'
  const absNum = Math.abs(value)
  if (absNum >= 100000000) {
    return `${(value / 100000000).toFixed(1)}억`
  } else if (absNum >= 10000) {
    return `${(value / 10000).toFixed(1)}만`
  }
  return value.toLocaleString()
}

// 히트맵 셀 색상 계산
const getFeatureColor = (value, feature) => {
  if (value === null || value === undefined || isNaN(value)) {
    return 'rgba(128,128,128,0.1)'
  }

  let normalized
  if (feature === 'foreign_net_buy' || feature === 'institutional_net_buy') {
    normalized = Math.tanh(value / 100000000)
  } else if (feature === 'sentiment_score') {
    normalized = Math.max(-1, Math.min(1, value))
  } else if (feature === 'expected_return_5d') {
    normalized = Math.tanh(value / 10)
  } else if (feature === 'price_trend' || feature === 'volume_trend') {
    normalized = Math.tanh(value * 10)
  } else if (feature === 'vol_avg_multiple') {
    normalized = Math.tanh((value - 1) * 2)
  } else if (feature === 'morning_return') {
    normalized = Math.tanh(value / 3)
  } else {
    normalized = 0
  }

  const intensity = Math.min(Math.abs(normalized), 1) * 0.7 + 0.1
  if (normalized > 0) {
    return `rgba(248, 113, 113, ${intensity})`
  }
  return `rgba(96, 165, 250, ${intensity})`
}

// 히트맵 셀 표시 텍스트 포맷
const formatCellValue = (value, format) => {
  if (value === null || value === undefined || isNaN(value)) return '—'

  if (format === 'money') {
    const absNum = Math.abs(value)
    if (absNum >= 100000000) {
      return `${value > 0 ? '+' : ''}${(value / 100000000).toFixed(1)}억`
    } else if (absNum >= 10000) {
      return `${value > 0 ? '+' : ''}${(value / 10000).toFixed(0)}만`
    }
    return value.toLocaleString()
  } else if (format === 'decimal') {
    return value.toFixed(2)
  } else if (format === 'percent') {
    return `${value > 0 ? '+' : ''}${(value * 100).toFixed(1)}%`
  } else if (format === 'expected_return') {
    return `${value > 0 ? '+' : ''}${value.toFixed(1)}%`
  } else if (format === 'multiple') {
    return `${value.toFixed(2)}x`
  }
  return String(value)
}

// 시장 감성 점수 색상/포맷 (양수=빨강, 음수=파랑, 0=회색)
const sentimentColorClass = computed(() => {
  const score = Number(marketSentiment.value.score)
  if (!Number.isFinite(score)) return 'sentiment-zero'
  if (score > 0.1) return 'sentiment-positive'
  if (score < -0.1) return 'sentiment-negative'
  return 'sentiment-zero'
})

const formattedSentimentScore = computed(() => {
  const score = Number(marketSentiment.value.score)
  if (!Number.isFinite(score)) return '0.00'
  return (score > 0 ? '+' : '') + score.toFixed(2)
})

const sentimentBadgeClass = computed(() => {
  const label = marketSentiment.value.label
  if (label && label.includes('긍정')) return 'badge-positive'
  if (label && label.includes('부정')) return 'badge-negative'
  return 'badge-neutral'
})

// 시장 감성 분포 합계 (빈 상태 판별용)
const sentimentDistributionTotal = computed(() => {
  const d = marketSentiment.value.distribution || {}
  return (d.positive?.count || 0) + (d.neutral?.count || 0) + (d.negative?.count || 0)
})

// 감성 점수 게이지 위치 (-1.0 ~ +1.0 축에서 0~100% 좌표)
const sentimentGaugePercent = computed(() => {
  const score = Number(marketSentiment.value.score)
  if (!Number.isFinite(score)) return 50
  const clamped = Math.max(-1, Math.min(1, score))
  return ((clamped + 1) / 2) * 100
})

// 감성 점수 한 줄 해석
const sentimentInterpretation = computed(() => {
  const score = Number(marketSentiment.value.score)
  if (!Number.isFinite(score)) return '중립적 분위기'
  if (score > 0.1) return '긍정적 분위기'
  if (score < -0.1) return '부정적 분위기'
  return '중립적 분위기'
})

// 5일 예측 전망 헬퍼 (null-safe)
const forecastUpPercent = computed(() => {
  const f = forecastOutlook.value
  if (!f) return 0
  const up = f.rising_count ?? 0
  const dn = f.falling_count ?? 0
  const total = up + dn
  return total === 0 ? 0 : Math.round((up / total) * 100)
})
const forecastDnPercent = computed(() => 100 - forecastUpPercent.value)

const uncertaintyClass = computed(() => {
  const level = forecastOutlook.value?.uncertainty_level
  if (level === '낮음') return 'low'
  if (level === '높음') return 'high'
  return 'medium'
})

// 매수 시그널 강도 (상승 vs 하락 비율 기반)
const forecastSignalStrength = computed(() => {
  const f = forecastOutlook.value
  if (!f) return '—'
  const up = f.rising_count ?? 0
  const dn = f.falling_count ?? 0
  if (up + dn === 0) return '—'
  const ratio = (up / (up + dn)) * 100
  if (ratio >= 70) return '강세'
  if (ratio >= 55) return '우세'
  if (ratio >= 45) return '중립'
  if (ratio >= 30) return '약세'
  return '매도'
})

const forecastSignalClass = computed(() => {
  const f = forecastOutlook.value
  if (!f) return ''
  const up = f.rising_count ?? 0
  const dn = f.falling_count ?? 0
  if (up + dn === 0) return ''
  return up > dn ? 'up' : up < dn ? 'dn' : ''
})

// 수급 매트릭스 헬퍼
const signalLabel = computed(() => {
  const sig = smartMoneyFlow.value?.dominant_signal
  if (sig === 'BOTH_BUY') return '매수 우세'
  if (sig === 'BOTH_SELL') return '매도 우세'
  if (sig === 'MIXED') return '혼조'
  return '—'
})

const signalBadgeClass = computed(() => {
  const sig = smartMoneyFlow.value?.dominant_signal
  if (sig === 'BOTH_BUY') return 'signal-buy'
  if (sig === 'BOTH_SELL') return 'signal-sell'
  return 'signal-neutral'
})

// 5일 평균 예측 추이 헬퍼
const forecastChartPoints = computed(() => {
  const t = marketForecastTrend.value
  if (!t) return []
  return [
    { day: 'D+1', value: Number(t.d1_return_pct ?? 0) },
    { day: 'D+2', value: Number(t.d2_return_pct ?? 0) },
    { day: 'D+3', value: Number(t.d3_return_pct ?? 0) },
    { day: 'D+4', value: Number(t.d4_return_pct ?? 0) },
    { day: 'D+5', value: Number(t.d5_return_pct ?? 0) }
  ]
})

// 막대 스케일링: 실제 |값| 최대치를 분모로 사용 → 저변동성 날에도
// 가장 큰 값이 (거의) 최대 높이로 표시된다. 전부 0이면 0 반환 (divide-by-zero 방지).
const forecastChartMaxAbs = computed(() => {
  const points = forecastChartPoints.value
  if (points.length === 0) return 0
  return Math.max(0, ...points.map(p => Math.abs(p.value)))
})

// 막대 높이(%) 계산: 분모가 0이면 0% (평평한 기준선). D+1은 항상 0이라 기준점.
const forecastBarHeight = (value) => {
  const max = forecastChartMaxAbs.value
  if (max <= 0) return 0
  return Math.min(100, (Math.abs(value) / max) * 100)
}

const trendDirectionClass = computed(() => {
  const dir = marketForecastTrend.value?.trend_direction
  if (dir === '상승세') return 'up'
  if (dir === '하락세') return 'dn'
  return 'neutral'
})

// 값이 이미 % 단위 (예: 2.34 = +2.34%)
const formatTrend = (value) => {
  if (value === null || value === undefined) return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return `${num > 0 ? '+' : ''}${num.toFixed(2)}%`
}

const formatPercent = (value) => {
  if (value === null || value === undefined) return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return `${num > 0 ? '+' : ''}${num.toFixed(2)}%`
}

const getTrendClass = (value) => {
  if (value === null || value === undefined) return ''
  const num = Number(value)
  if (!Number.isFinite(num) || num === 0) return ''
  return num > 0 ? 'up' : 'dn'
}

// 펀더멘탈 분석 헬퍼
const formatFinancial = (value) => {
  if (value === null || value === undefined) return '—'
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num.toFixed(1)
}

// 표시용 인사이트 (백엔드 summary 우선, fallback은 클라이언트 계산)
const displayInsights = computed(() => {
  if (heatmapSummary.value) {
    return {
      avgForeignNetBuy: heatmapSummary.value.avg_foreign_net_buy ?? 0,
      avgInstitutionalNetBuy: heatmapSummary.value.avg_institutional_net_buy ?? 0,
      avgSentiment: Number((heatmapSummary.value.avg_sentiment_score ?? 0).toFixed(2)),
      positiveSentimentCount: heatmapSummary.value.positive_sentiment_count ?? null,
      negativeSentimentCount: heatmapSummary.value.negative_sentiment_count ?? null,
      positiveTrendCount: heatmapSummary.value.positive_trend_count ?? null,
      topStock: {
        name: heatmapSummary.value.top_stock?.stock_name ?? '',
        score: heatmapSummary.value.top_stock?.positive_features ?? 0
      },
      source: 'backend'
    }
  }
  return {
    avgForeignNetBuy: heatmapInsights.value.avgForeignNetBuy,
    avgInstitutionalNetBuy: heatmapInsights.value.avgInstitutionalNetBuy,
    avgSentiment: heatmapInsights.value.avgSentiment,
    positiveSentimentCount: null,
    negativeSentimentCount: null,
    positiveTrendCount: null,
    topStock: heatmapInsights.value.topStock,
    source: 'client'
  }
})

// 감성 종목 분포 (중립 밴드 적용): |점수| < 0.05 → 중립.
// 백엔드 summary는 >0/<0 단순 분류라 -0.012 같은 사실상 중립값을 전부 '부정'으로 집계해
// "0/30"처럼 오해를 부른다. 히트맵 종목 데이터로 긍정/중립/부정을 직접 계산해 일관 표시.
const SENTIMENT_NEUTRAL_BAND = 0.05
const sentimentBreakdown = computed(() => {
  const stocks = heatmapData.value.filter(
    s => s.sentiment_score !== null && s.sentiment_score !== undefined
  )
  let positive = 0
  let neutral = 0
  let negative = 0
  stocks.forEach(s => {
    const v = Number(s.sentiment_score)
    if (!Number.isFinite(v) || Math.abs(v) < SENTIMENT_NEUTRAL_BAND) neutral++
    else if (v > 0) positive++
    else negative++
  })
  return { positive, neutral, negative, total: stocks.length }
})

const hasHeatmapData = computed(() => heatmapData.value.length > 0)

const goBack = () => {
  router.push('/bot')
}

onMounted(() => {
  fetchMarketData()
})
</script>

<template>
  <div class="market-analysis-screen dark-canvas">
    <AppHeader title="종합분석" :showBack="true" @back="goBack">
      <template #right>
        <div class="header-meta">
          <div class="live-dot"></div>
          <div class="header-date">{{ analysisDateStr || '조회중...' }}</div>
        </div>
      </template>
    </AppHeader>

    <!-- 로딩 / 에러 / 빈 데이터 처리 -->
    <div v-if="loading" class="state-wrapper">
      <div class="state-message">분석 데이터를 불러오는 중...</div>
    </div>

    <div v-else-if="error" class="state-wrapper">
      <div class="state-message error">{{ error }}</div>
    </div>

    <div v-else class="content">
      <!-- ⓘ KOSPI 시장 개요 -->
      <div class="analysis-section">
        <div class="section-label">KOSPI 시장 개요</div>
        <div class="section-card">
          <div class="kospi-block">
            <div class="kospi-main">
              <div class="kospi-label">KOSPI 지수</div>
              <div class="kospi-value">{{ formatKospiIndex(kospiInfo.index) }}</div>
              <div
                v-if="Number.isFinite(kospiInfo.change_rate)"
                :class="['kospi-change', kospiInfo.change_rate >= 0 ? 'up' : 'dn']"
              >
                {{ kospiInfo.change_rate > 0 ? '+' : '' }}{{ kospiInfo.change_rate.toFixed(2) }}%
              </div>
            </div>

            <div class="kospi-side">
              <div class="kospi-side-row">
                <span class="side-label">거래량</span>
                <span class="side-value">{{ formatVolume(kospiInfo.volume) }}</span>
              </div>
              <div class="kospi-side-row">
                <span class="side-label">외국인 순매수</span>
                <span
                  :class="['side-value', supplyDemand.foreign_net_buy === null ? '' : supplyDemand.foreign_net_buy >= 0 ? 'up' : 'dn']"
                >
                  {{ supplyDemand.foreign_net_buy === null ? '—' : (supplyDemand.foreign_net_buy > 0 ? '+' : '') + formatNumber(supplyDemand.foreign_net_buy) + '원' }}
                </span>
              </div>
              <div class="kospi-side-row">
                <span class="side-label">기관 순매수</span>
                <span
                  :class="['side-value', supplyDemand.institutional_net_buy === null ? '' : supplyDemand.institutional_net_buy >= 0 ? 'up' : 'dn']"
                >
                  {{ supplyDemand.institutional_net_buy === null ? '—' : (supplyDemand.institutional_net_buy > 0 ? '+' : '') + formatNumber(supplyDemand.institutional_net_buy) + '원' }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ① 피처 히트맵 -->
      <div class="analysis-section">
        <div class="section-label">피처 히트맵</div>
        <div class="section-card">
          <div v-if="!hasHeatmapData" class="empty-block">
            분석 데이터가 없습니다
          </div>

          <template v-else>
            <!-- 실제 히트맵 매트릭스 (Top 10 × 5 피처) -->
            <div class="heatmap-section">
              <div class="heatmap-header-bar">
                <span class="heatmap-title">상위 {{ shownHeatmapCount }}종목 × 5개 피처</span>
                <span class="heatmap-legend">
                  <span class="legend-chip up"></span> 양호
                  <span class="legend-chip dn"></span> 부진
                </span>
              </div>

              <div class="heatmap-matrix">
                <!-- Header row -->
                <div class="heatmap-header">종목</div>
                <div
                  v-for="feature in heatmapFeatures"
                  :key="feature.key"
                  class="heatmap-header"
                >
                  {{ feature.label }}
                </div>

                <!-- Stock rows -->
                <template v-for="stock in topHeatmapStocks" :key="stock.stock_code">
                  <div class="heatmap-stock-name" :title="stock.stock_name">
                    {{ stock.stock_name }}
                  </div>
                  <div
                    v-for="feature in heatmapFeatures"
                    :key="`${stock.stock_code}-${feature.key}`"
                    class="heatmap-cell"
                    :style="{ background: getFeatureColor(stock[feature.key], feature.key) }"
                  >
                    {{ formatCellValue(stock[feature.key], feature.format) }}
                  </div>
                </template>
              </div>

              <button
                v-if="heatmapData.length > 10"
                class="heatmap-toggle-btn"
                @click="toggleHeatmap"
              >
                {{ showAllHeatmap ? '접기' : `전체 ${heatmapData.length}종목 보기` }}
              </button>
            </div>
          </template>

          <!-- 통계 그리드 -->
          <div class="stats-grid">
            <div class="stat-box">
              <div class="stat-label">분석<br>종목</div>
              <div class="stat-value nt">{{ heatmapStats.totalStocks }}</div>
            </div>
            <div class="stat-box">
              <div class="stat-label">매수<br>후보</div>
              <div class="stat-value up">{{ heatmapStats.buyCandidate }}</div>
            </div>
            <div class="stat-box">
              <div class="stat-label">매도<br>후보</div>
              <div class="stat-value dn">{{ heatmapStats.sellCandidate }}</div>
            </div>
            <div class="stat-box">
              <div class="stat-label">중립</div>
              <div class="stat-value neutral">{{ heatmapStats.neutral }}</div>
            </div>
          </div>

          <!-- 통계 범례 -->
          <div class="stats-caption">
            매수·매도 후보 = AI가 선정한 TOP3 · 중립 = 미선정 종목
          </div>

          <!-- 인사이트 (백엔드 summary 또는 클라이언트 계산) -->
          <div class="insights-section">
            <div class="insight-row">
              <span class="insight-label">평균 외국인 순매수</span>
              <span :class="['insight-value', displayInsights.avgForeignNetBuy > 0 ? 'up' : displayInsights.avgForeignNetBuy < 0 ? 'dn' : '']">
                {{ displayInsights.avgForeignNetBuy > 0 ? '+' : '' }}{{ formatNumber(displayInsights.avgForeignNetBuy) }}원
              </span>
            </div>
            <div class="insight-row">
              <span class="insight-label">평균 기관 순매수</span>
              <span :class="['insight-value', displayInsights.avgInstitutionalNetBuy > 0 ? 'up' : displayInsights.avgInstitutionalNetBuy < 0 ? 'dn' : '']">
                {{ displayInsights.avgInstitutionalNetBuy > 0 ? '+' : '' }}{{ formatNumber(displayInsights.avgInstitutionalNetBuy) }}원
              </span>
            </div>
            <div class="insight-row">
              <span class="insight-label">평균 감성 점수</span>
              <span :class="['insight-value', displayInsights.avgSentiment > 0 ? 'up' : displayInsights.avgSentiment < 0 ? 'dn' : '']">
                {{ displayInsights.avgSentiment > 0 ? '+' : '' }}{{ displayInsights.avgSentiment }}
              </span>
            </div>
            <div
              v-if="sentimentBreakdown.total > 0"
              class="insight-row"
            >
              <span class="insight-label">
                긍정/중립/부정 감성 종목
                <span class="insight-sub">감성점수 ±0.05 기준 (그 외 중립)</span>
              </span>
              <span class="insight-value">
                <span class="up">{{ sentimentBreakdown.positive }}</span>
                /
                <span class="nt">{{ sentimentBreakdown.neutral }}</span>
                /
                <span class="dn">{{ sentimentBreakdown.negative }}</span>
              </span>
            </div>
            <div
              v-if="displayInsights.positiveTrendCount !== null"
              class="insight-row"
            >
              <span class="insight-label">상승 추세 종목</span>
              <span class="insight-value up">{{ displayInsights.positiveTrendCount }}개</span>
            </div>
            <div class="insight-row highlight">
              <span class="insight-label">
                최고 점수 종목
                <span
                  v-if="displayInsights.topStock.score"
                  class="insight-sub"
                >5개 핵심 피처 중 {{ displayInsights.topStock.score }}개 양호</span>
              </span>
              <span class="insight-value highlight">
                {{ displayInsights.topStock.name || '분석중' }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- ② Gemini AI 매매 판단 -->
      <div class="analysis-section">
        <div class="section-label">AI 매매 판단</div>
        <div class="section-card">
          <!-- Toggle Buttons -->
          <div class="top3-toggle">
            <button
              :class="['toggle-btn', 'buy', { active: activeTop3 === 'buy' }]"
              @click="toggleTop3('buy')"
            >
              ▲ 매수 TOP3
            </button>
            <button
              :class="['toggle-btn', 'sell', { active: activeTop3 === 'sell' }]"
              @click="toggleTop3('sell')"
            >
              ▼ 매도 TOP3
            </button>
          </div>

          <!-- Buy TOP3 Panel -->
          <div v-show="activeTop3 === 'buy'" class="top3-panel">
            <div
              v-for="stock in buyTop3"
              :key="stock.rank"
              :class="['stock-row', { empty: stock.isEmpty }]"
            >
              <div class="rank-badge buy">{{ stock.rank ?? '—' }}</div>
              <div
                class="stock-icon"
                :style="{ background: stock.iconBg, color: stock.iconColor }"
              >
                {{ stock.icon }}
              </div>
              <div class="stock-info">
                <div class="stock-name">{{ stock.name }}</div>
                <div v-if="stock.reason" class="stock-reason">{{ stock.reason }}</div>
              </div>
            </div>
          </div>

          <!-- Sell TOP3 Panel -->
          <div v-show="activeTop3 === 'sell'" class="top3-panel">
            <div
              v-for="stock in sellTop3"
              :key="stock.rank"
              :class="['stock-row', { empty: stock.isEmpty }]"
            >
              <div class="rank-badge sell">{{ stock.rank ?? '—' }}</div>
              <div
                class="stock-icon"
                :style="{ background: stock.iconBg, color: stock.iconColor }"
              >
                {{ stock.icon }}
              </div>
              <div class="stock-info">
                <div class="stock-name">{{ stock.name }}</div>
                <div v-if="stock.reason" class="stock-reason">{{ stock.reason }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ③ 수급 매트릭스 (스마트머니 4분면) -->
      <div class="analysis-section">
        <div class="section-label">수급 매트릭스</div>
        <div class="section-card">
          <div class="smart-money-block">
            <!-- 설명 -->
            <div class="smart-money-intro">
              스마트머니 컨센서스는 외국인·기관이 같은 방향으로 매매하는 비율(의견 일치도)을 나타냅니다.
            </div>

            <!-- 컨센서스 헤더 -->
            <div class="consensus-header">
              <div>
                <div class="consensus-label">스마트머니 컨센서스</div>
                <div class="consensus-value">
                  {{ smartMoneyFlow?.consensus_pct ?? '—' }}<span v-if="smartMoneyFlow?.consensus_pct !== null && smartMoneyFlow?.consensus_pct !== undefined">%</span>
                </div>
              </div>
              <div :class="['signal-badge', signalBadgeClass]">
                {{ signalLabel }}
              </div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- 4분면 설명 -->
            <div class="quadrant-caption">
              동반 매수/매도 = 외인·기관 둘 다 같은 방향 · 디커플링 = 한쪽만 매수
            </div>

            <!-- 4분면 그리드 -->
            <div class="quadrant-grid">
              <!-- 좌상: 디커플링 A (기관만 매수) -->
              <div class="quadrant-cell decoupling">
                <div class="quadrant-axis">외국인 매도</div>
                <div class="quadrant-name">디커플링 A</div>
                <div class="quadrant-count">{{ smartMoneyFlow?.institutional_only_buy_count ?? '—' }}</div>
                <div class="quadrant-sub">기관 매수</div>
              </div>
              <!-- 우상: 동반 매수 (Q1) -->
              <div class="quadrant-cell both-buy">
                <div class="quadrant-axis"></div>
                <div class="quadrant-name">★ 동반 매수</div>
                <div class="quadrant-count up">{{ smartMoneyFlow?.both_buy_count ?? '—' }}</div>
                <div class="quadrant-sub">외인+기관</div>
              </div>
              <!-- 좌하: 동반 매도 (Q3) -->
              <div class="quadrant-cell both-sell">
                <div class="quadrant-axis"></div>
                <div class="quadrant-name">▼ 동반 매도</div>
                <div class="quadrant-count dn">{{ smartMoneyFlow?.both_sell_count ?? '—' }}</div>
                <div class="quadrant-sub">외인-기관</div>
              </div>
              <!-- 우하: 디커플링 B (외국인만 매수) -->
              <div class="quadrant-cell decoupling">
                <div class="quadrant-axis">외국인 매수</div>
                <div class="quadrant-name">디커플링 B</div>
                <div class="quadrant-count">{{ smartMoneyFlow?.foreign_only_buy_count ?? '—' }}</div>
                <div class="quadrant-sub">기관 매도</div>
              </div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- 스마트머니 TOP 종목 -->
            <div class="smart-money-top">
              <span class="smart-money-top-label">💎 스마트머니 TOP</span>
              <span class="smart-money-top-name">
                {{ smartMoneyFlow?.smart_money_top_stock?.stock_name || '—' }}
              </span>
              <span class="smart-money-top-value up">
                {{ smartMoneyFlow?.smart_money_top_stock ? '+' + formatNumber(smartMoneyFlow.smart_money_top_stock.combined_net_buy) + '원' : '—' }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- ④ 5일 예측 전망 (Prophet) -->
      <div class="analysis-section">
        <div class="section-label">5일 예측 전망</div>
        <div class="section-card">
          <div class="forecast-block">
            <!-- 상단: 상승/하락 분포 -->
            <div class="forecast-distribution">
              <div class="forecast-stat up">
                <div class="forecast-stat-label">상승 예측</div>
                <div class="forecast-stat-value">{{ forecastOutlook?.rising_count ?? 0 }}<span class="unit">종목</span></div>
              </div>
              <div class="forecast-divider"></div>
              <div class="forecast-stat dn">
                <div class="forecast-stat-label">하락 예측</div>
                <div class="forecast-stat-value">{{ forecastOutlook?.falling_count ?? 0 }}<span class="unit">종목</span></div>
              </div>
            </div>

            <!-- 분포 바 -->
            <div class="forecast-bar">
              <div class="forecast-bar-up" :style="{ width: forecastUpPercent + '%' }"></div>
              <div class="forecast-bar-dn" :style="{ width: forecastDnPercent + '%' }"></div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- 지표 그리드 -->
            <div class="forecast-metrics">
              <div class="metric-item">
                <div class="metric-label">5일 예상 수익률</div>
                <div :class="['metric-value', getTrendClass(forecastOutlook?.avg_expected_return_5d)]">
                  {{ formatTrend(forecastOutlook?.avg_expected_return_5d) }}
                </div>
              </div>
              <div class="metric-item">
                <div class="metric-label">매수 시그널</div>
                <div :class="['metric-value', forecastSignalClass]">
                  {{ forecastSignalStrength }}
                </div>
              </div>
              <div class="metric-item">
                <div class="metric-label">예측 변동성</div>
                <div :class="['metric-value uncertainty', uncertaintyClass]">
                  {{ forecastOutlook?.uncertainty_level || '—' }}
                  <span
                    v-if="forecastOutlook?.avg_uncertainty_pct !== null && forecastOutlook?.avg_uncertainty_pct !== undefined"
                    class="metric-sub"
                  >
                    ({{ formatPercent(forecastOutlook.avg_uncertainty_pct) }})
                  </span>
                </div>
              </div>
            </div>

            <!-- 최고 전망 종목 -->
            <div class="forecast-top">
              <span class="forecast-top-label">📈 최고 상승 전망</span>
              <span class="forecast-top-name">{{ forecastOutlook?.top_outlook_stock?.stock_name || '—' }}</span>
              <span class="forecast-top-value up">
                {{ forecastOutlook?.top_outlook_stock ? formatTrend(forecastOutlook.top_outlook_stock.expected_return_5d) : '—' }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- ⑤ D+1~D+5 시장 평균 예측 추이 -->
      <div class="analysis-section">
        <div class="section-label">5일 시장 평균 예측 추이</div>
        <div class="section-card">
          <div class="trend-block">
            <!-- 헤더: D+5 예상 수익률 + 추세 방향 -->
            <div class="trend-header">
              <div>
                <div class="trend-label">D+5 시장 평균 예상 수익률</div>
                <div :class="['trend-value', trendDirectionClass]">
                  {{ formatTrend(marketForecastTrend?.d5_return_pct) }}
                </div>
              </div>
              <div :class="['trend-badge', trendDirectionClass]">
                {{ marketForecastTrend?.trend_direction || '—' }}
              </div>
            </div>

            <!-- 신뢰구간 -->
            <div
              v-if="marketForecastTrend?.d5_upper_pct !== null && marketForecastTrend?.d5_upper_pct !== undefined"
              class="confidence-band"
            >
              <span class="confidence-label">95% 신뢰구간 (D+5)</span>
              <span class="confidence-range">
                {{ formatTrend(marketForecastTrend.d5_lower_pct) }}
                <span class="range-sep">~</span>
                {{ formatTrend(marketForecastTrend.d5_upper_pct) }}
              </span>
            </div>

            <div class="sentiment-divider"></div>

            <!-- Mini bar chart (5일 막대) -->
            <div class="trend-chart">
              <div
                v-for="point in forecastChartPoints"
                :key="point.day"
                class="trend-bar-col"
              >
                <div class="trend-bar-wrap">
                  <!-- 값이 0이면 기준선(D+1 baseline) → 얇은 틱으로 명확히 표시 -->
                  <div v-if="point.value === 0" class="trend-bar-baseline"></div>
                  <div
                    v-else
                    :class="['trend-bar', point.value > 0 ? 'up' : 'dn']"
                    :style="{ height: forecastBarHeight(point.value) + '%' }"
                  ></div>
                </div>
                <div :class="['trend-bar-value', point.value > 0 ? 'up' : point.value < 0 ? 'dn' : 'base']">
                  {{ point.value === 0 ? '기준' : (point.value > 0 ? '+' : '') + point.value.toFixed(2) + '%' }}
                </div>
                <div class="trend-bar-label">{{ point.day }}</div>
              </div>
            </div>

            <!-- 데이터 커버리지 -->
            <div class="trend-coverage">
              <span class="coverage-label">분석 종목 수</span>
              <span class="coverage-count">
                {{ marketForecastTrend?.data_count ?? '—' }}<span v-if="marketForecastTrend?.data_count" class="unit">/30종목</span>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- ⑥ 펀더멘탈 분석 (DART) -->
      <div class="analysis-section">
        <div class="section-label">펀더멘탈 분석</div>
        <div class="section-card">
          <div class="financial-block">
            <!-- 평균 지표 3개 -->
            <div class="financial-avg-grid">
              <div class="financial-avg-box">
                <div class="financial-avg-label">PER 중앙값</div>
                <div class="financial-avg-value">{{ formatFinancial(financialHealth?.avg_per) }}</div>
                <div class="financial-avg-sub">{{ financialHealth?.undervalued_count ?? 0 }}종목 저평가</div>
              </div>
              <div class="financial-avg-box">
                <div class="financial-avg-label">평균 ROE</div>
                <div class="financial-avg-value">{{ formatFinancial(financialHealth?.avg_roe) }}<span class="unit">%</span></div>
                <div class="financial-avg-sub">{{ financialHealth?.high_roe_count ?? 0 }}종목 우수</div>
              </div>
              <div class="financial-avg-box">
                <div class="financial-avg-label">평균 영업이익률</div>
                <div class="financial-avg-value">{{ formatFinancial(financialHealth?.avg_operating_margin) }}<span class="unit">%</span></div>
                <div class="financial-avg-sub">{{ financialHealth?.high_margin_count ?? 0 }}종목 우수</div>
              </div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- 우수 종목 강조 -->
            <div class="excellent-row">
              <div class="excellent-info">
                <div class="excellent-label">💎 펀더멘탈 우수 종목</div>
                <div class="excellent-sub">PER &lt; 15 · ROE &gt; 15% · 영업이익률 &gt; 10% 중 2개 이상</div>
              </div>
              <div class="excellent-count">
                {{ financialHealth?.excellent_count ?? 0 }}<span class="unit">종목</span>
              </div>
            </div>

            <!-- 데이터 커버리지 -->
            <div class="data-coverage">
              <span class="coverage-label">DART 데이터 커버리지</span>
              <div class="coverage-bar">
                <div class="coverage-fill" :style="{ width: (financialHealth?.data_coverage ?? 0) + '%' }"></div>
              </div>
              <span class="coverage-percent">{{ financialHealth?.data_coverage ?? 0 }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ⑤ 시장 전반 감성분석 -->
      <div class="analysis-section">
        <div class="section-label">시장 전반 감성분석</div>
        <div class="section-card">
          <div class="sentiment-block">
            <!-- Score Row -->
            <div class="sentiment-header">
              <div>
                <div class="sentiment-source">KR-FinBERT · RSS 피드 기반</div>
                <div class="sentiment-main">
                  <span class="sentiment-value" :class="sentimentColorClass">{{ formattedSentimentScore }}</span>
                  <span class="sentiment-label">시장 감성점수</span>
                </div>
              </div>
              <div class="sentiment-badge" :class="sentimentBadgeClass">{{ marketSentiment.label }}</div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- 감성 점수 게이지 (-1.0 ~ +1.0) + 해석 -->
            <div class="sentiment-gauge-section">
              <div class="sentiment-gauge-track">
                <div class="gauge-zero-line"></div>
                <div
                  class="gauge-marker"
                  :class="sentimentColorClass"
                  :style="{ left: sentimentGaugePercent + '%' }"
                ></div>
              </div>
              <div class="sentiment-gauge-scale">
                <span>-1.0</span>
                <span>0</span>
                <span>+1.0</span>
              </div>
              <div class="sentiment-interpretation" :class="sentimentColorClass">
                {{ sentimentInterpretation }}
              </div>
            </div>

            <div class="sentiment-divider"></div>

            <!-- Distribution Bar -->
            <div class="distribution-section">
              <div class="distribution-header">
                <span>종목 감성 분포 (30종목)</span>
              </div>

              <div v-if="sentimentDistributionTotal === 0" class="distribution-empty">
                분포 데이터 없음
              </div>

              <template v-else>
                <div class="distribution-bar">
                  <div
                    class="bar-segment positive"
                    :style="{ width: marketSentiment.distribution.positive.percent + '%' }"
                  ></div>
                  <div
                    class="bar-segment neutral"
                    :style="{ width: marketSentiment.distribution.neutral.percent + '%' }"
                  ></div>
                  <div
                    class="bar-segment negative"
                    :style="{ width: marketSentiment.distribution.negative.percent + '%' }"
                  ></div>
                </div>
                <div class="distribution-labels">
                  <span class="label-positive">
                    긍정 {{ marketSentiment.distribution.positive.count }}개 ({{ marketSentiment.distribution.positive.percent }}%)
                  </span>
                  <span class="label-neutral">
                    중립 {{ marketSentiment.distribution.neutral.count }}개 ({{ marketSentiment.distribution.neutral.percent }}%)
                  </span>
                  <span class="label-negative">
                    부정 {{ marketSentiment.distribution.negative.count }}개 ({{ marketSentiment.distribution.negative.percent }}%)
                  </span>
                </div>
              </template>
            </div>

            <div class="sentiment-divider"></div>

            <!-- Collection Info -->
            <div class="collection-info">
              <div class="info-item">
                <div class="info-label">수집 범위</div>
                <div class="info-value">{{ marketSentiment.time_range }}</div>
              </div>
              <div class="info-item align-right">
                <div class="info-label">뉴스 출처</div>
                <div class="info-value">{{ marketSentiment.sources }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.market-analysis-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0F172A 0%, #1E293B 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.market-analysis-screen :deep(.app-header) {
  background: #0F172A;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.header-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-stock-up);
  animation: pulse-dot 2s infinite;
}

@keyframes pulse-dot {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(0.8);
  }
}

.header-date {
  font-family: 'DM Mono', monospace;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  background: var(--color-bg-tertiary);
  border: 1px solid var(--color-border);
  padding: 3px 8px;
  border-radius: 6px;
}

.content {
  padding: var(--spacing-md) var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

/* State Wrapper */
.state-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: var(--spacing-lg);
}

.state-message {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.state-message.error {
  color: #f87171;
}

.empty-block {
  padding: 40px 16px;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: 12px;
}

/* Section Label */
.section-label {
  font-size: 10.5px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-tertiary);
  letter-spacing: 0.8px;
  text-transform: uppercase;
  margin-bottom: var(--spacing-sm);
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-label::before {
  content: '';
  display: block;
  width: 2px;
  height: 11px;
  background: var(--color-primary);
  border-radius: 2px;
}

/* Section Card */
.section-card {
  background: var(--color-bg-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  overflow: hidden;
}

/* KOSPI Block */
.kospi-block {
  padding: var(--spacing-md);
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: var(--spacing-md);
  align-items: center;
}

.kospi-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kospi-label {
  font-size: 9.5px;
  color: var(--color-text-tertiary);
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.kospi-value {
  font-size: 26px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
  line-height: 1.1;
  margin-top: 4px;
}

.kospi-change {
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
  margin-top: 2px;
}

.kospi-change.up { color: #f87171; }
.kospi-change.dn { color: #60a5fa; }

.kospi-side {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: var(--spacing-md);
  border-left: 1px solid var(--color-border);
}

.kospi-side-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.side-label {
  font-size: 10px;
  color: var(--color-text-tertiary);
}

.side-value {
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
}

.side-value.up { color: #f87171; }
.side-value.dn { color: #60a5fa; }

/* Heatmap Section */
.heatmap-section {
  padding: var(--spacing-md);
}

.heatmap-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-sm);
}

.heatmap-title {
  font-size: 10.5px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-semibold);
}

.heatmap-legend {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 9px;
  color: var(--color-text-tertiary);
  font-family: 'DM Mono', monospace;
}

.legend-chip {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin-left: 4px;
}

.legend-chip.up { background: rgba(248, 113, 113, 0.65); }
.legend-chip.dn { background: rgba(96, 165, 250, 0.65); }

.heatmap-matrix {
  display: grid;
  grid-template-columns: 70px repeat(5, 1fr);
  gap: 2px;
  font-size: 9px;
}

.heatmap-cell {
  padding: 6px 4px;
  text-align: center;
  border-radius: 3px;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
  min-height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.heatmap-header {
  font-weight: 600;
  color: var(--color-text-tertiary);
  padding: 4px 2px;
  font-size: 9.5px;
  text-align: center;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.heatmap-stock-name {
  font-size: 10px;
  font-weight: 500;
  padding: 4px 2px;
  text-align: left;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
}

.heatmap-toggle-btn {
  width: 100%;
  margin-top: var(--spacing-sm);
  padding: 7px;
  background: var(--color-bg-tertiary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: 10.5px;
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.15s;
}

.heatmap-toggle-btn:hover {
  background: var(--color-bg-secondary);
  color: var(--color-text-primary);
}

/* Stats Grid */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  padding: 0 var(--spacing-md) var(--spacing-sm);
}

.stat-box {
  background: var(--color-bg-tertiary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 8px 4px;
  text-align: center;
}

.stat-label {
  font-size: 8px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
  line-height: 1.4;
}

.stat-value {
  font-size: 13px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
}

.stat-value.up { color: #f87171; }
.stat-value.dn { color: #60a5fa; }
.stat-value.nt { color: #2dd4bf; }
.stat-value.neutral { color: var(--color-text-secondary); }

.stats-caption {
  padding: 0 var(--spacing-md) var(--spacing-sm);
  font-size: 9px;
  color: var(--color-text-tertiary);
  line-height: 1.4;
}

/* Insights Section */
.insights-section {
  padding: var(--spacing-md);
  padding-top: var(--spacing-sm);
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.5) 0%, rgba(30, 41, 59, 0.5) 100%);
  border-top: 1px solid var(--color-border);
}

.insight-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.insight-row:last-child {
  border-bottom: none;
}

.insight-row.highlight {
  background: rgba(167, 139, 250, 0.08);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  margin-top: 4px;
  border-bottom: none;
}

.insight-label {
  font-size: 10.5px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.insight-sub {
  font-size: 9px;
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-normal);
}

.insight-value {
  font-size: 11.5px;
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
}

.insight-value.up {
  color: #f87171;
}

.insight-value.dn {
  color: #60a5fa;
}

/* 긍정/중립/부정 카운트 (insight-value 내부 중첩 span) */
.insight-value .up { color: #f87171; }
.insight-value .nt { color: #2dd4bf; }
.insight-value .dn { color: #60a5fa; }

.insight-value.highlight {
  color: var(--color-primary);
  font-size: 12px;
}

.highlight-score {
  font-size: 10px;
  color: var(--color-text-tertiary);
  margin-left: 4px;
}

/* TOP3 Toggle */
.top3-toggle {
  display: flex;
  gap: 6px;
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.toggle-btn {
  flex: 1;
  text-align: center;
  padding: 7px;
  border-radius: var(--radius-md);
  font-size: 11.5px;
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}

.toggle-btn.buy {
  background: rgba(248, 113, 113, 0.1);
  color: #f87171;
  border-color: rgba(248, 113, 113, 0.15);
}

.toggle-btn.sell {
  background: rgba(96, 165, 250, 0.1);
  color: #60a5fa;
  border-color: rgba(96, 165, 250, 0.15);
}

.toggle-btn.buy.active {
  background: rgba(248, 113, 113, 0.2);
  border-color: rgba(248, 113, 113, 0.4);
  box-shadow: 0 0 12px rgba(248, 113, 113, 0.15);
}

.toggle-btn.sell.active {
  background: rgba(96, 165, 250, 0.2);
  border-color: rgba(96, 165, 250, 0.4);
  box-shadow: 0 0 12px rgba(96, 165, 250, 0.15);
}

/* TOP3 Panel */
.top3-panel {
  padding: var(--spacing-sm) var(--spacing-md) var(--spacing-md);
}

.stock-row {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
  transition: opacity 0.2s;
}

.stock-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.stock-row.empty {
  opacity: 0.3;
}

.rank-badge {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: var(--font-weight-bold);
  flex-shrink: 0;
  font-family: 'DM Mono', monospace;
}

.rank-badge.buy {
  background: rgba(248, 113, 113, 0.15);
  color: #f87171;
}

.rank-badge.sell {
  background: rgba(96, 165, 250, 0.15);
  color: #60a5fa;
}

.stock-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: var(--font-weight-bold);
  flex-shrink: 0;
}

.stock-info {
  flex: 1;
  min-width: 0;
}

.stock-name {
  font-size: 12.5px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.stock-reason {
  font-size: 11.5px;
  color: var(--color-text-primary);
  margin-top: 3px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: normal;
  word-break: break-word;
}

/* Sentiment Block */
.sentiment-block {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.sentiment-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sentiment-source {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.sentiment-main {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.sentiment-value {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
}

.sentiment-value.sentiment-positive { color: #f87171; }
.sentiment-value.sentiment-negative { color: #60a5fa; }
.sentiment-value.sentiment-zero { color: var(--color-text-secondary); }

.sentiment-label {
  font-size: 11px;
  color: var(--color-text-secondary);
}

.sentiment-badge {
  font-size: 10px;
  padding: 3px 10px;
  border-radius: 20px;
}

.sentiment-badge.badge-positive {
  background: rgba(248, 113, 113, 0.1);
  color: #f87171;
  border: 1px solid rgba(248, 113, 113, 0.2);
}

.sentiment-badge.badge-negative {
  background: rgba(96, 165, 250, 0.1);
  color: #60a5fa;
  border: 1px solid rgba(96, 165, 250, 0.2);
}

.sentiment-badge.badge-neutral {
  background: rgba(74, 85, 104, 0.15);
  color: var(--color-text-secondary);
  border: 1px solid rgba(74, 85, 104, 0.3);
}

.sentiment-divider {
  height: 1px;
  background: var(--color-border);
  margin: 2px 0;
}

/* Sentiment Gauge (-1.0 ~ +1.0) */
.sentiment-gauge-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sentiment-gauge-track {
  position: relative;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, #60a5fa 0%, #4a5568 50%, #f87171 100%);
}

.gauge-zero-line {
  position: absolute;
  top: -2px;
  bottom: -2px;
  left: 50%;
  width: 1px;
  background: var(--color-text-tertiary);
  opacity: 0.6;
}

.gauge-marker {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid var(--color-bg-secondary);
  transform: translate(-50%, -50%);
  transition: left 0.3s ease;
  background: var(--color-text-secondary);
}

.gauge-marker.sentiment-positive { background: #f87171; }
.gauge-marker.sentiment-negative { background: #60a5fa; }
.gauge-marker.sentiment-zero { background: var(--color-text-secondary); }

.sentiment-gauge-scale {
  display: flex;
  justify-content: space-between;
  font-size: 8.5px;
  color: var(--color-text-tertiary);
  font-family: 'DM Mono', monospace;
}

.sentiment-interpretation {
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  text-align: center;
  margin-top: 2px;
}

.sentiment-interpretation.sentiment-positive { color: #f87171; }
.sentiment-interpretation.sentiment-negative { color: #60a5fa; }
.sentiment-interpretation.sentiment-zero { color: var(--color-text-secondary); }

/* Distribution */
.distribution-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.distribution-empty {
  padding: 12px;
  text-align: center;
  font-size: 10px;
  color: var(--color-text-tertiary);
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-md);
}

.distribution-header {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.distribution-bar {
  height: 6px;
  background: var(--color-bg-tertiary);
  border-radius: 3px;
  overflow: hidden;
  display: flex;
}

.bar-segment {
  height: 6px;
}

.bar-segment.positive {
  background: #f87171;
  border-radius: 3px 0 0 3px;
}

.bar-segment.neutral {
  background: #4a5568;
}

.bar-segment.negative {
  background: #60a5fa;
  border-radius: 0 3px 3px 0;
}

.distribution-labels {
  display: flex;
  justify-content: space-between;
  font-size: 9px;
  font-family: 'DM Mono', monospace;
  gap: var(--spacing-xs);
}

.label-positive { color: #f87171; }
.label-neutral { color: var(--color-text-tertiary); }
.label-negative { color: #60a5fa; }

/* Collection Info */
.collection-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.info-item.align-right {
  text-align: right;
}

.info-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.info-value {
  font-size: 11px;
  color: var(--color-text-secondary);
}

/* Forecast Block (5일 예측 전망) */
.forecast-block {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.forecast-distribution {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.forecast-stat {
  flex: 1;
  text-align: center;
}

.forecast-stat-label {
  font-size: 10px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.forecast-stat-value {
  font-size: 22px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
}

.forecast-stat-value .unit {
  font-size: 11px;
  margin-left: 3px;
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.forecast-stat.up .forecast-stat-value { color: #f87171; }
.forecast-stat.dn .forecast-stat-value { color: #60a5fa; }

.forecast-divider {
  width: 1px;
  height: 36px;
  background: var(--color-border);
}

.forecast-bar {
  height: 6px;
  background: var(--color-bg-tertiary);
  border-radius: 3px;
  overflow: hidden;
  display: flex;
}

.forecast-bar-up { height: 100%; background: #f87171; }
.forecast-bar-dn { height: 100%; background: #60a5fa; }

.forecast-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.metric-item {
  background: var(--color-bg-tertiary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 8px;
  text-align: center;
}

.metric-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.metric-value {
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
}

.metric-value.up { color: #f87171; }
.metric-value.dn { color: #60a5fa; }
.metric-value.uncertainty.low { color: #34d399; }
.metric-value.uncertainty.medium { color: #fbbf24; }
.metric-value.uncertainty.high { color: #f87171; }

.metric-sub {
  font-size: 8.5px;
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
  margin-left: 2px;
  display: block;
}

.forecast-top {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 8px 12px;
  background: rgba(167, 139, 250, 0.08);
  border-radius: var(--radius-md);
}

.forecast-top-label {
  font-size: 10.5px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  flex: 1;
}

.forecast-top-name {
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.forecast-top-value {
  font-size: 12px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
}

.forecast-top-value.up { color: #f87171; }

/* Financial Block (펀더멘탈 분석) */
.financial-block {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.financial-avg-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.financial-avg-box {
  background: var(--color-bg-tertiary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 10px 6px;
  text-align: center;
}

.financial-avg-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
  letter-spacing: 0.3px;
}

.financial-avg-value {
  font-size: 18px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
}

.financial-avg-value .unit {
  font-size: 11px;
  color: var(--color-text-secondary);
  margin-left: 1px;
}

.financial-avg-sub {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-top: 4px;
}

.excellent-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: linear-gradient(90deg, rgba(167, 139, 250, 0.08) 0%, rgba(167, 139, 250, 0.04) 100%);
  border-radius: var(--radius-md);
  border: 1px solid rgba(167, 139, 250, 0.15);
}

.excellent-label {
  font-size: 11px;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.excellent-sub {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.excellent-count {
  font-size: 22px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  color: var(--color-primary);
}

.excellent-count .unit {
  font-size: 10px;
  color: var(--color-text-secondary);
  margin-left: 3px;
}

.data-coverage {
  display: flex;
  align-items: center;
  gap: 8px;
}

.coverage-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.coverage-bar {
  flex: 1;
  height: 4px;
  background: var(--color-bg-tertiary);
  border-radius: 2px;
  overflow: hidden;
}

.coverage-fill {
  height: 100%;
  background: var(--color-stock-up);
  transition: width 0.3s;
}

.coverage-percent {
  font-size: 10px;
  font-family: 'DM Mono', monospace;
  color: var(--color-text-secondary);
  flex-shrink: 0;
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}

/* Smart Money Block (수급 매트릭스 / 스마트머니 4분면) */
.smart-money-block {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.smart-money-intro {
  font-size: 10px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  padding: 8px 10px;
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-md);
}

.quadrant-caption {
  font-size: 9px;
  color: var(--color-text-tertiary);
  line-height: 1.4;
  text-align: center;
}

.consensus-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.consensus-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.consensus-value {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
  line-height: 1;
}

.signal-badge {
  font-size: 10px;
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: var(--font-weight-semibold);
}

.signal-badge.signal-buy {
  background: rgba(248, 113, 113, 0.1);
  color: #f87171;
  border: 1px solid rgba(248, 113, 113, 0.2);
}

.signal-badge.signal-sell {
  background: rgba(96, 165, 250, 0.1);
  color: #60a5fa;
  border: 1px solid rgba(96, 165, 250, 0.2);
}

.signal-badge.signal-neutral {
  background: rgba(74, 85, 104, 0.15);
  color: var(--color-text-secondary);
  border: 1px solid rgba(74, 85, 104, 0.3);
}

.quadrant-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  position: relative;
}

.quadrant-cell {
  padding: 12px 10px;
  border-radius: var(--radius-md);
  text-align: center;
  border: 1px solid var(--color-border);
  background: var(--color-bg-tertiary);
}

.quadrant-cell.both-buy {
  background: rgba(248, 113, 113, 0.08);
  border-color: rgba(248, 113, 113, 0.2);
}

.quadrant-cell.both-sell {
  background: rgba(96, 165, 250, 0.08);
  border-color: rgba(96, 165, 250, 0.2);
}

.quadrant-cell.decoupling {
  background: rgba(251, 191, 36, 0.05);
  border-color: rgba(251, 191, 36, 0.15);
}

.quadrant-name {
  font-size: 10px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-semibold);
  margin-bottom: 6px;
}

.quadrant-count {
  font-size: 20px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
  line-height: 1;
}

.quadrant-count.up { color: #f87171; }
.quadrant-count.dn { color: #60a5fa; }

.quadrant-sub {
  font-size: 8.5px;
  color: var(--color-text-tertiary);
  margin-top: 4px;
}

.quadrant-axis {
  display: none;
}

.smart-money-top {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 8px 12px;
  background: rgba(167, 139, 250, 0.08);
  border-radius: var(--radius-md);
}

.smart-money-top-label {
  font-size: 10.5px;
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  flex: 1;
}

.smart-money-top-name {
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.smart-money-top-value {
  font-size: 11.5px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
}

.smart-money-top-value.up { color: #f87171; }

/* Trend Block (5일 시장 평균 예측 추이) */
.trend-block {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.trend-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
  letter-spacing: 0.5px;
}

.trend-value {
  font-size: 26px;
  font-weight: var(--font-weight-bold);
  font-family: 'DM Mono', monospace;
  line-height: 1.1;
}

.trend-value.up { color: #f87171; }
.trend-value.dn { color: #60a5fa; }
.trend-value.neutral { color: var(--color-text-secondary); }

.trend-badge {
  font-size: 10px;
  padding: 4px 10px;
  border-radius: 20px;
  font-weight: var(--font-weight-semibold);
}

.trend-badge.up { background: rgba(248, 113, 113, 0.1); color: #f87171; border: 1px solid rgba(248, 113, 113, 0.2); }
.trend-badge.dn { background: rgba(96, 165, 250, 0.1); color: #60a5fa; border: 1px solid rgba(96, 165, 250, 0.2); }
.trend-badge.neutral { background: rgba(74, 85, 104, 0.15); color: var(--color-text-secondary); border: 1px solid rgba(74, 85, 104, 0.3); }

.confidence-band {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-md);
  font-size: 10px;
}

.confidence-band .confidence-label {
  color: var(--color-text-tertiary);
}

.confidence-range {
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.range-sep {
  color: var(--color-text-tertiary);
  margin: 0 4px;
}

.trend-chart {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 4px;
  height: 120px;
  align-items: end;
}

.trend-bar-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.trend-bar-wrap {
  width: 100%;
  flex: 1;
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  height: 50px;
  min-height: 50px;
}

.trend-bar {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 70%;
  min-height: 4px;
  border-radius: 3px 3px 0 0;
  transition: height 0.3s ease;
}

.trend-bar.up { background: linear-gradient(180deg, #f87171, rgba(248, 113, 113, 0.5)); }
.trend-bar.dn { background: linear-gradient(180deg, rgba(96, 165, 250, 0.5), #60a5fa); }

/* D+1 기준선 (값 0) → 막대 대신 얇은 틱으로 표시 */
.trend-bar-baseline {
  width: 70%;
  height: 2px;
  border-radius: 2px;
  background: var(--color-border);
}

.trend-bar-value {
  font-size: 9px;
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
  color: var(--color-text-primary);
}

.trend-bar-value.up { color: #f87171; }
.trend-bar-value.dn { color: #60a5fa; }
.trend-bar-value.base { color: var(--color-text-tertiary); }

.trend-bar-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  font-family: 'DM Mono', monospace;
}

.trend-coverage {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 10px;
}

.trend-coverage .coverage-label {
  color: var(--color-text-tertiary);
}

.trend-coverage .coverage-count {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
  font-family: 'DM Mono', monospace;
}

.trend-coverage .unit {
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}
</style>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { marketAnalysisApi, companyApi } from '@/services/api'
import { logger } from '@/utils/logger'

const route = useRoute()
const router = useRouter()

// route.params.symbol drives the page (route is /company/:symbol)
const symbol = ref(route.params.symbol || '')

const activeTab = ref('ai')
const aiSubTab = ref('quant')
// 헤더에 표시되는 name/symbol 만 보관 (상세 탭 데이터는 각 탭 전용 ref 로 분리)
const company = ref({ name: symbol.value, symbol: symbol.value })
const isFavorite = ref(false)

// AI 분석 API 상태
const aiLoading = ref(true)
const aiError = ref(false)
const stockDetail = ref(null)
const hasAnalysis = computed(() => !!stockDetail.value && stockDetail.value.has_analysis === true)

// 헤더 아이콘: 종목명 첫 글자(없으면 코드 첫 글자, 그래도 없으면 '?') — 다른 화면(BotView)과 동일 스타일
const logoChar = computed(() => {
  const name = company.value?.name
  const sym = company.value?.symbol
  return (name?.charAt(0) || sym?.charAt(0) || '?').toUpperCase()
})

const toggleFavorite = () => {
  isFavorite.value = !isFavorite.value
}

const tabs = [
  { key: 'ai', label: 'AI분석' },
  { key: 'basic', label: '기본정보' },
  { key: 'financial', label: '재무제표' },
  { key: 'disclosure', label: '공시정보' }
]

const aiSubTabs = [
  { key: 'quant', label: '정량분석' },
  { key: 'sentiment', label: '감성분석' },
  { key: 'timeseries', label: '시계열' }
]

// ===== 기본정보 / 재무제표 / 공시정보 탭 상태 (탭 활성화 시 lazy fetch + 캐시) =====
const basicInfo = ref(null)
const basicLoading = ref(false)
const basicError = ref(false)
const basicLoaded = ref(false)
// 데이터 미연동 등 사유 안내 (백엔드 data.notice). null/빈 문자열이면 미표시
const basicNotice = ref(null)

const financials = ref(null)
const financialLoading = ref(false)
const financialError = ref(false)
const financialLoaded = ref(false)
const financialNotice = ref(null)

const disclosureData = ref(null)
const disclosureLoading = ref(false)
const disclosureError = ref(false)
const disclosureLoaded = ref(false)
const disclosureNotice = ref(null)

const formatNumber = (num) => {
  if (num === null || num === undefined || Number.isNaN(Number(num))) return '-'
  return new Intl.NumberFormat('ko-KR').format(num)
}

// ===== 프레젠테이션 헬퍼 (raw number → 화면 표시) =====

// 색상 토큰: 양수 → up(빨강, 한국식 상승), 음수 → dn(파랑), 0 근처 → nt
const toneBySign = (value, eps = 0) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return 'nt'
  const n = Number(value)
  if (n > eps) return 'up'
  if (n < -eps) return 'dn'
  return 'nt'
}

// 원화 금액 → "+2,840억" / "-1.2조" 형태
const formatMoney = (won) => {
  if (won === null || won === undefined || Number.isNaN(Number(won))) return '-'
  const n = Number(won)
  const sign = n > 0 ? '+' : n < 0 ? '-' : ''
  const abs = Math.abs(n)
  const JO = 1e12
  const EOK = 1e8
  if (abs >= JO) {
    const v = abs / JO
    return `${sign}${(Math.round(v * 10) / 10).toLocaleString('ko-KR')}조`
  }
  if (abs >= EOK) {
    const v = Math.round(abs / EOK)
    return `${sign}${v.toLocaleString('ko-KR')}억`
  }
  return `${sign}${formatNumber(abs)}`
}

// 부호 포함 고정 소수 (감성/추세 점수 표시용)
const signedFixed = (value, digits = 2) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '-'
  const n = Number(value)
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toFixed(digits)}`
}

// 0~100 클램프 (바 너비)
const clampPercent = (value) => {
  const n = Number(value)
  if (Number.isNaN(n)) return 0
  return Math.max(0, Math.min(100, Math.round(n)))
}

// 순매수 금액(원) → 바 너비: 5000억을 100% 기준으로 정규화
const moneyToPercent = (won) => clampPercent((Math.abs(Number(won) || 0) / 5e11) * 100)

// ===== 정량분석 (computed) =====
const quantAnalysis = computed(() => {
  const q = stockDetail.value?.quant
  if (!q) return null
  const fmtPct = (v, digits = 2) =>
    v === null || v === undefined || Number.isNaN(Number(v)) ? '-' : `${signedFixed(v, digits)}%`
  const fmtRatio = (v, suffix = '') =>
    v === null || v === undefined || Number.isNaN(Number(v)) ? '-' : `${Number(v).toFixed(1)}${suffix}`
  return {
    stats: [
      { label: '외국인\n순매수', value: formatMoney(q.foreign_net_buy), class: toneBySign(q.foreign_net_buy) },
      { label: '기관\n순매수', value: formatMoney(q.institutional_net_buy), class: toneBySign(q.institutional_net_buy) },
      {
        label: '거래량\n배율',
        value: q.vol_avg_multiple != null ? `${Number(q.vol_avg_multiple).toFixed(1)}x` : '-',
        class: 'yw'
      },
      {
        label: '종가\n위치',
        value: q.close_position != null ? Number(q.close_position).toFixed(2) : '-',
        class: 'nt'
      }
    ],
    kisFeatures: [
      {
        label: '외국인 순매수',
        source: 'KIS 수급',
        value: formatMoney(q.foreign_net_buy),
        percent: moneyToPercent(q.foreign_net_buy),
        class: toneBySign(q.foreign_net_buy)
      },
      {
        label: '기관 순매수',
        source: 'KIS 수급',
        value: formatMoney(q.institutional_net_buy),
        percent: moneyToPercent(q.institutional_net_buy),
        class: toneBySign(q.institutional_net_buy)
      },
      {
        label: '장초반 수익률',
        source: 'KIS 가격',
        value: fmtPct(q.morning_return),
        // 장초반 수익률(%)을 ±3% 구간으로 정규화(±3% 이상이면 가득)
        percent: clampPercent((Math.abs(Number(q.morning_return) || 0) / 3) * 100),
        class: toneBySign(q.morning_return)
      },
      {
        label: '종가 위치',
        source: 'KIS 가격',
        value: q.close_position != null ? Number(q.close_position).toFixed(2) : '-',
        percent: clampPercent((Number(q.close_position) || 0) * 100),
        class: 'purple'
      }
    ],
    dartMetrics: [
      { label: 'PER', value: q.per != null ? Number(q.per).toFixed(1) : '-', class: 'up' },
      { label: 'ROE (%)', value: fmtRatio(q.roe, '%'), class: 'nt' },
      { label: '영업이익률', value: fmtRatio(q.operating_margin, '%'), class: 'yw' }
    ]
  }
})

// ===== 감성분석 (computed) =====
const sentimentLabel = (score) => {
  const n = Number(score) || 0
  if (n >= 0.05) return '긍정'
  if (n <= -0.05) return '부정'
  return '중립'
}

// 감성 점수(-1~1) → 색상 토큰: 긍정 up(빨강), 부정 dn(파랑), 중립 nt
const sentimentTone = (score) => {
  const n = Number(score) || 0
  if (n >= 0.05) return 'up'
  if (n <= -0.05) return 'dn'
  return 'nt'
}

// 감성 배지 색상 클래스
const sentimentBadgeClass = (score) => {
  const n = Number(score) || 0
  if (n >= 0.05) return 'positive'
  if (n <= -0.05) return 'negative'
  return 'neutral'
}

const sentimentAnalysis = computed(() => {
  const s = stockDetail.value?.sentiment
  if (!s) return null
  const dist = s.market_distribution || {}
  const difference = (Number(s.stock_sentiment_score) || 0) - (Number(s.market_sentiment_score) || 0)
  return {
    stockSentiment: {
      score: Number(s.stock_sentiment_score) || 0,
      label: sentimentLabel(s.stock_sentiment_score),
      tone: sentimentTone(s.stock_sentiment_score),
      badge: sentimentBadgeClass(s.stock_sentiment_score),
      newsCount: s.stock_news_count ?? 0
    },
    marketSentiment: {
      score: Number(s.market_sentiment_score) || 0,
      label: `${sentimentLabel(s.market_sentiment_score)} 우세`,
      tone: sentimentTone(s.market_sentiment_score),
      badge: sentimentBadgeClass(s.market_sentiment_score),
      newsCount: s.market_news_count ?? 0
    },
    difference,
    differenceTone: toneBySign(difference),
    // 척도 바 위치(%): score -1..+1 → 0..100, 마커가 바를 벗어나지 않도록 4~96%로 클램프
    stockMarkerLeft: Math.max(4, Math.min(96, 50 + (Number(s.stock_sentiment_score) || 0) * 50)),
    marketMarkerLeft: Math.max(4, Math.min(96, 50 + (Number(s.market_sentiment_score) || 0) * 50)),
    stockNewsRange: s.time_range || '-',
    marketDistribution: {
      positive: dist.positive_percent ?? 0,
      neutral: dist.neutral_percent ?? 0,
      negative: dist.negative_percent ?? 0,
      positiveCount: dist.positive_count ?? 0,
      neutralCount: dist.neutral_count ?? 0,
      negativeCount: dist.negative_count ?? 0
    },
    marketSources: s.market_sources || '-'
  }
})

// ===== 시계열 (computed) =====
// 백엔드 주의: price_trend / volume_trend 는 "원/day 슬로프"(사용자 표시 부적합),
// price_uncertainty 는 0~1 비율이 아니라 원 단위 밴드폭(최대 수백)이다.
// 따라서 화면에는 raw 슬로프/밴드 대신 forecasts 기반 파생 지표(예상 수익률 %, 평균 불확실성 %)를 표시한다.
const timeseriesAnalysis = computed(() => {
  const t = stockDetail.value?.timeseries
  if (!t) return null
  const priceTrend = Number(t.price_trend) || 0
  const volumeTrend = Number(t.volume_trend) || 0
  const rawForecasts = Array.isArray(t.forecasts) ? t.forecasts : []

  // 유효 yhat 만 추려 D+1 → D+N 예상 수익률 계산
  const valid = rawForecasts.filter((f) => Number.isFinite(Number(f.yhat)))
  const firstYhat = valid.length ? Number(valid[0].yhat) : null
  const lastYhat = valid.length ? Number(valid[valid.length - 1].yhat) : null
  const expectedReturn =
    firstYhat && firstYhat !== 0 ? ((lastYhat - firstYhat) / firstYhat) * 100 : null

  // forecasts.uncertainty_pct 는 이미 진짜 % (예: 3.5). 평균을 신뢰도 지표로 사용.
  const uncPcts = valid
    .map((f) => Number(f.uncertainty_pct))
    .filter((v) => Number.isFinite(v))
  const avgUncertaintyPct = uncPcts.length
    ? uncPcts.reduce((a, b) => a + b, 0) / uncPcts.length
    : null

  return {
    stats: [
      {
        label: 'D+1→D+5\n예상수익',
        value: expectedReturn != null ? `${signedFixed(expectedReturn, 1)}%` : '-',
        class: toneBySign(expectedReturn)
      },
      {
        label: '가격\n추세',
        value: priceTrend > 0 ? '상승' : priceTrend < 0 ? '하락' : '보합',
        class: toneBySign(priceTrend)
      },
      {
        label: '예측\n불확실성',
        value: avgUncertaintyPct != null ? `${avgUncertaintyPct.toFixed(1)}%` : '-',
        class: 'yw'
      },
      {
        label: '학습\n거래일',
        value: t.training_days != null ? `${t.training_days}일` : '-',
        class: 'nt'
      }
    ],
    features: [
      {
        label: '가격 추세',
        source: 'price_trend',
        value: priceTrend > 0 ? '상승 ▲' : priceTrend < 0 ? '하락 ▼' : '보합',
        // 슬로프 절대크기는 의미가 약하므로 방향 신호로 고정 폭(상승/하락 60%, 보합 8%)
        percent: priceTrend === 0 ? 8 : 60,
        class: toneBySign(priceTrend)
      },
      {
        label: '거래량 추세',
        source: 'vol_trend',
        value: volumeTrend > 0 ? '증가 ▲' : volumeTrend < 0 ? '감소 ▼' : '보합',
        percent: volumeTrend === 0 ? 8 : 60,
        class: toneBySign(volumeTrend)
      },
      {
        label: '예측 불확실성',
        source: 'avg %',
        value: avgUncertaintyPct != null ? `${avgUncertaintyPct.toFixed(1)}%` : '-',
        // 불확실성 %를 0~10% 구간으로 정규화(10% 이상이면 가득)
        percent: avgUncertaintyPct != null ? clampPercent(avgUncertaintyPct * 10) : 0,
        class: 'nt'
      }
    ],
    forecasts: valid.map((f) => ({
      day: f.day,
      yhat: formatNumber(Math.round(Number(f.yhat))),
      upper: f.yhat_upper != null ? formatNumber(Math.round(Number(f.yhat_upper))) : '-',
      lower: f.yhat_lower != null ? formatNumber(Math.round(Number(f.yhat_lower))) : '-',
      uncertainty: f.uncertainty_pct != null ? `${Number(f.uncertainty_pct).toFixed(1)}%` : '-'
    })),
    // 종합 추세 판단
    priceUp: priceTrend >= 0,
    volumeUp: volumeTrend >= 0,
    // 평균 불확실성 3% 미만이면 양호, 이상이면 주의 (값 없으면 주의)
    confidenceGood: avgUncertaintyPct != null && avgUncertaintyPct < 3
  }
})

// ===== 시계열 예측 차트 geometry (실제 forecasts 로 SVG 좌표 계산) =====
// viewBox 비율을 렌더 박스(약 320×150)와 맞추고 preserveAspectRatio 기본값 유지 → 선명/비왜곡
const FORECAST_CHART = { w: 320, h: 150, padX: 16, padTop: 14, padBottom: 18 }

const forecastChart = computed(() => {
  const valid = (timeseriesAnalysis.value && stockDetail.value?.timeseries?.forecasts
    ? stockDetail.value.timeseries.forecasts
    : []
  ).filter((f) => Number.isFinite(Number(f.yhat)))
  if (valid.length < 2) return null

  const { w, h, padX, padTop, padBottom } = FORECAST_CHART
  const innerW = w - padX * 2
  const innerH = h - padTop - padBottom

  // y 범위: lower..upper 전체의 min/max (없으면 yhat 사용)
  const lows = valid.map((f) => Number(f.yhat_lower ?? f.yhat))
  const highs = valid.map((f) => Number(f.yhat_upper ?? f.yhat))
  let minY = Math.min(...lows)
  let maxY = Math.max(...highs)
  if (!Number.isFinite(minY) || !Number.isFinite(maxY)) return null
  if (minY === maxY) {
    // 평평한 경우 약간의 여백
    minY -= 1
    maxY += 1
  }
  const xAt = (i) => padX + (valid.length === 1 ? innerW / 2 : (innerW * i) / (valid.length - 1))
  const yAt = (val) => padTop + innerH - ((val - minY) / (maxY - minY)) * innerH

  const yhatPts = valid.map((f, i) => `${xAt(i).toFixed(1)},${yAt(Number(f.yhat)).toFixed(1)}`)
  // 신뢰구간 영역: upper(좌→우) + lower(우→좌)
  const upperPts = valid.map((f, i) => `${xAt(i).toFixed(1)},${yAt(Number(f.yhat_upper ?? f.yhat)).toFixed(1)}`)
  const lowerPtsRev = valid
    .map((f, i) => `${xAt(i).toFixed(1)},${yAt(Number(f.yhat_lower ?? f.yhat)).toFixed(1)}`)
    .reverse()
  const hasBand = valid.some((f) => f.yhat_upper != null && f.yhat_lower != null)

  return {
    width: w,
    height: h,
    line: yhatPts.join(' '),
    band: hasBand ? [...upperPts, ...lowerPtsRev].join(' ') : null,
    dots: valid.map((f, i) => ({ x: xAt(i), y: yAt(Number(f.yhat)) })),
    labels: valid.map((f, i) => ({ x: xAt(i), text: f.day }))
  }
})

// ===== 정량 피처 막대 차트 geometry (4개 KIS 스탯의 상대 막대) =====
const QUANT_CHART = { w: 320, h: 150, padX: 14, padTop: 16, padBottom: 26, gap: 14 }

const quantChart = computed(() => {
  const q = stockDetail.value?.quant
  if (!q) return null
  // 0~1 로 정규화된 4개 막대 (각 피처의 의미 있는 기준으로 스케일)
  const bars = [
    {
      label: '외국인',
      norm: Math.min(1, Math.abs(Number(q.foreign_net_buy) || 0) / 5e11),
      color: (Number(q.foreign_net_buy) || 0) >= 0 ? '#F87171' : '#60A5FA'
    },
    {
      label: '기관',
      norm: Math.min(1, Math.abs(Number(q.institutional_net_buy) || 0) / 5e11),
      color: (Number(q.institutional_net_buy) || 0) >= 0 ? '#F87171' : '#60A5FA'
    },
    {
      label: '거래량',
      // 거래량 배율: 1x=기준, 3x 이상이면 가득
      norm: Math.min(1, (Number(q.vol_avg_multiple) || 0) / 3),
      color: '#FBBF24'
    },
    {
      label: '종가위치',
      // 0~1 값 그대로
      norm: Math.min(1, Math.max(0, Number(q.close_position) || 0)),
      color: '#A78BFA'
    }
  ]
  const { w, h, padX, padTop, padBottom, gap } = QUANT_CHART
  const innerW = w - padX * 2
  const innerH = h - padTop - padBottom
  const barW = (innerW - gap * (bars.length - 1)) / bars.length
  return {
    width: w,
    height: h,
    baseline: h - padBottom,
    bars: bars.map((b, i) => {
      const bh = Math.max(2, b.norm * innerH)
      return {
        x: padX + i * (barW + gap),
        y: padTop + innerH - bh,
        w: barW,
        h: bh,
        color: b.color,
        label: b.label,
        labelX: padX + i * (barW + gap) + barW / 2
      }
    })
  }
})

// ===== 기본정보 (computed) =====
// YYYYMMDD -> YYYY-MM-DD (이미 포맷된 값/빈 값은 그대로 통과)
const formatDate = (raw) => {
  if (!raw) return null
  const s = String(raw)
  if (/^\d{8}$/.test(s)) return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
  return s
}

// KIS 숫자 필드: null -> '—', 그 외 단위 포맷
const numOrDash = (v, unit = '') => {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—'
  return `${formatNumber(v)}${unit}`
}
const ratioOrDash = (v, suffix = '') => {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—'
  return `${Number(v).toFixed(2)}${suffix}`
}

const basicView = computed(() => {
  const b = basicInfo.value
  if (!b) return null
  // DART 출처(선택적) 행: null 이면 숨김
  const optionalRows = [
    { label: '기업명(영문)', value: b.stock_name_en },
    { label: '주소', value: b.address },
    { label: '대표자', value: b.ceo_name },
    { label: '설립일', value: formatDate(b.established_date) }
  ].filter((row) => row.value)
  return {
    homepage: b.homepage || null,
    optionalRows,
    sector: b.sector || '제공 안 함',
    marketCap: b.market_cap != null ? formatMoney(b.market_cap) : '—',
    listedShares: numOrDash(b.listed_shares, '주'),
    currentPrice: numOrDash(b.current_price, '원'),
    changeRate: b.change_rate,
    changeRateText:
      b.change_rate === null || b.change_rate === undefined || Number.isNaN(Number(b.change_rate))
        ? null
        : `${signedFixed(b.change_rate, 2)}%`,
    changeRateClass: toneBySign(b.change_rate),
    per: ratioOrDash(b.per, '배'),
    pbr: ratioOrDash(b.pbr, '배'),
    eps: numOrDash(b.eps, '원'),
    bps: numOrDash(b.bps, '원'),
    week52High: numOrDash(b.week52_high, '원'),
    week52Low: numOrDash(b.week52_low, '원')
  }
})

// 현재가는 KIS 시세 출처. current_price 가 null 이고 미연동 사유(notice)가 있으면 KIS 점검중으로 간주
const basicPriceKisDown = computed(
  () => basicInfo.value != null && basicInfo.value.current_price == null && !!basicNotice.value
)

// ===== 재무제표 (computed) =====
const financialView = computed(() => {
  const f = financials.value
  if (!f) return null
  const r = f.ratios || {}
  // good 규칙: 데이터 기반의 단순 휴리스틱 (값 없으면 neutral)
  const ratioCard = (label, value, unit, isGood) => ({
    label,
    value: value === null || value === undefined || Number.isNaN(Number(value)) ? '—' : `${Number(value).toFixed(1)}${unit}`,
    good: value !== null && value !== undefined && !Number.isNaN(Number(value)) ? isGood(Number(value)) : false
  })
  return {
    annual: Array.isArray(f.annual) ? f.annual : [],
    ratios: [
      ratioCard('ROE', r.roe, '%', (v) => v > 10),
      ratioCard('ROA', r.roa, '%', (v) => v > 5),
      ratioCard('PER', r.per, '배', (v) => v < 20),
      ratioCard('PBR', r.pbr, '배', (v) => v < 2),
      ratioCard('부채비율', r.debt_ratio, '%', (v) => v < 100),
      ratioCard('유동비율', r.current_ratio, '%', (v) => v > 100)
    ]
  }
})

// ===== 공시정보 (computed) =====
const disclosureList = computed(() =>
  Array.isArray(disclosureData.value?.disclosures) ? disclosureData.value.disclosures : []
)

// ===== 탭별 lazy 로더 (캐시: *Loaded 가 true 면 재요청 안 함) =====
const loadBasicInfo = async () => {
  if (basicLoaded.value || basicLoading.value) return
  basicLoading.value = true
  basicError.value = false
  try {
    const res = await companyApi.getBasicInfo(symbol.value)
    if (res && res.success && res.data) {
      basicInfo.value = res.data
      basicNotice.value = res.data.notice || null
      basicLoaded.value = true
    } else {
      basicError.value = true
    }
  } catch (error) {
    logger.debug('기업 기본정보 조회 실패:', error)
    basicError.value = true
  } finally {
    basicLoading.value = false
  }
}

const loadFinancials = async () => {
  if (financialLoaded.value || financialLoading.value) return
  financialLoading.value = true
  financialError.value = false
  try {
    const res = await companyApi.getFinancials(symbol.value)
    if (res && res.success && res.data) {
      financials.value = res.data
      financialNotice.value = res.data.notice || null
      financialLoaded.value = true
    } else {
      financialError.value = true
    }
  } catch (error) {
    logger.debug('재무제표 조회 실패:', error)
    financialError.value = true
  } finally {
    financialLoading.value = false
  }
}

const loadDisclosures = async () => {
  if (disclosureLoaded.value || disclosureLoading.value) return
  disclosureLoading.value = true
  disclosureError.value = false
  try {
    const res = await companyApi.getDisclosures(symbol.value)
    if (res && res.success && res.data) {
      disclosureData.value = res.data
      disclosureNotice.value = res.data.notice || null
      disclosureLoaded.value = true
    } else {
      disclosureError.value = true
    }
  } catch (error) {
    logger.debug('공시정보 조회 실패:', error)
    disclosureError.value = true
  } finally {
    disclosureLoading.value = false
  }
}

// 상세 탭 캐시 초기화 (종목 변경 시)
const resetDetailTabs = () => {
  basicInfo.value = null
  basicLoaded.value = false
  basicError.value = false
  basicNotice.value = null
  financials.value = null
  financialLoaded.value = false
  financialError.value = false
  financialNotice.value = null
  disclosureData.value = null
  disclosureLoaded.value = false
  disclosureError.value = false
  disclosureNotice.value = null
}

// 활성 탭에 맞는 데이터를 lazy 로드
const ensureTabData = (tab) => {
  if (tab === 'basic') loadBasicInfo()
  else if (tab === 'financial') loadFinancials()
  else if (tab === 'disclosure') loadDisclosures()
}

// 탭 전환 감지 → 해당 탭 데이터 lazy fetch
watch(activeTab, (tab) => ensureTabData(tab))

const loadStockDetail = async () => {
  aiLoading.value = true
  aiError.value = false
  stockDetail.value = null
  // 헤더 정체성(종목명/코드)은 항상 실제 종목을 반영한다.
  // 코드는 언제나 route symbol, 이름은 로딩/에러/무분석 동안 route symbol 로 폴백 ("아마존" 금지)
  company.value = {
    ...company.value,
    name: symbol.value,
    symbol: symbol.value
  }
  try {
    const res = await marketAnalysisApi.getStockDetail(symbol.value)
    if (res && res.success && res.data) {
      stockDetail.value = res.data
      // 응답이 있으면 실제 종목명으로 갱신 (이름 없으면 route symbol 유지)
      company.value = {
        ...company.value,
        name: res.data.stock_name || symbol.value,
        symbol: symbol.value
      }
    } else {
      aiError.value = true
    }
  } catch (error) {
    logger.debug('종목 상세 분석 조회 실패:', error)
    aiError.value = true
  } finally {
    aiLoading.value = false
  }
}

onMounted(() => {
  // Check if AI analysis tab should be shown
  if (route.query.showAiAnalysis === 'true') {
    activeTab.value = 'ai'
    // Remove query parameter from URL
    router.replace({ query: {} })
  }

  // Fetch real AI analysis for the routed stock symbol
  loadStockDetail()

  // 마운트 시 기본 탭이 AI 가 아닐 경우(예: 향후 진입 경로) 해당 탭 데이터 lazy 로드
  ensureTabData(activeTab.value)
})

// /company/A -> /company/B 처럼 컴포넌트가 재사용될 때 onMounted 가 다시 호출되지 않으므로
// route symbol 변경을 감지해 symbol ref 를 갱신하고 재조회한다 (초기 마운트 중복 호출 방지를 위해 immediate 미사용)
watch(
  () => route.params.symbol,
  (newSymbol) => {
    if (!newSymbol || newSymbol === symbol.value) return
    symbol.value = newSymbol
    loadStockDetail()
    // 상세 탭 캐시 초기화 후 현재 활성 탭만 재조회
    resetDetailTabs()
    ensureTabData(activeTab.value)
  }
)
</script>

<template>
  <div class="company-detail-screen">
    <AppHeader title="기업 상세 정보" showBack />

    <div class="content">
      <!-- Company Header -->
      <div class="company-header">
        <div class="company-logo">
          <span class="logo-char">{{ logoChar }}</span>
        </div>
        <div class="company-info">
          <span class="company-name">{{ company.name }}</span>
          <span class="company-symbol">{{ company.symbol }}</span>
        </div>
        <button class="favorite-btn" @click="toggleFavorite">
          <van-icon
            :name="isFavorite ? 'star' : 'star-o'"
            :color="isFavorite ? '#F59E0B' : '#9CA3AF'"
            size="24"
          />
        </button>
      </div>

      <!-- Sub Tabs -->
      <div class="sub-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          :class="['sub-tab', { active: activeTab === tab.key }]"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- Basic Info Tab -->
      <div v-if="activeTab === 'basic'" class="tab-content">
        <!-- 데이터 미연동 안내 배너 (기존 콘텐츠 위에 함께 표시) -->
        <div v-if="basicNotice" class="notice-banner">
          <svg class="notice-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span class="notice-text">{{ basicNotice }}</span>
        </div>

        <!-- 로딩 -->
        <div v-if="basicLoading" class="ai-state-message">불러오는 중...</div>

        <!-- 에러 -->
        <div v-else-if="basicError" class="ai-state-message">
          기본정보를 불러올 수 없습니다.
        </div>

        <!-- Company Info Card -->
        <div v-else-if="basicView" class="info-card">
          <!-- DART 출처 선택적 행 (값 없으면 행 자체를 숨김) -->
          <div v-for="row in basicView.optionalRows" :key="row.label" class="info-row">
            <span class="info-label">{{ row.label }}</span>
            <span class="info-value">{{ row.value }}</span>
          </div>
          <div v-if="basicView.homepage" class="info-row">
            <span class="info-label">홈페이지</span>
            <a class="info-value link" :href="basicView.homepage" target="_blank">{{ basicView.homepage }}</a>
          </div>
          <div class="info-row">
            <span class="info-label">섹터/업종</span>
            <span class="info-value">{{ basicView.sector }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">시가총액</span>
            <span class="info-value">{{ basicView.marketCap }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">상장주식수</span>
            <span class="info-value">{{ basicView.listedShares }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">현재가</span>
            <span class="info-value">
              <KisMaintenanceNotice v-if="basicPriceKisDown" variant="inline" />
              <template v-else>
                {{ basicView.currentPrice }}
                <span
                  v-if="basicView.changeRateText"
                  class="change-rate"
                  :class="basicView.changeRateClass"
                >({{ basicView.changeRateText }})</span>
              </template>
            </span>
          </div>
          <div class="info-row">
            <span class="info-label">PER</span>
            <span class="info-value">{{ basicView.per }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">PBR</span>
            <span class="info-value">{{ basicView.pbr }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">EPS</span>
            <span class="info-value">{{ basicView.eps }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">BPS</span>
            <span class="info-value">{{ basicView.bps }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">52주 최고</span>
            <span class="info-value">{{ basicView.week52High }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">52주 최저</span>
            <span class="info-value">{{ basicView.week52Low }}</span>
          </div>
        </div>
      </div>

      <!-- AI Analysis Tab -->
      <div v-else-if="activeTab === 'ai'" class="tab-content">
        <!-- AI Sub Tabs -->
        <div class="ai-sub-tabs">
          <button
            v-for="subTab in aiSubTabs"
            :key="subTab.key"
            :class="['ai-sub-tab', { active: aiSubTab === subTab.key }]"
            @click="aiSubTab = subTab.key"
          >
            {{ subTab.label }}
          </button>
        </div>

        <!-- 로딩 상태 -->
        <div v-if="aiLoading" class="ai-state-message">불러오는 중...</div>

        <!-- 분석 데이터 없음 / 에러 상태 -->
        <div v-else-if="!hasAnalysis" class="ai-state-message">
          이 종목은 AI 분석 데이터가 없습니다.
        </div>

        <!-- 정량분석 -->
        <div v-else-if="aiSubTab === 'quant' && quantAnalysis" class="ai-tab-sections">
          <!-- KIS·DART 피처 분포 차트 -->
          <div class="ai-section">
            <div class="section-label">KIS · DART 피처 분포</div>
            <div class="analysis-card">
              <div class="chart-wrapper">
                <div class="chart-placeholder">
                  <svg
                    v-if="quantChart"
                    class="chart-svg"
                    :viewBox="`0 0 ${quantChart.width} ${quantChart.height}`"
                    role="img"
                    aria-label="KIS 피처 막대 차트"
                  >
                    <line
                      :x1="0"
                      :y1="quantChart.baseline"
                      :x2="quantChart.width"
                      :y2="quantChart.baseline"
                      stroke="rgba(255,255,255,0.12)"
                      stroke-width="1"
                    />
                    <g v-for="(bar, i) in quantChart.bars" :key="i">
                      <rect
                        :x="bar.x"
                        :y="bar.y"
                        :width="bar.w"
                        :height="bar.h"
                        :fill="bar.color"
                        opacity="0.85"
                        rx="3"
                      />
                      <text
                        :x="bar.labelX"
                        :y="quantChart.baseline + 14"
                        text-anchor="middle"
                        class="chart-axis-label"
                      >{{ bar.label }}</text>
                    </g>
                  </svg>
                  <div v-else class="chart-empty">차트 데이터 없음</div>
                </div>
                <div class="stat-row">
                  <div v-for="(stat, idx) in quantAnalysis.stats" :key="idx" class="stat-item">
                    <div class="stat-label" v-html="stat.label.replace('\n', '<br>')"></div>
                    <div class="stat-value" :class="stat.class">{{ stat.value }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- KIS 피처 상세 -->
          <div class="ai-section">
            <div class="section-label">KIS 피처 상세</div>
            <div class="analysis-card">
              <div class="feature-list">
                <div v-for="(feature, idx) in quantAnalysis.kisFeatures" :key="idx" class="feature-row">
                  <div class="feature-label">{{ feature.label }}</div>
                  <div class="feature-source">{{ feature.source }}</div>
                  <div class="feature-bar-wrap">
                    <div class="feature-bar-track">
                      <div
                        class="feature-bar-fill"
                        :class="feature.class"
                        :style="{ width: `${feature.percent}%` }"
                      ></div>
                    </div>
                  </div>
                  <div class="feature-value" :class="feature.class">{{ feature.value }}</div>
                </div>
              </div>
            </div>
          </div>

          <!-- DART 재무지표 -->
          <div class="ai-section">
            <div class="section-label">DART 재무지표</div>
            <div class="analysis-card">
              <div class="card-sublabel">분기 기준 · DART API 공시 데이터</div>
              <div class="metrics-list">
                <div v-for="(metric, idx) in quantAnalysis.dartMetrics" :key="idx" class="metrics-row">
                  <div class="metrics-label">{{ metric.label }}</div>
                  <div class="metrics-value" :class="metric.class">{{ metric.value }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 감성분석 -->
        <div v-else-if="aiSubTab === 'sentiment' && sentimentAnalysis" class="ai-tab-sections">
          <!-- 감성점수 비교 -->
          <div class="ai-section">
            <div class="section-label">감성점수 비교</div>
            <div class="analysis-card">
              <div class="sentiment-compare">
                <div class="sentiment-box">
                  <div class="sentiment-header">종목 감성</div>
                  <div class="sentiment-meta">네이버금융 · {{ sentimentAnalysis.stockSentiment.newsCount }}건</div>
                  <div class="sentiment-score" :class="sentimentAnalysis.stockSentiment.tone">{{ signedFixed(sentimentAnalysis.stockSentiment.score) }}</div>
                  <div class="sentiment-badge" :class="sentimentAnalysis.stockSentiment.badge">{{ sentimentAnalysis.stockSentiment.label }}</div>
                  <div class="sentiment-footer">시간 가중 평균</div>
                </div>
                <div class="sentiment-box">
                  <div class="sentiment-header">시장 전반</div>
                  <div class="sentiment-meta">RSS 피드 · {{ sentimentAnalysis.marketSentiment.newsCount }}건</div>
                  <div class="sentiment-score" :class="sentimentAnalysis.marketSentiment.tone">{{ signedFixed(sentimentAnalysis.marketSentiment.score) }}</div>
                  <div class="sentiment-badge" :class="sentimentAnalysis.marketSentiment.badge">{{ sentimentAnalysis.marketSentiment.label }}</div>
                  <div class="sentiment-footer">시간 가중 평균</div>
                </div>
              </div>
              <div class="sentiment-diff">
                <div class="diff-label">종목 − 시장 전반</div>
                <div class="diff-value" :class="sentimentAnalysis.differenceTone">{{ signedFixed(sentimentAnalysis.difference) }} {{ sentimentAnalysis.difference >= 0 ? '↑' : '↓' }}</div>
              </div>
              <div class="diff-desc">
                {{ sentimentAnalysis.difference >= 0 ? '시장 평균 대비 긍정 신호 우위' : '시장 평균 대비 부정 신호 우위' }}
              </div>
            </div>
          </div>

          <!-- 종목 감성 상세 -->
          <div class="ai-section">
            <div class="section-label">종목 감성 상세</div>
            <div class="analysis-card">
              <div class="card-sublabel">stock_code = {{ company.symbol }} · news_analysis 테이블</div>

              <!-- 감성 척도 바 -->
              <div class="sentiment-scale">
                <div class="scale-labels">
                  <span>-1.0 부정</span>
                  <span>0 중립</span>
                  <span>긍정 +1.0</span>
                </div>
                <div class="scale-bar">
                  <div
                    class="market-marker"
                    :style="{ left: `${sentimentAnalysis.marketMarkerLeft}%` }"
                  ></div>
                  <div
                    class="stock-marker"
                    :style="{ left: `${sentimentAnalysis.stockMarkerLeft}%` }"
                  ></div>
                </div>
                <div class="scale-legend">
                  <span class="market-legend">▲ 시장 {{ signedFixed(sentimentAnalysis.marketSentiment.score) }}</span>
                  <span class="stock-legend">● 종목 {{ signedFixed(sentimentAnalysis.stockSentiment.score) }}</span>
                </div>
              </div>

              <!-- 수집 메타 -->
              <div class="meta-grid">
                <div class="meta-box">
                  <div class="meta-label">수집 뉴스</div>
                  <div class="meta-value">{{ sentimentAnalysis.stockSentiment.newsCount }}건</div>
                </div>
                <div class="meta-box">
                  <div class="meta-label">수집 범위</div>
                  <div class="meta-value-multi">{{ sentimentAnalysis.stockNewsRange }}</div>
                </div>
              </div>
            </div>
          </div>

          <!-- 시장 전반 감성 상세 -->
          <div class="ai-section">
            <div class="section-label">시장 전반 감성 상세</div>
            <div class="analysis-card">
              <div class="card-sublabel">stock_code = NULL · RSS 피드 · {{ sentimentAnalysis.marketSources }}</div>

              <!-- 긍정/중립/부정 분포 바 -->
              <div class="distribution-section">
                <div class="distribution-label">코스피 100 기준 종목 분포</div>
                <div class="distribution-bar">
                  <div class="dist-positive" :style="{ flex: sentimentAnalysis.marketDistribution.positive }"></div>
                  <div class="dist-neutral" :style="{ flex: sentimentAnalysis.marketDistribution.neutral }"></div>
                  <div class="dist-negative" :style="{ flex: sentimentAnalysis.marketDistribution.negative }"></div>
                </div>
                <div class="distribution-legend">
                  <span class="legend-positive">긍정 {{ sentimentAnalysis.marketDistribution.positiveCount }}개 ({{ sentimentAnalysis.marketDistribution.positive }}%)</span>
                  <span class="legend-neutral">중립 {{ sentimentAnalysis.marketDistribution.neutralCount }}개 ({{ sentimentAnalysis.marketDistribution.neutral }}%)</span>
                  <span class="legend-negative">부정 {{ sentimentAnalysis.marketDistribution.negativeCount }}개 ({{ sentimentAnalysis.marketDistribution.negative }}%)</span>
                </div>
              </div>

              <!-- 수집 메타 -->
              <div class="meta-grid">
                <div class="meta-box">
                  <div class="meta-label">분석 뉴스</div>
                  <div class="meta-value">{{ sentimentAnalysis.marketSentiment.newsCount }}건</div>
                </div>
                <div class="meta-box">
                  <div class="meta-label">출처</div>
                  <div class="meta-value-multi">{{ sentimentAnalysis.marketSources }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 시계열 -->
        <div v-else-if="aiSubTab === 'timeseries' && timeseriesAnalysis" class="ai-tab-sections">
          <!-- Prophet 예측 차트 -->
          <div class="ai-section">
            <div class="section-label">Prophet D+1~D+5 가격 예측</div>
            <div class="analysis-card">
              <div class="chart-wrapper">
                <div class="chart-placeholder">
                  <svg
                    v-if="forecastChart"
                    class="chart-svg"
                    :viewBox="`0 0 ${forecastChart.width} ${forecastChart.height}`"
                    role="img"
                    aria-label="Prophet D+1~D+5 가격 예측 차트"
                  >
                    <!-- 신뢰구간 영역 (yhat_upper ~ yhat_lower) -->
                    <polygon
                      v-if="forecastChart.band"
                      :points="forecastChart.band"
                      fill="#60A5FA"
                      opacity="0.15"
                    />
                    <!-- yhat 예측선 -->
                    <polyline
                      :points="forecastChart.line"
                      fill="none"
                      stroke="#F87171"
                      stroke-width="2"
                      stroke-linejoin="round"
                      stroke-linecap="round"
                    />
                    <!-- 데이터 포인트 -->
                    <circle
                      v-for="(d, i) in forecastChart.dots"
                      :key="i"
                      :cx="d.x"
                      :cy="d.y"
                      r="3"
                      fill="#F87171"
                    />
                    <!-- x축 라벨 (D+1~D+5) -->
                    <text
                      v-for="(l, i) in forecastChart.labels"
                      :key="`l${i}`"
                      :x="l.x"
                      :y="forecastChart.height - 4"
                      text-anchor="middle"
                      class="chart-axis-label"
                    >{{ l.text }}</text>
                  </svg>
                  <div v-else class="chart-empty">예측 데이터 부족</div>
                  <div class="chart-badge">Prophet · 120거래일 기반 · 신뢰구간 포함</div>
                </div>
                <div class="stat-row">
                  <div v-for="(stat, idx) in timeseriesAnalysis.stats" :key="idx" class="stat-item">
                    <div class="stat-label" v-html="stat.label.replace('\n', '<br>')"></div>
                    <div class="stat-value" :class="stat.class">{{ stat.value }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 예측 피처 상세 -->
          <div class="ai-section">
            <div class="section-label">예측 피처 상세</div>
            <div class="analysis-card">
              <div class="card-sublabel">D+1~D+5 선형회귀 기울기 · Gemini 입력 피처</div>
              <div class="feature-list">
                <div v-for="(feature, idx) in timeseriesAnalysis.features" :key="idx" class="feature-row">
                  <div class="feature-label">{{ feature.label }}</div>
                  <div class="feature-source">{{ feature.source }}</div>
                  <div class="feature-bar-wrap">
                    <div class="feature-bar-track">
                      <div
                        class="feature-bar-fill"
                        :class="feature.class"
                        :style="{ width: `${feature.percent}%` }"
                      ></div>
                    </div>
                  </div>
                  <div class="feature-value" :class="feature.class">{{ feature.value }}</div>
                </div>
              </div>

              <!-- 방향 요약 -->
              <div class="trend-summary">
                <div class="trend-summary-label">종합 추세 판단</div>
                <div class="trend-cards">
                  <div class="trend-card">
                    <div class="trend-emoji">{{ timeseriesAnalysis.priceUp ? '📈' : '📉' }}</div>
                    <div class="trend-label">가격</div>
                    <div class="trend-value" :class="timeseriesAnalysis.priceUp ? 'up' : 'nt'">
                      {{ timeseriesAnalysis.priceUp ? '상승' : '하락' }}
                    </div>
                  </div>
                  <div class="trend-card">
                    <div class="trend-emoji">{{ timeseriesAnalysis.volumeUp ? '📈' : '📉' }}</div>
                    <div class="trend-label">거래량</div>
                    <div class="trend-value" :class="timeseriesAnalysis.volumeUp ? 'up' : 'nt'">
                      {{ timeseriesAnalysis.volumeUp ? '증가' : '감소' }}
                    </div>
                  </div>
                  <div class="trend-card">
                    <div class="trend-emoji">{{ timeseriesAnalysis.confidenceGood ? '🎯' : '⚠️' }}</div>
                    <div class="trend-label">신뢰도</div>
                    <div class="trend-value" :class="timeseriesAnalysis.confidenceGood ? 'nt' : 'up'">
                      {{ timeseriesAnalysis.confidenceGood ? '양호' : '주의' }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 예측 구간 정보 -->
          <div class="ai-section">
            <div class="section-label">예측 구간 정보</div>
            <div class="analysis-card">
              <div class="card-sublabel">(yhat_upper − yhat_lower) / yhat 평균 · 값이 클수록 신뢰도 낮음</div>

              <!-- D+1~D+5 예측값 테이블 -->
              <div class="forecast-table">
                <div class="forecast-header">
                  <div class="forecast-cell">일자</div>
                  <div class="forecast-cell">yhat</div>
                  <div class="forecast-cell">yhat_upper</div>
                  <div class="forecast-cell">yhat_lower</div>
                  <div class="forecast-cell">불확실성</div>
                </div>
                <div v-for="(forecast, idx) in timeseriesAnalysis.forecasts" :key="idx" class="forecast-row">
                  <div class="forecast-cell day">{{ forecast.day }}</div>
                  <div class="forecast-cell yhat">{{ forecast.yhat }}</div>
                  <div class="forecast-cell up">{{ forecast.upper }}</div>
                  <div class="forecast-cell dn">{{ forecast.lower }}</div>
                  <div class="forecast-cell nt">{{ forecast.uncertainty }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Financial Tab -->
      <div v-else-if="activeTab === 'financial'" class="tab-content">
        <!-- 데이터 미연동 안내 배너 (기존 콘텐츠 위에 함께 표시) -->
        <div v-if="financialNotice" class="notice-banner">
          <svg class="notice-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span class="notice-text">{{ financialNotice }}</span>
        </div>

        <!-- 로딩 -->
        <div v-if="financialLoading" class="ai-state-message">불러오는 중...</div>

        <!-- 에러 -->
        <div v-else-if="financialError" class="ai-state-message">
          재무 데이터를 불러올 수 없습니다.
        </div>

        <div v-else-if="financialView" class="financial-content">
          <!-- Financial Ratios -->
          <div class="financial-section">
            <h4 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"/>
              </svg>
              주요 재무비율
            </h4>
            <div class="ratios-grid">
              <div v-for="(ratio, index) in financialView.ratios" :key="index" class="ratio-card">
                <div class="ratio-label">{{ ratio.label }}</div>
                <div class="ratio-value" :class="{ good: ratio.good }">
                  {{ ratio.value }}
                </div>
              </div>
            </div>
          </div>

          <!-- Annual Financial Data -->
          <div class="financial-section">
            <h4 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M3 10h18M3 14h18m-9-4v8m-7 0h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
              </svg>
              연간 실적 (단위: 억원)
            </h4>

            <!-- 연간 실적 없음 -->
            <div v-if="financialView.annual.length === 0" class="ai-state-message">
              연간 실적 데이터가 없습니다.
            </div>

            <div v-else class="financial-table">
              <div class="table-header">
                <div class="table-cell">연도</div>
                <div class="table-cell">매출액</div>
                <div class="table-cell">영업이익</div>
                <div class="table-cell">순이익</div>
                <div class="table-cell">EPS</div>
              </div>
              <div v-for="data in financialView.annual" :key="data.year" class="table-row">
                <div class="table-cell year">{{ data.year }}</div>
                <div class="table-cell">{{ formatNumber(data.revenue) }}</div>
                <div class="table-cell">{{ formatNumber(data.operating_profit) }}</div>
                <div class="table-cell">{{ formatNumber(data.net_profit) }}</div>
                <div class="table-cell">{{ formatNumber(data.eps) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Disclosure Tab -->
      <div v-else-if="activeTab === 'disclosure'" class="tab-content">
        <!-- 데이터 미연동 안내 배너 (기존 콘텐츠 위에 함께 표시) -->
        <div v-if="disclosureNotice" class="notice-banner">
          <svg class="notice-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span class="notice-text">{{ disclosureNotice }}</span>
        </div>

        <!-- 로딩 -->
        <div v-if="disclosureLoading" class="ai-state-message">불러오는 중...</div>

        <!-- 에러 -->
        <div v-else-if="disclosureError" class="ai-state-message">
          공시정보를 불러올 수 없습니다.
        </div>

        <div v-else class="disclosure-content">
          <div class="disclosure-section">
            <h4 class="section-title">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/>
              </svg>
              최근 공시
            </h4>

            <!-- 공시 없음 -->
            <div v-if="disclosureList.length === 0" class="ai-state-message">
              최근 공시가 없습니다.
            </div>

            <div v-else class="disclosure-list">
              <div
                v-for="disclosure in disclosureList"
                :key="disclosure.id"
                class="disclosure-item"
                :class="{ important: disclosure.important }"
              >
                <div class="disclosure-header">
                  <span class="disclosure-type">{{ disclosure.type }}</span>
                  <span class="disclosure-date">{{ disclosure.date }}</span>
                </div>
                <div class="disclosure-title">
                  <svg v-if="disclosure.important" width="14" height="14" viewBox="0 0 24 24" fill="currentColor" class="important-icon">
                    <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
                  </svg>
                  {{ disclosure.title }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Other tabs placeholder -->
      <div v-else class="tab-content">
        <div class="empty-tab">
          <p>{{ tabs.find(t => t.key === activeTab)?.label }} 정보를 불러오는 중...</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.company-detail-screen {
  min-height: 100vh;
  background: var(--color-bg-primary);
}

.content {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

.company-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-lg);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.company-logo {
  width: 56px;
  height: 56px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.logo-char {
  color: white;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  line-height: 1;
}

.company-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.company-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.company-symbol {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.sub-tabs {
  display: flex;
  gap: 0;
  margin-bottom: var(--spacing-xl);
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-lg);
  padding: 4px;
}

.sub-tab {
  flex: 1;
  padding: var(--spacing-sm) var(--spacing-xs);
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.3s ease;
  font-weight: var(--font-weight-medium);
}

.sub-tab.active {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.3);
}

.favorite-btn {
  background: none;
  border: none;
  padding: var(--spacing-xs);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.info-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-xl);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-md) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  gap: var(--spacing-md);
}

.info-row.full {
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-sm);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  flex-shrink: 0;
  min-width: 80px;
}

.info-value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: right;
  flex: 1;
  word-break: break-all;
}

.change-rate {
  margin-left: var(--spacing-xs);
  font-weight: var(--font-weight-semibold);
}

.change-rate.up { color: #F87171; }
.change-rate.dn { color: #60A5FA; }
.change-rate.nt { color: #2DD4BF; }

.info-value.link {
  color: #A78BFA;
  text-decoration: none;
  transition: color 0.3s ease;
}

.info-value.link:hover {
  color: #7C3AED;
}

.info-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
  width: 100%;
}

.empty-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  color: var(--color-text-tertiary);
}

/* 데이터 미연동 안내 배너 (DART/실시간 KIS 미연동 등) */
.notice-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.25);
  border-radius: var(--radius-lg);
}

.notice-banner .notice-icon {
  color: #F59E0B;
  flex-shrink: 0;
  margin-top: 1px;
}

.notice-banner .notice-text {
  font-size: var(--font-size-sm);
  line-height: 1.5;
  color: var(--color-text-secondary);
}

.action-buttons {
  display: flex;
  gap: var(--spacing-md);
  margin-top: var(--spacing-2xl);
}

.action-btn {
  flex: 1;
  padding: var(--spacing-md) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.action-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.3);
}

.action-btn:active {
  transform: translateY(0);
}

.action-btn.news {
  background: linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%);
  color: var(--color-text-inverse);
}

.action-btn.trade {
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  color: var(--color-text-inverse);
}

/* AI Analysis Tab */
.ai-analysis-content {
  padding-bottom: var(--spacing-lg);
}

.ai-compact-header {
  margin-bottom: var(--spacing-xl);
  padding: var(--spacing-lg);
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.08) 0%, rgba(124, 58, 237, 0.08) 100%);
  border-radius: var(--radius-lg);
  border: 1px solid rgba(79, 70, 229, 0.2);
}

.ai-score-compact {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
}

.score-circle-small {
  position: relative;
  width: 80px;
  height: 80px;
  flex-shrink: 0;
}

.score-circle-small svg {
  width: 100%;
  height: 100%;
  transform: scaleX(-1);
}

.score-content-small {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
}

.score-value-small {
  display: block;
  font-size: 24px;
  font-weight: var(--font-weight-bold);
  color: #F9FAFB;
  line-height: 1;
}

.ai-info-compact {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 0;
}

.ai-header-inline {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.ai-header-inline svg {
  flex-shrink: 0;
}

.ai-title-compact {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: #F9FAFB;
  margin: 0;
}

.badges-row {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.rating-badge-small {
  padding: 4px var(--spacing-md);
  background: linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%);
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: #fff;
  box-shadow: 0 2px 6px rgba(79, 70, 229, 0.3);
}

.recommendation-badge-small {
  padding: 4px var(--spacing-md);
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
}

.recommendation-badge-small.매수 {
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  color: #fff;
}

.recommendation-badge-small.보유 {
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  color: #fff;
}

.recommendation-badge-small.매도 {
  background: linear-gradient(135deg, #EF4444 0%, #DC2626 100%);
  color: #fff;
}

.ai-disclaimer {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: rgba(255, 255, 255, 0.02);
  border-radius: var(--radius-md);
  border: 1px solid rgba(255, 255, 255, 0.05);
  margin-top: var(--spacing-xl);
}

.ai-disclaimer svg {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
  margin-top: 2px;
}

.ai-disclaimer p {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);
  opacity: 0.8;
}

.analysis-section {
  margin-bottom: var(--spacing-xl);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: #F9FAFB;
  margin-bottom: var(--spacing-md);
}

.section-title svg {
  color: #A78BFA;
}

.section-title.warning svg {
  color: #F59E0B;
}

.reason-list,
.warning-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.reason-list li,
.warning-list li {
  padding-left: var(--spacing-lg);
  position: relative;
  font-size: var(--font-size-sm);
  color: #D1D5DB;
  line-height: 1.5;
}

.reason-list li::before {
  content: '✓';
  position: absolute;
  left: 0;
  color: #10B981;
  font-weight: var(--font-weight-bold);
}

.warning-list li::before {
  content: '⚠';
  position: absolute;
  left: 0;
  color: #F59E0B;
}

.strength-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.strength-item {
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.strength-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-sm);
}

.strength-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: #F9FAFB;
}

.strength-score {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: #A78BFA;
}

.strength-bar {
  height: 6px;
  background: #374151;
  border-radius: var(--radius-full);
  overflow: hidden;
}

.strength-progress {
  height: 100%;
  background: linear-gradient(90deg, #4F46E5 0%, #7C3AED 100%);
  border-radius: var(--radius-full);
  transition: width 0.5s ease;
}

/* Financial Tab */
.financial-content {
  padding-bottom: var(--spacing-lg);
}

.financial-section {
  margin-bottom: var(--spacing-xl);
}

.ratios-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-md);
}

.ratio-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  text-align: center;
  border: 1px solid rgba(255, 255, 255, 0.05);
  transition: all 0.3s ease;
}

.ratio-card:hover {
  transform: translateY(-2px);
  border-color: rgba(79, 70, 229, 0.3);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.2);
}

.ratio-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
  font-weight: var(--font-weight-medium);
}

.ratio-value {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.ratio-value.good {
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.financial-table {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.table-header {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1.2fr 1.2fr 1fr;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: rgba(79, 70, 229, 0.1);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.table-row {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1.2fr 1.2fr 1fr;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  transition: background 0.3s ease;
}

.table-row:last-child {
  border-bottom: none;
}

.table-row:hover {
  background: rgba(79, 70, 229, 0.05);
}

.table-cell {
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
  text-align: right;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.table-header .table-cell {
  color: #A78BFA;
  font-weight: var(--font-weight-semibold);
  text-align: right;
}

.table-cell.year {
  font-weight: var(--font-weight-bold);
  color: #A78BFA;
  justify-content: flex-start;
  text-align: left;
}

.table-header .table-cell:first-child {
  justify-content: flex-start;
  text-align: left;
}

/* Disclosure Tab */
.disclosure-content {
  padding-bottom: var(--spacing-lg);
}

.disclosure-section {
  margin-bottom: var(--spacing-xl);
}

.disclosure-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.disclosure-item {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  border: 1px solid rgba(255, 255, 255, 0.05);
  transition: all 0.3s ease;
  cursor: pointer;
}

.disclosure-item:hover {
  transform: translateY(-1px);
  border-color: rgba(79, 70, 229, 0.3);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.2);
}

.disclosure-item.important {
  border-color: rgba(245, 158, 11, 0.3);
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.05) 0%, rgba(217, 119, 6, 0.05) 100%);
}

.disclosure-item.important:hover {
  border-color: rgba(245, 158, 11, 0.5);
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.2);
}

.disclosure-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-xs);
}

.disclosure-type {
  font-size: var(--font-size-xs);
  color: #A78BFA;
  font-weight: var(--font-weight-semibold);
  padding: 2px var(--spacing-sm);
  background: rgba(79, 70, 229, 0.2);
  border-radius: var(--radius-sm);
}

.disclosure-item.important .disclosure-type {
  color: #F59E0B;
  background: rgba(245, 158, 11, 0.2);
}

.disclosure-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.disclosure-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-xs);
}

.important-icon {
  color: #F59E0B;
  flex-shrink: 0;
  margin-top: 2px;
}

/* AI Analysis Tab Styles */
.ai-sub-tabs {
  display: flex;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-lg);
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-lg);
  padding: 4px;
}

.ai-sub-tab {
  flex: 1;
  padding: var(--spacing-sm) var(--spacing-xs);
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.3s ease;
  font-weight: var(--font-weight-medium);
}

.ai-sub-tab.active {
  background: linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%);
  color: #fff;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.3);
}

.ai-tab-sections {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.ai-state-message {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  padding: var(--spacing-xl);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  text-align: center;
}

.ai-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.section-label {
  font-size: 10px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.section-label::before {
  content: '';
  width: 2px;
  height: 10px;
  background: #A78BFA;
  border-radius: 2px;
}

.analysis-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
}

.card-sublabel {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-md);
}

.chart-wrapper {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.chart-placeholder {
  position: relative;
  width: 100%;
  background: rgba(20, 27, 43, 0.5);
  border-radius: var(--radius-md);
  overflow: hidden;
}

/* viewBox 비율(320×150)을 그대로 유지 → 비왜곡/선명 렌더 */
.chart-svg {
  display: block;
  width: 100%;
  height: auto;
}

.chart-axis-label {
  fill: var(--color-text-tertiary);
  font-size: 10px;
  font-family: 'SF Mono', monospace;
}

.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}

.chart-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  font-size: 9px;
  color: var(--color-text-secondary);
  background: rgba(8, 12, 20, 0.8);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  font-family: 'SF Mono', monospace;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
}

.stat-item {
  background: rgba(30, 41, 59, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm) 4px;
  text-align: center;
  min-width: 0;
}

.stat-label {
  font-size: 7.5px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
  line-height: 1.4;
}

.stat-value {
  font-size: 12px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-value.up { color: #F87171; }
.stat-value.dn { color: #60A5FA; }
.stat-value.nt { color: #2DD4BF; }
.stat-value.yw { color: #FBBF24; }

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.feature-row {
  display: flex;
  align-items: center;
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  gap: var(--spacing-xs);
}

.feature-row:last-child {
  border-bottom: none;
}

.feature-label {
  font-size: 10px;
  color: var(--color-text-secondary);
  width: 84px;
  flex-shrink: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feature-source {
  font-size: 9px;
  color: var(--color-text-tertiary);
  width: 44px;
  flex-shrink: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feature-bar-wrap {
  flex: 1;
  min-width: 0;
  margin: 0 var(--spacing-xs);
}

.feature-bar-track {
  height: 5px;
  background: rgba(55, 65, 81, 0.5);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.feature-bar-fill {
  height: 5px;
  border-radius: var(--radius-full);
  transition: width 0.5s ease;
}

.feature-bar-fill.up { background: #F87171; }
.feature-bar-fill.dn { background: #60A5FA; }
.feature-bar-fill.nt { background: #2DD4BF; }
.feature-bar-fill.yw { background: #FBBF24; }
.feature-bar-fill.purple { background: #A78BFA; }

.feature-value {
  font-size: 11px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
  width: 62px;
  text-align: right;
  flex-shrink: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feature-value.up { color: #F87171; }
.feature-value.dn { color: #60A5FA; }
.feature-value.nt { color: #2DD4BF; }
.feature-value.yw { color: #FBBF24; }
.feature-value.purple { color: #A78BFA; }

.metrics-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.metrics-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.metrics-row:last-child {
  border-bottom: none;
}

.metrics-label {
  font-size: 10px;
  color: var(--color-text-secondary);
}

.metrics-value {
  font-size: 13px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
}

.metrics-value.up { color: #F87171; }
.metrics-value.dn { color: #60A5FA; }
.metrics-value.nt { color: #2DD4BF; }
.metrics-value.yw { color: #FBBF24; }

/* 감성분석 스타일 */
.sentiment-compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.sentiment-box {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}

.sentiment-box:first-child {
  border-right: 1px solid rgba(255, 255, 255, 0.1);
}

.sentiment-header {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.sentiment-meta {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.sentiment-score {
  font-size: 26px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
  line-height: 1;
  margin: 4px 0;
}

.sentiment-score.up { color: #F87171; }
.sentiment-score.dn { color: #60A5FA; }
.sentiment-score.nt { color: #2DD4BF; }

.sentiment-badge {
  padding: 3px 10px;
  border-radius: var(--radius-full);
  font-size: 10px;
  font-weight: var(--font-weight-semibold);
}

.sentiment-badge.positive {
  background: rgba(248, 113, 113, 0.12);
  color: #F87171;
  border: 1px solid rgba(248, 113, 113, 0.2);
}

.sentiment-badge.negative {
  background: rgba(96, 165, 250, 0.12);
  color: #60A5FA;
  border: 1px solid rgba(96, 165, 250, 0.2);
}

.sentiment-badge.neutral {
  background: rgba(45, 212, 191, 0.12);
  color: #2DD4BF;
  border: 1px solid rgba(45, 212, 191, 0.2);
}

.sentiment-footer {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-top: 4px;
}

.sentiment-diff {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-md);
  background: rgba(30, 41, 59, 0.5);
  margin-top: var(--spacing-md);
  border-radius: var(--radius-md);
}

.diff-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.diff-value {
  font-size: 12px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
  color: var(--color-text-primary);
}

.diff-value.up { color: #F87171; }
.diff-value.dn { color: #60A5FA; }
.diff-value.nt { color: #2DD4BF; }

.diff-desc {
  font-size: 10px;
  color: var(--color-text-secondary);
  margin-top: var(--spacing-sm);
}

.sentiment-scale {
  margin-bottom: var(--spacing-md);
}

.scale-labels {
  display: flex;
  justify-content: space-between;
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

.scale-bar {
  position: relative;
  height: 10px;
  background: linear-gradient(to right, #60A5FA, rgba(55, 65, 81, 0.5) 50%, #F87171);
  border-radius: var(--radius-full);
}

.market-marker {
  position: absolute;
  top: -3px;
  transform: translateX(-50%);
  width: 2px;
  height: 16px;
  background: #FBBF24;
  border-radius: 1px;
}

.stock-marker {
  position: absolute;
  top: -5px;
  transform: translateX(-50%);
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #F87171;
  border: 2px solid #1E293B;
  box-shadow: 0 0 8px rgba(248, 113, 113, 0.5);
}

.scale-legend {
  display: flex;
  justify-content: space-between;
  font-size: 8.5px;
  margin-top: 6px;
  font-family: 'SF Mono', monospace;
}

.market-legend {
  color: #FBBF24;
}

.stock-legend {
  color: #F87171;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-xs);
}

.meta-box {
  background: rgba(30, 41, 59, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm);
  min-width: 0;
}

.meta-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 3px;
}

.meta-value {
  font-size: 15px;
  font-weight: var(--font-weight-bold);
  font-family: 'SF Mono', monospace;
  color: var(--color-text-primary);
}

.meta-value-multi {
  font-size: 10px;
  color: var(--color-text-secondary);
  line-height: 1.4;
  margin-top: 2px;
  overflow-wrap: anywhere;
}

.distribution-section {
  margin-bottom: var(--spacing-md);
}

.distribution-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

.distribution-bar {
  display: flex;
  height: 8px;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.dist-positive {
  background: #F87171;
  border-radius: 3px 0 0 3px;
}

.dist-neutral {
  background: #4A5568;
}

.dist-negative {
  background: #60A5FA;
  border-radius: 0 3px 3px 0;
}

.distribution-legend {
  display: flex;
  justify-content: space-between;
  gap: 4px;
  font-size: 8.5px;
  margin-top: 5px;
  font-family: 'SF Mono', monospace;
  white-space: nowrap;
}

.legend-positive { color: #F87171; }
.legend-neutral { color: #4A5568; }
.legend-negative { color: #60A5FA; }

/* 시계열 스타일 */
.trend-summary {
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.trend-summary-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-sm);
}

.trend-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-xs);
}

.trend-card {
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.2);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm);
  text-align: center;
}

.trend-card:last-child {
  background: rgba(52, 211, 153, 0.08);
  border: 1px solid rgba(52, 211, 153, 0.2);
}

.trend-emoji {
  font-size: 18px;
  margin-bottom: 4px;
}

.trend-label {
  font-size: 9px;
  color: var(--color-text-tertiary);
}

.trend-value {
  font-size: 12px;
  font-weight: var(--font-weight-bold);
  margin-top: 2px;
}

.trend-value.up { color: #F87171; }
.trend-value.nt { color: #2DD4BF; }

.forecast-table {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.forecast-header {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  font-size: 8.5px;
  color: var(--color-text-tertiary);
}

.forecast-row {
  display: flex;
  padding: 7px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  font-size: 10.5px;
  font-family: 'SF Mono', monospace;
}

.forecast-row:last-child {
  border-bottom: none;
}

.forecast-cell {
  flex: 1;
  min-width: 0;
  text-align: right;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding-left: 2px;
}

/* 일자 열은 좁게 고정, 숫자 열에 여유 배분 */
.forecast-cell:first-child {
  flex: 0 0 38px;
  text-align: left;
  padding-left: 0;
}

.forecast-cell.day {
  color: var(--color-text-tertiary);
}

.forecast-cell.yhat {
  color: #F9FAFB;
  font-weight: var(--font-weight-bold);
}
.forecast-cell.up { color: #F87171; }
.forecast-cell.dn { color: #60A5FA; }
.forecast-cell.nt { color: #2DD4BF; }
</style>

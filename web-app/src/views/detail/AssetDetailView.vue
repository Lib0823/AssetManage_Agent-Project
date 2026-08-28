<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import AssetTabs from '@/components/common/AssetTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { assetApi, overseasApi, marketApi, bondApi } from '@/services/api'
import { isKisOutageError } from '@/utils/kisStatus'
import { useRealtimeStore } from '@/stores/realtime'
import { logger } from '@/utils/logger'
import { bondLotKey, buildBondLotQuery, formatAmount, formatKisDate, formatQuantity } from '@/utils/bond'

const router = useRouter()
const route = useRoute()

const realtime = useRealtimeStore()

// 실시간 tick 구독 상한. KIS 연결당 구독 심볼 상한(~41, MUST-VERIFY)을
// TradingView(호가+체결가) 등 다른 화면과 공유하므로, 자산 상세는 가시
// 보유 종목 중 상위 N개만 보수적으로 구독한다.
const REALTIME_SYMBOL_CAP = 15

// 현재 활성화된 tick 구독 해제 함수 목록 (탭 전환/언마운트 시 정리)
let tickUnsubs = []

const tabs = ref({ main: 'stocks', sub: 'domestic' })
const loading = ref(false)
const errorMessage = ref('')

// 국내 주식 데이터 (API에서 가져옴)
const domesticStocks = ref([])

// 해외 주식(US) 데이터 (overseasApi.getBalance() 기반, USD 원본)
const overseasStocks = ref([])

// 해외 안내 메시지 (quote 비활성 등 graceful degrade)
const overseasNotice = ref('')

// 국내 KIS 연동 불가(점검/네트워크/서버 오류) 여부.
// 하드 엔드포인트(보유/잔고) 실패 시 true → 오해를 부르는 0원 대신 점검 안내 표시.
const domesticKisDown = ref(false)

// USD → KRW 환율 (marketApi.getExchangeRates() 의 USD rate, 없으면 null → 병기 생략)
const usdRate = ref(null)

// 현재 선택된 탭에 따른 주식 데이터
const currentStocks = computed(() => {
  return tabs.value.sub === 'domestic' ? domesticStocks.value : overseasStocks.value
})

// 국내 주식 요약 (balance summary 기반, API에서 가져옴)
const domesticSummary = ref({
  totalValuation: 0,
  totalProfit: 0,
  profitPercent: 0,
  totalPurchase: 0,
  d2Deposit: 0
})

// 해외 주식 요약 (USD 원본)
const overseasSummary = ref({
  totalValuation: 0,
  totalProfit: 0,
  profitPercent: 0,
  totalPurchase: 0,
  d2Deposit: 0
})

// 현재 선택된 탭에 따른 요약
const currentSummary = computed(() => {
  return tabs.value.sub === 'domestic' ? domesticSummary.value : overseasSummary.value
})

// 현재 탭의 통화 단위
const isOverseas = computed(() => tabs.value.sub !== 'domestic')

// 보유 채권 (매수 로트 단위). 채권 탭을 처음 열 때만 조회한다 — KIS 호출을 아끼기 위해
// 마운트 시점이 아니라 탭 전환 시점에 lazy 로드한다.
const bondHoldings = ref([])
const bondTotalBuyAmount = ref(0)
const bondNotice = ref('')
const bondLoading = ref(false)
const bondLoaded = ref(false)

// 현금 데이터 (API에서 가져옴, KRW만 지원)
const cashDetail = ref({
  krw: {
    availableForOrder: 0,
    availableForWithdrawal: 0
  }
})

// Load holdings (보유 주식)
const loadHoldings = async () => {
  try {
    loading.value = true
    domesticKisDown.value = false
    const response = await assetApi.getHoldings()

    // KIS API 응답 구조에 맞춰 파싱
    if (response.data?.output1) {
      domesticStocks.value = response.data.output1.map(stock => ({
        symbol: stock.pdno,  // 종목코드
        name: stock.prdt_name,  // 종목명
        nameEn: stock.prdt_name,
        currentPrice: parseInt(stock.prpr),  // 현재가
        quantity: parseInt(stock.hldg_qty),  // 보유수량
        avgPrice: parseInt(stock.pchs_avg_pric),  // 평균매입가
        purchasePrice: parseInt(stock.pchs_amt),  // 매입금액
        profit: parseInt(stock.evlu_pfls_amt),  // 평가손익금액
        profitPercent: parseFloat(stock.evlu_pfls_rt),  // 평가손익율
        logo: ''
      }))
    }
  } catch (error) {
    logger.debug('Failed to load holdings:', error)
    errorMessage.value = '보유 주식 정보를 불러오는데 실패했습니다'
    if (isKisOutageError(error)) {
      domesticKisDown.value = true
    }
  } finally {
    loading.value = false
  }
}

// Load balance (잔고)
const loadBalance = async () => {
  try {
    const response = await assetApi.getBalance()

    // KIS API 응답 구조에 맞춰 파싱 (output2[0] = 잔고 요약)
    const balanceData = response.data?.balance?.output2?.[0]
    if (balanceData) {
      // KIS inquire-balance output2 는 예수금(dnca_tot_amt)·D+2예수금(prvs_rcdl_excc_amt)을 제공한다.
      // 주문가능현금(ord_psbl_cash)/출금가능(wdrw_psbl_tot_amt)은 이 TR 응답에 없어(매수가능조회 소관)
      // 예전 매핑이 항상 0으로 나왔다. 실제 존재하는 예수금 필드로 매핑한다.
      cashDetail.value.krw.availableForOrder = parseInt(balanceData.dnca_tot_amt || 0)          // 예수금총금액
      cashDetail.value.krw.availableForWithdrawal = parseInt(balanceData.prvs_rcdl_excc_amt || 0)  // D+2 예수금(가수도정산금액)

      // 국내 주식 요약
      domesticSummary.value = {
        totalValuation: parseInt(balanceData.tot_evlu_amt || 0),        // 총평가금액
        totalProfit: parseInt(balanceData.evlu_pfls_smtl_amt || 0),     // 평가손익합계
        profitPercent: parseFloat(balanceData.asst_icdc_erng_rt || 0),  // 자산증감수익률
        totalPurchase: parseInt(balanceData.pchs_amt_smtl_amt || 0),    // 매입금액합계
        d2Deposit: parseInt(balanceData.prvs_rcdl_excc_amt || 0)        // D+2 예수금(가수도정산금액)
      }
    }
  } catch (error) {
    logger.debug('Failed to load balance:', error)
    if (isKisOutageError(error)) {
      domesticKisDown.value = true
    }
  }
}

const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

// Load USD→KRW 환율 (있으면 KRW 병기, 없으면 생략 — graceful)
const loadExchangeRate = async () => {
  try {
    const res = await marketApi.getExchangeRates()
    const list = res && res.success && Array.isArray(res.data) ? res.data : []
    const usd = list.find((r) => r && (r.currency === 'USD' || r.currency === 'USD/KRW'))
    usdRate.value = usd && Number.isFinite(Number(usd.rate)) ? Number(usd.rate) : null
  } catch (error) {
    logger.debug('Failed to load exchange rate:', error)
    usdRate.value = null
  }
}

// Load overseas (US) balance — graceful degrade: 실패/notice 시 빈 목록 + 안내
const loadOverseasBalance = async () => {
  try {
    const response = await overseasApi.getBalance()
    // ApiResponse 봉투: { success, data: OverseasBalanceResponse }
    const data = response?.data ?? null

    if (!data) {
      overseasStocks.value = []
      overseasSummary.value = { ...overseasSummary.value, totalValuation: 0, totalProfit: 0, profitPercent: 0, totalPurchase: 0, d2Deposit: 0 }
      overseasNotice.value = response?.notice || '해외 잔고를 불러올 수 없습니다'
      return
    }

    overseasNotice.value = data.notice || ''

    const holdings = Array.isArray(data.holdings) ? data.holdings : []
    overseasStocks.value = holdings.map((h) => ({
      symbol: h.symbol,
      name: h.name || h.symbol,
      nameEn: h.name || h.symbol,
      exchange: h.exchange || h.ovrs_excg_cd || null,
      currentPrice: num(h.currentPrice),
      quantity: num(h.quantity),
      orderableQty: num(h.orderableQty),
      avgPrice: num(h.avgPrice),
      purchasePrice: num(h.avgPrice) * num(h.quantity),
      profit: num(h.evalProfitLoss),
      profitPercent: num(h.profitLossRate),
      logo: ''
    }))

    const totalPurchase = num(data.totalPurchase)
    const totalProfit = num(data.totalProfitLoss)
    overseasSummary.value = {
      totalValuation: num(data.totalEval),
      totalProfit,
      profitPercent: totalPurchase > 0 ? (totalProfit / totalPurchase) * 100 : 0,
      totalPurchase,
      d2Deposit: 0
    }
  } catch (error) {
    logger.debug('Failed to load overseas balance:', error)
    overseasStocks.value = []
    overseasSummary.value = { totalValuation: 0, totalProfit: 0, profitPercent: 0, totalPurchase: 0, d2Deposit: 0 }
    overseasNotice.value = '해외 잔고 연동 중 오류가 발생했습니다'
  }
}

/**
 * 보유 채권 조회 (채권 탭 lazy 로드).
 *
 * 금액은 **매수금액 기준**이다 — KIS 채권 잔고 응답에 평가금액 필드가 없다.
 * 조회 실패는 빈 목록 + 안내로 degrade 한다(0원으로 조용히 표시하면 오해를 부른다).
 */
const loadBondBalance = async () => {
  if (bondLoaded.value || bondLoading.value) return
  bondLoading.value = true
  bondNotice.value = ''
  try {
    const res = await bondApi.getBalance()
    const data = res?.data ?? null
    bondHoldings.value = Array.isArray(data?.holdings) ? data.holdings : []
    bondTotalBuyAmount.value = num(data?.totalBuyAmount)
    bondNotice.value = data?.notice || ''
    bondLoaded.value = true
  } catch (error) {
    logger.debug('Failed to load bond balance:', error)
    bondHoldings.value = []
    bondTotalBuyAmount.value = 0
    bondNotice.value = isKisOutageError(error)
      ? 'KIS 점검 중이거나 일시적인 연동 오류로 채권 잔고를 불러올 수 없어요.'
      : '채권 잔고를 불러오지 못했습니다'
  } finally {
    bondLoading.value = false
  }
}

// ── 실시간 체결가(tick) 구독 ──────────────────────────────────────────────
// 현재 탭의 가시 보유 종목 심볼만 tick 구독 → 카드의 currentPrice 라이브 갱신.
// degrade(연결 disabled/reconnecting/closed)일 때는 구독만 보류하고
// 기존 정적 잔고(currentPrice)를 그대로 유지한다(절대 0/null로 덮어쓰지 않음).

// 모든 활성 tick 구독 해제.
const clearTickSubscriptions = () => {
  for (const unsub of tickUnsubs) {
    try {
      unsub()
    } catch (e) {
      logger.debug('[asset] tick unsubscribe error:', e)
    }
  }
  tickUnsubs = []
}

// 현재 탭(국내/해외)의 가시 보유 종목 중 상한 내 심볼을 tick 구독.
// 탭 전환/리스트 갱신 시 호출되며, 기존 구독을 먼저 정리한 뒤 재구독한다.
const syncTickSubscriptions = () => {
  clearTickSubscriptions()

  const stocks = currentStocks.value
  if (!Array.isArray(stocks) || stocks.length === 0) return

  const overseas = isOverseas.value
  const market = overseas ? 'US' : 'KR'

  // 상한(top-N) 적용 — 보유 목록 앞에서부터.
  const visible = stocks.slice(0, REALTIME_SYMBOL_CAP)

  for (const stock of visible) {
    if (!stock || stock.symbol == null) continue
    // US tick은 거래소 코드 필요. goToTrade와 동일한 기본값(NASD) 사용.
    const exchange = overseas ? (stock.exchange || 'NASD') : null
    const target = stock // 콜백 클로저로 해당 카드 객체 참조

    const unsub = realtime.subscribeTick(market, stock.symbol, exchange, (msg) => {
      // REST DTO 호환: currentPrice 우선, 없으면 price.
      const next = msg.currentPrice ?? msg.price
      const n = Number(next)
      // 유효한 양수 가격만 반영(0/NaN은 degrade로 간주하고 기존값 유지).
      if (Number.isFinite(n) && n > 0) {
        target.currentPrice = n
      }
    })
    tickUnsubs.push(unsub)
  }
}

// URL 쿼리에서 초기 탭 설정 및 데이터 로드
onMounted(async () => {
  if (route.query.main) {
    tabs.value.main = route.query.main
    if (route.query.sub) {
      tabs.value.sub = route.query.sub
    }
  }

  // 채권 탭으로 바로 진입한 경우(?main=bonds)에도 데이터가 비지 않게 한다.
  if (tabs.value.main === 'bonds') {
    loadBondBalance()
  }

  // Load data
  await Promise.all([loadHoldings(), loadBalance(), loadOverseasBalance(), loadExchangeRate()])

  // 데이터 로드 후 현재 탭 기준 tick 구독 시작 (주식 탭에서만 의미).
  syncTickSubscriptions()
})

// 탭(main: stocks/cash, sub: domestic/overseas) 변경 시 구독 재동기화.
// 현금 탭에서는 가시 종목이 없으므로 구독이 모두 해제된다.
watch(
  () => [tabs.value.main, tabs.value.sub],
  () => {
    if (tabs.value.main === 'stocks') {
      syncTickSubscriptions()
    } else {
      clearTickSubscriptions()
    }
    // 채권 탭을 처음 열 때만 잔고를 조회한다 (loadBondBalance 가 중복 호출을 막는다).
    if (tabs.value.main === 'bonds') {
      loadBondBalance()
    }
  }
)

// 보유 목록이 비동기로 채워질 수 있으므로, 현재 탭 목록 변경 시에도 재동기화.
watch(currentStocks, () => {
  if (tabs.value.main === 'stocks') {
    syncTickSubscriptions()
  }
})

onBeforeUnmount(() => {
  clearTickSubscriptions()
})

const formatNumber = (n) => {
  return new Intl.NumberFormat('ko-KR').format(Number(n) || 0)
}

// USD 금액 표시 (소수 2자리)
const formatUsd = (n) => {
  return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(n) || 0)
}

// 통화 단위 금액 포맷 (국내 KRW / 해외 USD)
const formatMoney = (n) => {
  return isOverseas.value ? `$${formatUsd(n)}` : `${formatNumber(n)}원`
}

const goToNews = (stock) => {
  router.push(`/news?symbol=${stock.symbol}`)
}

const goToTrade = (stock) => {
  if (isOverseas.value) {
    const exchange = stock.exchange || 'NASD'
    router.push(`/trading/${stock.symbol}?market=US&exchange=${exchange}`)
    return
  }
  router.push(`/trading/${stock.symbol}`)
}

const goToInfo = (stock) => {
  router.push(`/company/${stock.symbol}`)
}

// 보유 로트 → 채권 상세. buyDate/buySeq 를 쿼리로 함께 넘긴다(매도에 필수).
const goToBondDetail = (lot) => {
  if (!lot?.bondCode) return
  router.push({ path: `/bonds/${lot.bondCode}`, query: buildBondLotQuery(lot) })
}
</script>

<template>
  <div class="asset-detail-screen">
    <AppHeader title="자산 상세 정보" showBack />

    <div class="content">
      <!-- Tabs -->
      <AssetTabs v-model="tabs" :showSubTabs="tabs.main === 'stocks'" />

      <!-- 현금 화면 -->
      <div v-if="tabs.main === 'cash'" class="cash-section">
        <!-- KRW Card -->
        <div class="cash-card">
          <div class="cash-header">
            <h3 class="cash-title">KRW</h3>
            <span class="cash-total">{{ domesticKisDown ? '—' : formatNumber(cashDetail.krw.availableForOrder) + '원' }}</span>
          </div>
          <KisMaintenanceNotice v-if="domesticKisDown" variant="banner" />
          <div v-else class="cash-details">
            <div class="cash-detail-item">
              <span class="detail-label">주문가능금</span>
              <span class="detail-value">{{ formatNumber(cashDetail.krw.availableForOrder) }}원</span>
            </div>
            <div class="cash-detail-item">
              <span class="detail-label">출금가능금</span>
              <span class="detail-value">{{ formatNumber(cashDetail.krw.availableForWithdrawal) }}원</span>
            </div>
          </div>
        </div>

        <!-- USD Card (해외 자산 요약 — KIS 잔고 기준) -->
        <div class="cash-card">
          <div class="cash-header">
            <h3 class="cash-title">USD</h3>
            <span class="cash-total">${{ formatUsd(overseasSummary.totalValuation) }}</span>
          </div>
          <p v-if="overseasNotice" class="cash-notice">{{ overseasNotice }}</p>
          <div class="cash-details">
            <div class="cash-detail-item">
              <span class="detail-label">평가금액</span>
              <span class="detail-value">
                {{ overseasNotice && overseasSummary.totalValuation === 0 ? '—' : '$' + formatUsd(overseasSummary.totalValuation) }}
              </span>
            </div>
            <div class="cash-detail-item">
              <span class="detail-label">매입금액</span>
              <span class="detail-value">
                {{ overseasNotice && overseasSummary.totalPurchase === 0 ? '—' : '$' + formatUsd(overseasSummary.totalPurchase) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!--
        채권 화면.
        이 분기가 없으면 채권 탭이 빈 화면을 보여준다(현금/주식만 v-if 로 걸려 있었다).
        매도는 로트 단위이므로 각 행이 buyDate/buySeq 를 달고 상세로 이동한다.
      -->
      <div v-if="tabs.main === 'bonds'" class="bond-section">
        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">보유 채권</h3>
          </div>
          <div class="summary-main">
            <div class="main-info">
              <!-- KIS 채권 잔고에는 평가금액 필드가 없다. 매수금액임을 밝힌다. -->
              <span class="main-label">총매수금액 (평가금액 아님)</span>
              <span class="main-value">{{ formatAmount(bondTotalBuyAmount) }}<span class="unit">원</span></span>
            </div>
          </div>
        </div>

        <KisMaintenanceNotice v-if="bondNotice" variant="banner" :message="bondNotice" />

        <div v-if="bondLoading" class="empty-state">불러오는 중...</div>

        <div v-else-if="bondHoldings.length === 0" class="empty-state">
          보유 중인 채권이 없습니다
        </div>

        <div v-else class="bond-list">
          <button
            v-for="lot in bondHoldings"
            :key="bondLotKey(lot)"
            type="button"
            class="bond-row"
            @click="goToBondDetail(lot)"
          >
            <span class="bond-row-main">
              <span class="bond-row-name">{{ lot.bondName || lot.bondCode }}</span>
              <span class="bond-row-sub">
                매수일 {{ formatKisDate(lot.buyDate) }} · 수량 {{ formatQuantity(lot.quantity) }}
              </span>
            </span>
            <span class="bond-row-amount">{{ formatAmount(lot.buyAmount) }}원</span>
            <span class="bond-row-arrow">›</span>
          </button>
        </div>
      </div>

      <!-- 주식 화면 -->
      <div v-if="tabs.main === 'stocks'">
        <!-- 국내 KIS 점검 중: 오해를 부르는 0원 요약/빈 목록 대신 안내 카드 표시 -->
        <KisMaintenanceNotice v-if="!isOverseas && domesticKisDown" variant="card" />
        <template v-else>
        <!-- Summary Card -->
        <div class="summary-card">
          <div class="summary-header">
            <h3 class="summary-title">{{ tabs.sub === 'domestic' ? '국내' : '해외 (US)' }} 주식</h3>
            <div class="profit-badge">
              <span class="profit-icon">{{ currentSummary.profitPercent >= 0 ? '▲' : '▼' }}</span>
              <span class="profit-percent">{{ Number(currentSummary.profitPercent || 0).toFixed(2) }}%</span>
            </div>
          </div>

          <p v-if="isOverseas && overseasNotice" class="summary-notice">{{ overseasNotice }}</p>

          <div class="summary-main">
            <div class="main-info">
              <span class="main-label">총평가금액</span>
              <span class="main-value">
                <template v-if="isOverseas">${{ formatUsd(currentSummary.totalValuation) }}</template>
                <template v-else>{{ formatNumber(currentSummary.totalValuation) }}<span class="unit">원</span></template>
              </span>
              <span v-if="isOverseas && usdRate" class="krw-paren">≈ ₩{{ formatNumber(Math.round(currentSummary.totalValuation * usdRate)) }}</span>
            </div>
            <div class="profit-info">
              <span class="profit-label">총평가손익</span>
              <span class="profit-value" :class="currentSummary.totalProfit >= 0 ? 'positive' : 'negative'">{{ formatMoney(currentSummary.totalProfit) }}</span>
            </div>
          </div>

          <div class="summary-details">
            <div class="detail-row">
              <div class="detail-item">
                <span class="detail-label">{{ isOverseas ? '예수금(USD)' : 'D+2예수금' }}</span>
                <span class="detail-value">{{ isOverseas ? '—' : formatNumber(currentSummary.d2Deposit) + '원' }}</span>
              </div>
              <div class="detail-divider"></div>
              <div class="detail-item">
                <span class="detail-label">총매입금액</span>
                <span class="detail-value">{{ formatMoney(currentSummary.totalPurchase) }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Stock List -->
        <div class="stock-list">
          <!-- Empty State -->
          <div v-if="currentStocks.length === 0" class="empty-state">
            <template v-if="tabs.sub === 'domestic'">보유 중인 국내 주식이 없습니다</template>
            <template v-else>{{ overseasNotice || '보유 중인 해외 주식이 없습니다' }}</template>
          </div>

          <div v-for="stock in currentStocks" :key="stock.symbol" class="stock-card-enhanced">
            <!-- Stock Info Header -->
            <div class="stock-header">
              <div class="stock-logo">
                <img v-if="stock.logo" :src="stock.logo" :alt="stock.name" />
                <div v-else class="logo-placeholder">{{ stock.symbol?.charAt(0) }}</div>
              </div>
              <div class="stock-info">
                <div class="stock-name-row">
                  <span class="stock-name">{{ stock.name }}</span>
                  <span class="stock-symbol">{{ stock.symbol }}</span>
                </div>
                <div class="stock-price-row">
                  <span class="current-price">
                    <template v-if="isOverseas">
                      {{ stock.currentPrice > 0 ? '$' + formatUsd(stock.currentPrice) : '—' }}
                    </template>
                    <template v-else>₩{{ formatNumber(stock.currentPrice) }}</template>
                  </span>
                  <span class="profit-badge" :class="stock.profitPercent >= 0 ? 'positive' : 'negative'">
                    {{ stock.profitPercent >= 0 ? '+' : '' }}{{ Number(stock.profitPercent || 0).toFixed(2) }}%
                  </span>
                </div>
                <div v-if="isOverseas && stock.currentPrice > 0 && usdRate" class="stock-krw-paren">
                  ≈ ₩{{ formatNumber(Math.round(stock.currentPrice * usdRate)) }}
                </div>
              </div>
            </div>

            <!-- Stock Details -->
            <div class="stock-details">
              <div class="detail-item">
                <span class="detail-label">매입금액</span>
                <span class="detail-value">{{ formatMoney(stock.purchasePrice) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">평가손익</span>
                <span class="detail-value profit" :class="stock.profit >= 0 ? 'positive' : 'negative'">
                  {{ stock.profit >= 0 ? '+' : '' }}{{ formatMoney(stock.profit) }}
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">매도 가능 수량</span>
                <span class="detail-value">{{ formatNumber(isOverseas ? (stock.orderableQty || stock.quantity) : stock.quantity) }}주</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">평균단가</span>
                <span class="detail-value">{{ isOverseas ? '$' + formatUsd(stock.avgPrice) : formatNumber(stock.avgPrice) + '원' }}</span>
              </div>
            </div>

            <!-- Action Buttons -->
            <div class="action-buttons">
              <button class="action-btn news-btn" @click="goToNews(stock)">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <path d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h10a2 2 0 012 2v1m2 13a2 2 0 01-2-2V7m2 13a2 2 0 002-2V9a2 2 0 00-2-2h-2m-4-3H9M7 16h6M7 8h6v4H7V8z" stroke="currentColor" stroke-width="2"/>
                </svg>
                뉴스
              </button>
              <button class="action-btn trade-btn" @click="goToTrade(stock)">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                거래
              </button>
              <button class="action-btn info-btn" @click="goToInfo(stock)">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <path d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                정보
              </button>
            </div>
          </div>
        </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.asset-detail-screen {
  min-height: 100vh;
  background: var(--color-bg-primary);
}

.content {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

.summary-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-xl);
  margin-bottom: var(--spacing-lg);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

/* 헤더 영역 */
.summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.summary-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.profit-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(16, 185, 129, 0.25) 100%);
  padding: 6px 14px;
  border-radius: var(--radius-full);
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.profit-icon {
  font-size: var(--font-size-sm);
  color: var(--color-stock-up);
}

.profit-percent {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-stock-up);
}

/* 메인 정보 영역 */
.summary-main {
  margin-bottom: var(--spacing-lg);
}

.main-info {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-md);
}

.main-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.main-value {
  font-size: 32px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: 1.2;
}

.krw-paren {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  margin-top: var(--spacing-xxs);
}

.summary-notice {
  margin: 0 0 var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-md);
}

.cash-notice {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.stock-krw-paren {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.unit {
  font-size: 20px;
  color: var(--color-text-secondary);
  margin-left: 4px;
}

.profit-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-md);
  background: rgba(16, 185, 129, 0.1);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-stock-up);
}

.profit-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.profit-value {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.profit-value.positive {
  color: var(--color-stock-up);
}

.profit-value.negative {
  color: var(--color-stock-down);
}

/* 상세 정보 영역 */
.summary-details {
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.detail-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.detail-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.detail-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.detail-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.detail-divider {
  width: 1px;
  height: 40px;
  background: rgba(255, 255, 255, 0.1);
}

.stock-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.empty-state {
  padding: var(--spacing-xl);
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--radius-lg);
}

/* Enhanced Stock Card */
.stock-card-enhanced {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* Stock Header */
.stock-header {
  display: flex;
  gap: var(--spacing-md);
  align-items: center;
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.stock-logo {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg-secondary);
  flex-shrink: 0;
}

.stock-logo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.logo-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  color: white;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.stock-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.stock-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stock-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.stock-symbol {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

.stock-price-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.current-price {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.profit-badge {
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

.profit-badge.positive {
  background: rgba(16, 185, 129, 0.2);
  color: var(--color-stock-up);
}

.profit-badge.negative {
  background: rgba(239, 68, 68, 0.2);
  color: var(--color-stock-down);
}

/* Stock Details */
.stock-details {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--radius-md);
}

.stock-card-enhanced .detail-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxs);
}

.stock-card-enhanced .detail-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.stock-card-enhanced .detail-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.stock-card-enhanced .detail-value.profit.positive {
  color: var(--color-stock-up);
}

.stock-card-enhanced .detail-value.profit.negative {
  color: var(--color-stock-down);
}

/* Action Buttons */
.action-buttons {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm);
  background: var(--color-bg-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover {
  background: var(--color-bg-highlight);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.action-btn:active {
  transform: scale(0.98);
}

/* 현금 섹션 */
.cash-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* ── 채권 (보유 로트 목록) ── */
.bond-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.bond-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.bond-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  padding: var(--spacing-md);
  border: none;
  background: var(--color-bg-secondary);
  border-radius: var(--radius-lg);
  cursor: pointer;
  text-align: left;
}

.bond-row:hover {
  background: var(--color-bg-tertiary);
}

.bond-row-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.bond-row-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bond-row-sub {
  font-size: 11px;
  color: var(--color-text-secondary);
}

.bond-row-amount {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  white-space: nowrap;
}

.bond-row-arrow {
  font-size: 18px;
  color: var(--color-text-tertiary);
}

.cash-card {
  background: var(--color-bg-secondary);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
}

.cash-card.disabled-card {
  opacity: 0.6;
}

.cash-card.disabled-card .cash-title {
  color: var(--color-text-tertiary);
}

.cash-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
}

.cash-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.cash-total {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.cash-total-group {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--spacing-xxs);
}

.cash-total-krw {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.cash-details {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.cash-detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-sm) 0;
}

.detail-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.detail-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

</style>

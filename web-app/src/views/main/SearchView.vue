<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import InvestmentTabs from '@/components/common/InvestmentTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { stockApi, overseasApi, marketApi, favoriteApi } from '@/services/api'
import { logger } from '@/utils/logger'
import { isKisOutageError, isKisUnavailableNotice } from '@/utils/kisStatus'

const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })
const searchQuery = ref('')
const results = ref([])
const searching = ref(false)
const searchError = ref('')
// 서버/네트워크 장애 여부. true 면 "검색 결과가 없습니다" 대신 장애 안내를 띄운다
// (백엔드 다운을 무데이터로 오인하게 만들지 않기 위함).
const searchOutage = ref(false)

// 종목코드별 현재가 캐시 (lazy 로딩)
const priceMap = ref({})
// 관심종목 코드 집합
const favoriteCodes = ref(new Set())
// USD → KRW 환율 (해외 KRW 병기용, 없으면 null → 병기 생략)
const usdKrwRate = ref(null)

const isDomestic = computed(() => tabs.value.sub === 'domestic')

// 검색어 없이 기본 상위 종목을 보여주는 상태(국내=코스피, 해외=S&P500)
const showingTopStocks = computed(() => !searchQuery.value.trim())

const filteredResults = computed(() => {
  // 백엔드 검색이 종목코드/이름으로 이미 필터링하므로 그대로 사용 (국내·해외 공통)
  return results.value
})

// ApiResponse 엔벨로프({success,message,data})와 bare payload 모두 허용하는 안전 언랩
const unwrap = (res) => {
  if (res && typeof res === 'object' && !Array.isArray(res) && 'data' in res && 'success' in res) {
    return res.data
  }
  return res
}

const formatNumber = (num) => {
  if (num === null || num === undefined || Number.isNaN(Number(num))) {
    return '—'
  }
  return new Intl.NumberFormat('ko-KR').format(num)
}

// 해외 종목의 거래소 코드 추출 (NASD/NYSE/AMEX). 백엔드 검색 응답 필드명 변형을 모두 허용.
const getExchange = (item) =>
  item?.exchangeCode ?? item?.exchange_code ?? item?.exchange ?? null

const toKrw = (usd) => {
  if (
    usd === null ||
    usd === undefined ||
    Number.isNaN(Number(usd)) ||
    usdKrwRate.value === null
  ) {
    return null
  }
  return Number(usd) * usdKrwRate.value
}

// 백엔드 응답은 snake_case(stock_code/stock_name)일 수 있어 camelCase로 정규화(둘 다 허용).
const normalizeResults = (data) => {
  const list = Array.isArray(data) ? data : []
  return list.map((it) => ({
    stockCode: it.stockCode ?? it.stock_code ?? '',
    stockName: it.stockName ?? it.stock_name ?? '',
    market: it.market ?? '',
    exchangeCode: it.exchangeCode ?? it.exchange_code ?? it.exchange ?? null
  }))
}

// 화면 진입 기본 목록: 국내=코스피 상위, 해외=S&P500 상위. 검색어가 비면 이 목록으로 복귀.
const loadTopStocks = async () => {
  searchError.value = ''
  searchOutage.value = false
  searching.value = true
  try {
    const res = await stockApi.getTop(isDomestic.value ? undefined : 'US')
    results.value = normalizeResults(unwrap(res))
  } catch (error) {
    logger.debug('상위 종목 조회 실패:', error)
    results.value = []
    // 서버 장애(5xx/네트워크/타임아웃)와 "결과 없음"을 화면에서 구분한다.
    searchOutage.value = isKisOutageError(error)
    if (!searchOutage.value) {
      searchError.value = '상위 종목을 불러오지 못했습니다'
    }
  } finally {
    searching.value = false
  }
}

const handleSearch = async () => {
  const query = searchQuery.value.trim()
  searchError.value = ''
  searchOutage.value = false

  if (!query) {
    // 검색어가 없으면 기본 상위 종목 목록으로 복귀
    await loadTopStocks()
    return
  }

  searching.value = true
  try {
    const res = isDomestic.value
      ? await stockApi.search(query)
      : await stockApi.searchOverseas(query)
    results.value = normalizeResults(unwrap(res))
  } catch (error) {
    logger.debug('종목 검색 실패:', error)
    results.value = []
    // 서버 장애면 배너로, 그 외(400 등)는 문구로 안내 — 무데이터와 섞이지 않게.
    searchOutage.value = isKisOutageError(error)
    if (!searchOutage.value) {
      searchError.value = '검색 중 오류가 발생했습니다'
    }
  } finally {
    searching.value = false
  }
}

// 항목별 현재가 lazy 로딩 (국내·해외 분기)
const loadPrice = async (item) => {
  const stockCode = item?.stockCode
  if (!stockCode || priceMap.value[stockCode]) {
    return
  }
  // 중복 호출 방지를 위해 placeholder 선점
  priceMap.value = { ...priceMap.value, [stockCode]: { loading: true } }

  try {
    if (isDomestic.value) {
      const data = unwrap(await stockApi.getPrice(stockCode))
      // StockPriceResponse는 snake_case(current_price 등) → camelCase 폴백과 함께 읽는다.
      const currentPrice = data?.currentPrice ?? data?.current_price ?? null
      priceMap.value = {
        ...priceMap.value,
        [stockCode]: {
          loading: false,
          overseas: false,
          currentPrice,
          changeAmount: data?.changeAmount ?? data?.change_amount ?? null,
          changeRate: data?.changeRate ?? data?.change_rate ?? null,
          notice: data?.notice ?? null
        }
      }
    } else {
      const exchange = getExchange(item)
      const data = unwrap(await overseasApi.getPrice(stockCode, exchange))
      // OverseasPriceResponse: { symbol, exchange, currency, last, base, diff, rate, notice }
      const last = data?.last ?? data?.currentPrice ?? null
      const rate = data?.rate ?? data?.changeRate ?? null
      priceMap.value = {
        ...priceMap.value,
        [stockCode]: {
          loading: false,
          overseas: true,
          currency: data?.currency ?? 'USD',
          currentPrice: last,
          changeAmount: data?.diff ?? data?.changeAmount ?? null,
          changeRate: rate,
          notice: data?.notice ?? null
        }
      }
    }
  } catch (error) {
    logger.debug(`현재가 조회 실패 (${stockCode}):`, error)
    priceMap.value = {
      ...priceMap.value,
      [stockCode]: {
        loading: false,
        overseas: !isDomestic.value,
        currentPrice: null,
        changeAmount: null,
        changeRate: null,
        notice: null,
        // 하드 실패(5xx/네트워크)는 시세 미제공이 아니라 KIS 연동 장애로 표기한다.
        kisDown: isKisOutageError(error)
      }
    }
  }
}

const getPriceInfo = (stockCode) => priceMap.value[stockCode] || null

// 시세 자리에 '—' 대신 "점검중" 배지를 띄울지 판단.
// (1) 하드 실패 = catch 에서 세운 kisDown, (2) 소프트 degrade = 200 이지만 값 null + notice.
const priceKisDown = (stockCode) => {
  const info = getPriceInfo(stockCode)
  if (!info || info.loading) {
    return false
  }
  if (info.kisDown === true) {
    return true
  }
  return (
    (info.currentPrice === null || info.currentPrice === undefined) &&
    isKisUnavailableNotice(info.notice)
  )
}

const hasPrice = (stockCode) => {
  const info = getPriceInfo(stockCode)
  return !!info && !info.loading && info.currentPrice !== null && info.currentPrice !== undefined
}

const priceText = (stockCode) => {
  const info = getPriceInfo(stockCode)
  if (!info) {
    return '—'
  }
  if (info.overseas) {
    // 해외도 원화로만 표기 (달러 병기 시 행 높이가 커짐). 환율 없으면 '—'.
    const krw = toKrw(info.currentPrice)
    return krw === null ? '—' : `${formatNumber(Math.round(krw))}원`
  }
  return `${formatNumber(info.currentPrice)}원`
}

// 검색 결과가 바뀌면 각 항목 현재가 조회
watch(
  filteredResults,
  (items) => {
    items.forEach((item) => loadPrice(item))
  },
  { immediate: false }
)

// 탭(국내↔해외) 전환 시 입력/결과/가격 캐시 초기화 — 통화·도메인이 다르므로 섞이지 않게.
// 국내로 전환하면 기본 상위 종목을 다시 노출한다.
watch(isDomestic, () => {
  searchQuery.value = ''
  results.value = []
  priceMap.value = {}
  searchError.value = ''
  searchOutage.value = false
  loadTopStocks()
})

const loadExchangeRates = async () => {
  try {
    const res = await marketApi.getExchangeRates()
    const list = res && res.success && Array.isArray(res.data) ? res.data : []
    const usd = list.find((r) => r && r.currency === 'USD')
    usdKrwRate.value = usd && Number(usd.rate) ? Number(usd.rate) : null
  } catch (error) {
    // 환율 실패 시 KRW 병기만 생략 (해외 검색은 정상 동작)
    logger.debug('환율 조회 실패:', error)
    usdKrwRate.value = null
  }
}

const loadFavorites = async () => {
  try {
    const res = await favoriteApi.list()
    const data = unwrap(res)
    const codes = Array.isArray(data) ? data.map((f) => f.stockCode ?? f.stock_code) : []
    favoriteCodes.value = new Set(codes.filter(Boolean))
  } catch (error) {
    // 비로그인/오류 시 관심종목 표시만 비움 (검색은 정상 동작)
    logger.debug('관심종목 조회 실패:', error)
    favoriteCodes.value = new Set()
  }
}

const isFavorite = (stockCode) => favoriteCodes.value.has(stockCode)

const toggleFavorite = async (item) => {
  const code = item.stockCode
  if (!code) {
    return
  }
  const next = new Set(favoriteCodes.value)
  try {
    if (favoriteCodes.value.has(code)) {
      await favoriteApi.remove(code)
      next.delete(code)
    } else {
      // 해외는 종목명·거래소를 함께 저장(관심종목 화면 해외 탭에서 시세 조회에 필요)
      await favoriteApi.add({
        stockCode: code,
        stockName: item.stockName || undefined,
        exchangeCode: isDomestic.value ? undefined : (getExchange(item) || undefined)
      })
      next.add(code)
    }
    favoriteCodes.value = next
  } catch (error) {
    logger.debug('관심종목 변경 실패:', error)
  }
}

const goToCompany = (item) => {
  router.push(`/company/${item.stockCode}`)
}

onMounted(() => {
  loadFavorites()
  loadExchangeRates()
  loadTopStocks()
})
</script>

<template>
  <div class="search-screen">
    <AppHeader title="종목 검색" showIcon icon="search" />

    <div class="content">
      <!-- Tabs -->
      <InvestmentTabs v-model="tabs" />

      <!-- Search Input -->
      <div class="search-bar">
        <input
          v-model="searchQuery"
          type="text"
          class="search-input"
          :placeholder="isDomestic ? '종목명(종목코드)' : '심볼(예: AAPL)·종목명'"
          @keyup.enter="handleSearch"
        />
        <button class="search-btn" :disabled="searching" @click="handleSearch">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2"/>
            <path d="M21 21L16.5 16.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </button>
      </div>

      <!-- Results List -->
      <div class="results-container">
        <div v-if="showingTopStocks && !searching && filteredResults.length > 0" class="list-caption">
          {{ isDomestic ? '코스피 상위 종목' : 'S&P500 상위 종목' }}
        </div>

        <!-- 서버 장애: "검색 결과가 없습니다" 로 오인되지 않도록 별도 안내 -->
        <KisMaintenanceNotice
          v-if="searchOutage && !searching"
          variant="card"
          title="서버 연결 오류"
          message="일시적인 서버 오류로 종목 목록을 불러올 수 없어요. 잠시 후 다시 시도해 주세요."
          class="list-notice"
        />

        <div v-else-if="filteredResults.length === 0" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="7" stroke="var(--color-text-tertiary)" stroke-width="2"/>
            <path d="M21 21L16.5 16.5" stroke="var(--color-text-tertiary)" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <p class="empty-text">{{ searchError || (searching ? '검색 중...' : '검색 결과가 없습니다') }}</p>
        </div>

        <div v-else class="results-list">
          <div
            v-for="(item, idx) in filteredResults"
            :key="item.stockCode"
            class="result-item"
            @click="goToCompany(item)"
          >
            <div class="item-left">
              <div class="item-thumb">
                <svg width="40" height="40" viewBox="0 0 40 40" fill="none">
                  <rect width="40" height="40" rx="10" :fill="`url(#itemGradient${idx})`"/>
                  <defs>
                    <linearGradient :id="`itemGradient${idx}`" x1="0" y1="0" x2="40" y2="40">
                      <stop offset="0%" :stop-color="idx % 3 === 0 ? '#3B82F6' : idx % 3 === 1 ? '#10B981' : '#F59E0B'"/>
                      <stop offset="100%" :stop-color="idx % 3 === 0 ? '#1E40AF' : idx % 3 === 1 ? '#047857' : '#D97706'"/>
                    </linearGradient>
                  </defs>
                  <text x="20" y="26" font-size="16" font-weight="bold" fill="white" text-anchor="middle">{{ (item.stockName || '?').charAt(0) }}</text>
                </svg>
              </div>
              <div class="item-info">
                <span class="item-name">{{ item.stockName }}</span>
                <span class="item-symbol">
                  {{ item.stockCode }}<template v-if="!isDomestic && getExchange(item)"> · {{ getExchange(item) }}</template>
                </span>
              </div>
            </div>
            <div class="item-right">
              <template v-if="hasPrice(item.stockCode)">
                <div class="item-price">{{ priceText(item.stockCode) }}</div>
                <div
                  v-if="getPriceInfo(item.stockCode).changeRate !== null && getPriceInfo(item.stockCode).changeRate !== undefined"
                  :class="['item-change', Number(getPriceInfo(item.stockCode).changeRate) >= 0 ? 'positive' : 'negative']"
                >
                  {{ Number(getPriceInfo(item.stockCode).changeRate) >= 0 ? '+' : '' }}{{ getPriceInfo(item.stockCode).changeRate }}%
                </div>
              </template>
              <!-- KIS 연동 실패는 값 없음('—')과 구분해 배지로 표시 -->
              <KisMaintenanceNotice v-else-if="priceKisDown(item.stockCode)" variant="inline" />
              <div v-else class="item-price">—</div>
            </div>
            <button class="star-btn" @click.stop="toggleFavorite(item)">
              <svg width="18" height="18" viewBox="0 0 24 24" :fill="isFavorite(item.stockCode) ? '#F59E0B' : 'none'" :stroke="isFavorite(item.stockCode) ? 'none' : '#64748B'" stroke-width="2">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"/>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-screen {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  overflow: hidden;
}

/* Header Override */
.search-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
  flex-shrink: 0;
}

.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
  padding: 0 var(--spacing-lg);
  padding-bottom: var(--bottom-nav-height);
}

.content :deep(.investment-tabs) {
  flex-shrink: 0;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 0 var(--spacing-md);
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: var(--radius-md);
  margin-bottom: var(--spacing-lg);
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
  flex-shrink: 0;
}

.search-btn {
  background: none;
  border: none;
  color: var(--color-text-secondary);
  cursor: pointer;
  padding: var(--spacing-sm);
  transition: color 0.2s;
}

.search-btn:hover {
  color: var(--color-text-primary);
}

.search-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.search-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.search-input {
  flex: 1;
  border: none;
  background: none;
  padding: var(--spacing-sm) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--color-text-tertiary);
}

.results-container {
  background: color-mix(in srgb, var(--canvas-card-start) 40%, transparent);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 12px;
  padding: var(--spacing-md);
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

/* 장애 안내 카드 (관심종목 화면과 동일한 배치 규칙) */
.list-notice {
  margin-bottom: var(--spacing-md);
  flex-shrink: 0;
}

.list-caption {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-tertiary);
  padding: 0 4px var(--spacing-sm);
  flex-shrink: 0;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-2xl);
  gap: var(--spacing-sm);
  flex: 1;
}

.empty-text {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-tertiary);
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: var(--spacing-lg);
}

.result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.result-item:hover {
  background: var(--canvas-hairline-soft);
  border-color: rgba(139, 92, 246, 0.3);
  transform: translateX(2px);
}

.item-left {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex: 1;
}

.item-thumb {
  width: 40px;
  height: 40px;
  background: var(--canvas-hairline-soft);
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.item-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.item-symbol {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.item-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  margin-right: var(--spacing-sm);
}

.item-price {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.item-change {
  font-size: 11px;
  font-weight: var(--font-weight-medium);
}

.item-change.positive {
  color: #10B981;
}

.item-change.negative {
  color: #EF4444;
}

.star-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.8;
  transition: opacity 0.2s;
  flex-shrink: 0;
}

.star-btn:hover {
  opacity: 1;
}
</style>

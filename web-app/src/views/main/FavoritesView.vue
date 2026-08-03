<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import InvestmentTabs from '@/components/common/InvestmentTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { favoriteApi, overseasApi, marketApi } from '@/services/api'
import { logger } from '@/utils/logger'

const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })

// 전체 관심종목(국내+해외). 탭에 따라 아래 favorites 로 분리한다.
const allFavorites = ref([])
const isLoading = ref(false)
const errorMessage = ref('')

// 해외 시세 lazy 캐시(종목코드별) + USD→KRW 환율(원화 표기용)
const priceMap = ref({})
const usdKrwRate = ref(null)

const isDomestic = computed(() => tabs.value.sub === 'domestic')

// 국내/해외 구분: 국내 종목코드는 6자리 숫자, 해외는 영문 심볼.
const isDomesticCode = (code) => /^\d{6}$/.test(String(code || ''))

// 현재 탭에 해당하는 관심종목만 노출
const favorites = computed(() =>
  allFavorites.value.filter((f) =>
    isDomestic.value ? isDomesticCode(f.stockCode) : !isDomesticCode(f.stockCode)
  )
)

// KIS 시세 미연동/점검(국내 embedded notice). 해외는 lazy 라 여기서 판단하지 않는다.
const kisDown = computed(() => isDomestic.value && favorites.value.some((f) => !!f.notice))

// ApiResponse 엔벨로프({success,data})와 bare payload 모두 허용하는 안전 언랩
const unwrap = (res) =>
  res && typeof res === 'object' && !Array.isArray(res) && 'data' in res && 'success' in res
    ? res.data
    : res

const loadFavorites = async () => {
  isLoading.value = true
  errorMessage.value = ''
  try {
    const data = unwrap(await favoriteApi.list())
    const list = Array.isArray(data) ? data : []
    allFavorites.value = list
      .map((f) => ({
        stockCode: f.stockCode ?? f.stock_code ?? '',
        stockName: f.stockName ?? f.stock_name ?? '',
        currentPrice: f.currentPrice ?? f.current_price ?? null,
        changeRate: f.changeRate ?? f.change_rate ?? null,
        notice: f.notice ?? null,
        exchangeCode: f.exchangeCode ?? f.exchange_code ?? null
      }))
      .filter((f) => f.stockCode)
  } catch (error) {
    logger.debug('관심 종목 조회 실패:', error)
    errorMessage.value = '관심 종목을 불러오지 못했습니다.'
    allFavorites.value = []
  } finally {
    isLoading.value = false
  }
}

const removeFavorite = async (item) => {
  if (!item?.stockCode) return
  try {
    await favoriteApi.remove(item.stockCode)
    allFavorites.value = allFavorites.value.filter((f) => f.stockCode !== item.stockCode)
  } catch (error) {
    logger.debug('관심 종목 삭제 실패:', error)
  }
}

const goToCompany = (item) => {
  if (!item?.stockCode) return
  router.push(`/company/${item.stockCode}`)
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '—'
  return new Intl.NumberFormat('ko-KR').format(num)
}

const toKrw = (usd) => {
  if (usd === null || usd === undefined || Number.isNaN(Number(usd)) || usdKrwRate.value === null) {
    return null
  }
  return Number(usd) * usdKrwRate.value
}

// 해외 종목 현재가 lazy 조회 (원화 환산 표기용)
const loadOverseasPrice = async (item) => {
  const code = item?.stockCode
  if (!code || priceMap.value[code]) return
  priceMap.value = { ...priceMap.value, [code]: { loading: true } }
  try {
    const data = unwrap(await overseasApi.getPrice(code, item.exchangeCode || 'NASD'))
    priceMap.value = {
      ...priceMap.value,
      [code]: {
        loading: false,
        currentPrice: data?.last ?? data?.currentPrice ?? null,
        changeRate: data?.rate ?? data?.changeRate ?? null
      }
    }
  } catch (error) {
    logger.debug(`해외 시세 조회 실패 (${code}):`, error)
    priceMap.value = { ...priceMap.value, [code]: { loading: false, currentPrice: null, changeRate: null } }
  }
}

// 국내는 백엔드 embedded 원화, 해외는 lazy 원화 환산.
const priceText = (item) => {
  if (isDomesticCode(item.stockCode)) {
    return item.currentPrice == null ? '—' : `${formatNumber(item.currentPrice)}원`
  }
  const info = priceMap.value[item.stockCode]
  if (!info || info.loading || info.currentPrice == null) return '—'
  const krw = toKrw(info.currentPrice)
  return krw == null ? '—' : `${formatNumber(Math.round(krw))}원`
}

// 등락률: 국내=embedded, 해외=lazy
const rateOf = (item) => {
  if (isDomesticCode(item.stockCode)) return item.changeRate
  const info = priceMap.value[item.stockCode]
  return info && !info.loading ? info.changeRate : null
}

const changeRateValue = (item) => {
  const rate = rateOf(item)
  if (rate === null || rate === undefined) return null
  const parsed = typeof rate === 'number' ? rate : Number(rate)
  return Number.isNaN(parsed) ? null : parsed
}

const formatChangeRate = (item) => {
  const rate = changeRateValue(item)
  if (rate === null) return '—'
  const sign = rate >= 0 ? '+' : ''
  return `${sign}${rate}%`
}

const isPositive = (item) => {
  const rate = changeRateValue(item)
  return rate !== null && rate >= 0
}

const loadExchangeRates = async () => {
  try {
    const res = await marketApi.getExchangeRates()
    const list = res && res.success && Array.isArray(res.data) ? res.data : []
    const usd = list.find((r) => r && r.currency === 'USD')
    usdKrwRate.value = usd && Number(usd.rate) ? Number(usd.rate) : null
  } catch (error) {
    logger.debug('환율 조회 실패:', error)
    usdKrwRate.value = null
  }
}

// 현재 탭이 해외면 해당 종목들의 시세를 lazy 로드 (탭 전환/목록 변경 시)
watch(
  favorites,
  (items) => {
    if (!isDomestic.value) {
      items.forEach((item) => loadOverseasPrice(item))
    }
  },
  { immediate: false }
)

onMounted(() => {
  loadFavorites()
  loadExchangeRates()
})
</script>

<template>
  <div class="favorites-screen">
    <AppHeader title="관심 종목" showIcon icon="star" />

    <div class="content">
      <!-- Tabs -->
      <InvestmentTabs v-model="tabs" />

      <!-- Items List -->
      <div class="items-container">
        <!-- KIS 점검중: 상단 통일 안내 (국내 전용) -->
        <KisMaintenanceNotice
          v-if="isDomestic && !isLoading && !errorMessage && kisDown"
          variant="banner"
          class="list-notice"
        />

        <div v-if="isLoading" class="empty-state">
          <p class="empty-text">불러오는 중...</p>
        </div>

        <div v-else-if="errorMessage" class="empty-state">
          <p class="empty-text">{{ errorMessage }}</p>
        </div>

        <div v-else-if="favorites.length === 0" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" stroke="var(--color-text-tertiary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <p class="empty-text">관심 종목이 없습니다</p>
        </div>

        <div v-else class="items-list">
          <div
            v-for="(item, idx) in favorites"
            :key="item.stockCode"
            class="item-row"
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
                  <text x="20" y="26" font-size="16" font-weight="bold" fill="white" text-anchor="middle">{{ (item.stockName || item.stockCode || '?').charAt(0) }}</text>
                </svg>
              </div>
              <div class="item-info">
                <span class="item-name">{{ item.stockName || item.stockCode }}</span>
                <span class="item-symbol">
                  {{ item.stockCode }}<template v-if="!isDomestic && item.exchangeCode"> · {{ item.exchangeCode }}</template>
                </span>
              </div>
            </div>
            <div class="item-right">
              <div class="item-price">{{ priceText(item) }}</div>
              <div v-if="isDomestic && item.notice" class="item-notice">—</div>
              <div
                v-else
                :class="['item-change', isPositive(item) ? 'positive' : 'negative']"
              >
                {{ formatChangeRate(item) }}
              </div>
            </div>
            <button class="star-btn" @click.stop="removeFavorite(item)">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="#F59E0B">
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
.favorites-screen {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  overflow: hidden;
}

.favorites-screen :deep(.app-header) {
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

/* Items Container */
.items-container {
  margin-top: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  background: rgba(30, 41, 59, 0.4);
  border: 1px solid var(--canvas-hairline-soft);
  border-radius: 12px;
  padding: var(--spacing-md);
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.list-notice {
  margin-bottom: var(--spacing-md);
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

/* Items List */
.items-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item-row {
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

.item-row:hover {
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

.item-thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
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

.item-notice {
  font-size: 11px;
  font-weight: var(--font-weight-medium);
  color: var(--color-text-tertiary);
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

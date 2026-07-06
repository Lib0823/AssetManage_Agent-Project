<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import InvestmentTabs from '@/components/common/InvestmentTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { favoriteApi } from '@/services/api'
import { logger } from '@/utils/logger'

const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })

const favorites = ref([])
const isLoading = ref(false)
const errorMessage = ref('')

// 국내(domestic)만 실데이터 제공. 해외/코인 등은 추후 지원.
const isDomestic = computed(() => tabs.value.sub === 'domestic')

// KIS 시세 미연동/점검: 서버가 종목별로 notice 를 채워 내려준다.
// 한 종목이라도 notice 가 있으면 KIS 점검중으로 보고 상단에 통일 안내를 표시.
const kisDown = computed(() => favorites.value.some((f) => !!f.notice))

const loadFavorites = async () => {
  if (!isDomestic.value) {
    favorites.value = []
    return
  }
  isLoading.value = true
  errorMessage.value = ''
  try {
    const res = await favoriteApi.list()
    // /favorites 는 ApiResponse 엔벨로프({success,data})로 반환 → data 를 언랩.
    // 또한 필드가 snake_case(stock_code 등)라 camelCase 로 정규화(둘 다 허용).
    const list = res && res.success && Array.isArray(res.data)
      ? res.data
      : (Array.isArray(res) ? res : [])
    favorites.value = list.map((f) => ({
      stockCode: f.stockCode ?? f.stock_code ?? '',
      stockName: f.stockName ?? f.stock_name ?? '',
      currentPrice: f.currentPrice ?? f.current_price ?? null,
      changeRate: f.changeRate ?? f.change_rate ?? null,
      notice: f.notice ?? null
    }))
  } catch (error) {
    logger.debug('관심 종목 조회 실패:', error)
    errorMessage.value = '관심 종목을 불러오지 못했습니다.'
    favorites.value = []
  } finally {
    isLoading.value = false
  }
}

const removeFavorite = async (item) => {
  if (!item?.stockCode) return
  try {
    await favoriteApi.remove(item.stockCode)
    favorites.value = favorites.value.filter((f) => f.stockCode !== item.stockCode)
  } catch (error) {
    logger.debug('관심 종목 삭제 실패:', error)
  }
}

const handleTabChange = () => {
  loadFavorites()
}

const goToCompany = (item) => {
  if (!item?.stockCode) return
  router.push(`/company/${item.stockCode}`)
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '—'
  return new Intl.NumberFormat('ko-KR').format(num)
}

const formatPrice = (item) => {
  if (item.currentPrice === null || item.currentPrice === undefined) return '—'
  return `${formatNumber(item.currentPrice)}원`
}

const changeRateValue = (item) => {
  const rate = item.changeRate
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

onMounted(() => {
  loadFavorites()
})
</script>

<template>
  <div class="favorites-screen">
    <AppHeader title="관심 종목" showIcon icon="star" />

    <div class="content">
      <!-- Tabs -->
      <InvestmentTabs v-model="tabs" @update:modelValue="handleTabChange" />

      <!-- Items List -->
      <div class="items-container">
        <!-- KIS 점검중: 상단 통일 안내 (다른 화면과 동일한 문구/모양) -->
        <KisMaintenanceNotice
          v-if="isDomestic && !isLoading && !errorMessage && kisDown"
          variant="banner"
          class="list-notice"
        />

        <!-- 해외/코인 등 추후 지원 -->
        <div v-if="!isDomestic" class="empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" stroke="var(--color-text-tertiary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <p class="empty-text">추후 지원 예정입니다</p>
        </div>

        <div v-else-if="isLoading" class="empty-state">
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
                <span class="item-symbol">{{ item.stockCode }}</span>
              </div>
            </div>
            <div class="item-right">
              <div class="item-price">{{ formatPrice(item) }}</div>
              <div v-if="item.notice" class="item-notice">—</div>
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

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.favorites-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0F172A 0%, #1E293B 100%);
  padding-bottom: var(--bottom-nav-height);
}

.favorites-screen :deep(.app-header) {
  background: #0F172A;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.content {
  padding: 0 var(--spacing-lg);
}

/* Items Container */
.items-container {
  margin-top: var(--spacing-md);
  background: rgba(30, 41, 59, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: var(--spacing-md);
  min-height: 250px;
}

.list-notice {
  margin-bottom: var(--spacing-md);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-2xl);
  gap: var(--spacing-sm);
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
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.item-row:hover {
  background: rgba(255, 255, 255, 0.06);
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
  background: rgba(255, 255, 255, 0.05);
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

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { tradingApi, marketApi } from '@/services/api'
import { mockMarketIndices } from '@/services/mockData'
import { logger } from '@/utils/logger'

const router = useRouter()

// Reactive state (loaded from API in onMounted)
const indexCategories = ref([])
const indicesLoading = ref(true)
// 국내(KIS) 지수를 불러오지 못하면 true → mock 으로 덮지 않고 점검 안내를 표시
const indicesKisDown = ref(false)
const exchangeRates = ref([])
const exchangeLoading = ref(true)
const topNews = ref([])
const aiRecommendations = ref([])
const notifications = ref([])

const currentIndexSlide = ref(0)
const scrollContainerRef = ref(null)

const onScroll = () => {
  if (!scrollContainerRef.value) return
  const container = scrollContainerRef.value
  const scrollLeft = container.scrollLeft
  const itemWidth = container.offsetWidth * 0.85
  const newIndex = Math.round(scrollLeft / itemWidth)
  if (newIndex !== currentIndexSlide.value && newIndex >= 0 && newIndex < indexCategories.value.length) {
    currentIndexSlide.value = newIndex
  }
}

const goToSlide = (index) => {
  if (!scrollContainerRef.value) return
  const container = scrollContainerRef.value
  const itemWidth = container.offsetWidth * 0.85
  container.scrollTo({ left: index * itemWidth, behavior: 'smooth' })
}

// 환율 스크롤
const currentExchangeSlide = ref(0)
const exchangeScrollRef = ref(null)

const onExchangeScroll = () => {
  if (!exchangeScrollRef.value) return
  const container = exchangeScrollRef.value
  const scrollLeft = container.scrollLeft
  const itemWidth = container.offsetWidth * 0.85
  const newIndex = Math.round(scrollLeft / itemWidth)
  if (newIndex !== currentExchangeSlide.value && newIndex >= 0 && newIndex < exchangeRates.value.length) {
    currentExchangeSlide.value = newIndex
  }
}

const goToExchangeSlide = (index) => {
  if (!exchangeScrollRef.value) return
  const container = exchangeScrollRef.value
  const itemWidth = container.offsetWidth * 0.85
  container.scrollTo({ left: index * itemWidth, behavior: 'smooth' })
}

// 미니 차트 포인트 계산
const getChartPoints = (history) => {
  if (!history || history.length === 0) return ''
  const min = Math.min(...history)
  const max = Math.max(...history)
  const range = max - min || 1
  const points = history.map((value, index) => {
    const x = (index / (history.length - 1)) * 100
    const y = 40 - ((value - min) / range) * 36 - 2
    return `${x},${y}`
  })
  return points.join(' ')
}

// 알림 모달
const showNotificationModal = ref(false)

const getNotificationIcon = (type) => {
  const icons = {
    trade: 'balance-o',
    ai: 'robot-o',
    price: 'chart-trending-o',
    news: 'newspaper-o'
  }
  return icons[type] || 'bell'
}

const getNotificationTypeName = (type) => {
  const names = {
    trade: '매매',
    ai: 'AI',
    price: '주가',
    news: '뉴스'
  }
  const name = names[type] || 'none'
  return `[${name}]`
}

const formatNumber = (num) => {
  if (num === null || num === undefined || Number.isNaN(Number(num))) return '-'
  return new Intl.NumberFormat('ko-KR').format(num)
}

const formatChange = (change, percent) => {
  const c = Number(change) || 0
  const p = Number(percent) || 0
  const sign = c >= 0 ? '+' : ''
  return `${sign}${c}(${sign}${p}%)`
}

// 지수 카드는 항상 2x2(4슬롯)로 렌더 — 데이터가 적거나 없으면 빈 슬롯을 채워
// 레이아웃이 뭉개지지 않게 하고, 값이 null이면 화면에서 '-'로 표시된다.
const indexSlots = (category) => {
  const items = ((category && category.items) || []).slice(0, 4)
  while (items.length < 4) {
    items.push({ label: '-', value: null, change: null, change_percent: null })
  }
  return items
}

const goToNews = (news) => {
  if (news.link) {
    window.open(news.link, '_blank')
  } else {
    router.push(`/news/${news.id}`)
  }
}

const goToNewsFeed = () => {
  router.push('/news')
}

const goToCompany = (symbol) => {
  router.push({
    path: `/company/${symbol}`,
    query: { showAiAnalysis: 'true' }
  })
}

// ===== Data loading =====

// Relative time helper for notifications
const formatRelativeTime = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  if (Number.isNaN(date.getTime())) return ''
  const diffMs = Date.now() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '방금 전'
  if (diffMin < 60) return `${diffMin}분 전`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour}시간 전`
  return date.toLocaleDateString('ko-KR')
}

// 1. 알림 ← 거래 내역
const loadNotifications = async () => {
  try {
    // 알림은 DB 거래내역(trade_history) 기반 — KIS 라이브가 아니라 빠르고 안정적(타임아웃/500 없음).
    const res = await tradingApi.getRecentTrades()
    const trades = (res && res.success && Array.isArray(res.data)) ? res.data : []
    if (trades.length === 0) {
      notifications.value = [{ id: 'empty', type: 'none', title: '최근 알림이 없습니다', desc: '', time: '', read: true }]
      return
    }
    const sorted = [...trades].sort((a, b) => new Date(b.orderedAt) - new Date(a.orderedAt))
    notifications.value = sorted.slice(0, 8).map((trade) => {
      const side = (trade.orderType || '').toUpperCase() === 'SELL' ? '매도' : '매수'
      const price = trade.executedPrice ?? trade.orderPrice
      return {
        id: trade.id,
        type: 'trade',
        title: `${trade.stockName} ${trade.quantity}주 ${side} 체결`,
        desc: price != null ? `체결가 ${formatNumber(price)}원` : '',
        time: formatRelativeTime(trade.orderedAt),
        read: true
      }
    })
  } catch (error) {
    // 타임아웃/네트워크 실패는 치명적이지 않으므로 경고로만 남기고 빈 상태로 degrade.
    if (error?.code === 'ECONNABORTED') {
      logger.debug('Notifications (trade history) timed out; showing empty state')
    } else {
      logger.debug('Failed to load notifications:', error)
    }
    notifications.value = [{ id: 'empty', type: 'none', title: '최근 알림이 없습니다', desc: '', time: '', read: true }]
  }
}

// 2. 주요 지수 ← /market/indices (categories rendered dynamically)
// 코인 카테고리만 mock 을 유지(코인은 지원 범위 밖). 국내·해외 지수는 실데이터만 사용한다.
const mockIndexCategory = (key) => {
  const m = mockMarketIndices[key]
  if (!m) return null
  return {
    key,
    label: m.label,
    items: (m.items || []).map((i) => ({
      label: i.label,
      value: i.value,
      change: i.change,
      change_percent: i.changePercent
    }))
  }
}

const loadIndices = async () => {
  // 국내·해외: KIS 실데이터만 사용(없으면 표시 안 함, 가짜로 덮지 않음).
  // 코인: 지원 범위 밖이라 mock 유지. 표시 순서: 국내 → 해외 → 코인.
  const order = ['domestic', 'overseas', 'coin']
  const fromApi = {}
  try {
    // 지수는 KIS 를 순차 호출하므로 전역 10s 보다 여유 있게.
    const res = await marketApi.getIndices({ timeout: 20000 })
    if (res && res.success && res.data && Array.isArray(res.data.categories)) {
      for (const c of res.data.categories) {
        if (c && c.key && Array.isArray(c.items) && c.items.length > 0) {
          fromApi[c.key] = c
        }
      }
    }
  } catch (error) {
    logger.debug('Failed to load indices:', error)
  }

  // 국내·해외 지수는 KIS 실데이터만 노출한다. 비어 있으면 가짜 지수로 덮지 않고 카드를 생략한다
  // (사용자가 가짜 숫자를 실데이터로 오인하지 않도록). 코인만 mock 유지(지원 범위 밖).
  // 국내가 비면 KIS 점검/미연동으로 보고 상단 점검 안내를 표시한다.
  indicesKisDown.value = !fromApi.domestic
  indexCategories.value = order
    .map((key) => (key === 'coin' ? fromApi.coin || mockIndexCategory('coin') : fromApi[key] || null))
    .filter(Boolean)
  indicesLoading.value = false
}

// 3. 환율 ← /market/exchange-rates
const loadExchangeRates = async () => {
  try {
    const res = await marketApi.getExchangeRates()
    exchangeRates.value = (res && res.success && Array.isArray(res.data)) ? res.data : []
  } catch (error) {
    logger.debug('Failed to load exchange rates:', error)
    exchangeRates.value = []
  } finally {
    exchangeLoading.value = false
  }
}

// 4. 속보 ← /market/news
const loadTopNews = async () => {
  try {
    const res = await marketApi.getTopNews()
    topNews.value = (res && res.success && Array.isArray(res.data)) ? res.data : []
  } catch (error) {
    logger.debug('Failed to load top news:', error)
    topNews.value = []
  }
}

// 5. AI 추천 ← /market/decisions (buy_top3)
const loadAiRecommendations = async () => {
  try {
    const res = await marketApi.getAiRecommendations()
    const buyTop3 = (res && res.success && res.data && Array.isArray(res.data.buy_top3)) ? res.data.buy_top3 : []
    aiRecommendations.value = buyTop3
      .filter((item) => item && item.stock_code)
      .map((item) => ({
        symbol: item.stock_code,
        title: item.stock_name,
        tag: 'AI 매수 추천',
        description: item.reason,
        logo: null
      }))
  } catch (error) {
    logger.debug('Failed to load AI recommendations:', error)
    aiRecommendations.value = []
  }
}

onMounted(() => {
  // Each section loads independently; one failure must not blank the others
  Promise.allSettled([
    loadNotifications(),
    loadIndices(),
    loadExchangeRates(),
    loadTopNews(),
    loadAiRecommendations()
  ])
})
</script>

<template>
  <div class="home-screen">
    <AppHeader title="홈" showIcon icon="home" show-kis-mode />

    <div class="content">
      <!-- Notification Banner -->
      <div class="notification-banner" @click="showNotificationModal = true">
        <span class="notification-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M18 8C18 6.4087 17.3679 4.88258 16.2426 3.75736C15.1174 2.63214 13.5913 2 12 2C10.4087 2 8.88258 2.63214 7.75736 3.75736C6.63214 4.88258 6 6.4087 6 8C6 15 3 17 3 17H21C21 17 18 15 18 8Z" stroke="#F59E0B" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M13.73 21C13.5542 21.3031 13.3019 21.5547 12.9982 21.7295C12.6946 21.9044 12.3504 21.9965 12 21.9965C11.6496 21.9965 11.3054 21.9044 11.0018 21.7295C10.6982 21.5547 10.4458 21.3031 10.27 21" stroke="#F59E0B" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </span>
        <span class="notification-text"><b>{{ getNotificationTypeName(notifications[0]?.type) }}</b></span>
        <span class="notification-highlight">{{ notifications[0]?.title }}</span>
        <span>  </span>
        <van-icon name="arrow" class="notification-arrow" />
      </div>

      <!-- Notification Modal -->
      <van-popup
        v-model:show="showNotificationModal"
        round
        closeable
        close-icon-position="top-right"
        :style="{ width: '90%', maxWidth: '320px', maxHeight: '60vh', background: '#1F2937' }"
      >
        <div class="notification-modal">
          <div class="modal-header">
            <h3 class="modal-title">최근 알림</h3>
          </div>
          <div class="notification-list">
            <div
              v-for="noti in notifications"
              :key="noti.id"
              :class="['notification-item', { unread: !noti.read }]"
            >
              <div class="noti-icon-wrap">
                <van-icon :name="getNotificationIcon(noti.type)" size="16" />
              </div>
              <div class="noti-content">
                <span class="noti-title">{{ noti.title }}</span>
                <span class="noti-desc">{{ noti.desc }}</span>
              </div>
              <span class="noti-time">{{ noti.time }}</span>
            </div>
          </div>
        </div>
      </van-popup>

      <!-- Market Indices -->
      <section class="section">
        <h2 class="section-title">주요 지수</h2>
        <div v-if="indicesLoading" class="loading-state">
          <span class="loading-spinner"></span> 지수 불러오는 중...
        </div>
        <KisMaintenanceNotice
          v-else-if="indicesKisDown"
          variant="banner"
          class="indices-notice"
        />
        <div v-else class="indices-swipe-container">
          <div class="swipe-fade left" :class="{ visible: currentIndexSlide > 0 }"></div>
          <div class="swipe-fade right" :class="{ visible: currentIndexSlide < indexCategories.length - 1 }"></div>
          <div
            ref="scrollContainerRef"
            class="indices-scroll"
            @scroll="onScroll"
          >
            <div
              v-for="category in indexCategories"
              :key="category.key"
              class="indices-card"
            >
              <!-- 상단 2개 지수 (항상 2슬롯, 데이터 없으면 '-') -->
              <div class="indices-row">
                <div
                  v-for="(item, i) in indexSlots(category).slice(0, 2)"
                  :key="`top-${category.key}-${i}`"
                  class="index-item-wrap"
                >
                  <div class="vertical-divider" v-if="i > 0"></div>
                  <div class="index-item">
                    <span class="index-label">{{ item.label || '-' }}</span>
                    <span class="index-value">{{ formatNumber(item.value) }}</span>
                    <span
                      v-if="item.value != null"
                      :class="['index-change', (item.change ?? 0) >= 0 ? 'positive' : 'negative']"
                    >
                      {{ formatChange(item.change, item.change_percent) }}
                    </span>
                    <span v-else class="index-change">-</span>
                  </div>
                </div>
              </div>

              <!-- Center divider with tab -->
              <div class="index-divider">
                <div class="divider-line"></div>
                <div class="index-tab">
                  <span class="tab-text">{{ category.label }}</span>
                </div>
                <div class="divider-line"></div>
              </div>

              <!-- 하단 2개 지수 (항상 2슬롯, 데이터 없으면 '-') -->
              <div class="indices-row">
                <div
                  v-for="(item, i) in indexSlots(category).slice(2, 4)"
                  :key="`bottom-${category.key}-${i}`"
                  class="index-item-wrap"
                >
                  <div class="vertical-divider" v-if="i > 0"></div>
                  <div class="index-item">
                    <span class="index-label">{{ item.label || '-' }}</span>
                    <span class="index-value">{{ formatNumber(item.value) }}</span>
                    <span
                      v-if="item.value != null"
                      :class="['index-change', (item.change ?? 0) >= 0 ? 'positive' : 'negative']"
                    >
                      {{ formatChange(item.change, item.change_percent) }}
                    </span>
                    <span v-else class="index-change">-</span>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="indexCategories.length === 0" class="empty-state">
              지수 정보가 없습니다
            </div>
          </div>
          <!-- Slide indicators -->
          <div class="slide-indicators">
            <span
              v-for="(category, idx) in indexCategories"
              :key="idx"
              :class="['indicator', { active: currentIndexSlide === idx }]"
              @click="goToSlide(idx)"
            ></span>
          </div>
        </div>
      </section>

      <!-- Exchange Rates -->
      <section class="section">
        <h2 class="section-title">환율</h2>
        <div v-if="exchangeLoading" class="loading-state">
          <span class="loading-spinner"></span> 환율 불러오는 중...
        </div>
        <div v-else class="exchange-swipe-container">
          <div class="swipe-fade left" :class="{ visible: currentExchangeSlide > 0 }"></div>
          <div class="swipe-fade right" :class="{ visible: currentExchangeSlide < exchangeRates.length - 1 }"></div>
          <div
            ref="exchangeScrollRef"
            class="exchange-scroll"
            @scroll="onExchangeScroll"
          >
            <div
              v-for="rate in exchangeRates"
              :key="rate.currency"
              class="exchange-card"
            >
              <div class="exchange-info">
                <div class="exchange-left">
                  <span class="currency-badge">{{ rate.currency }}</span>
                  <span class="country-name">{{ rate.country }}</span>
                </div>
                <div class="exchange-right">
                  <span class="rate-value">{{ formatNumber(rate.rate) }}원</span>
                  <span :class="['rate-change', (rate.change ?? 0) >= 0 ? 'positive' : 'negative']">
                    {{ (rate.change ?? 0) >= 0 ? '+' : '' }}{{ Number(rate.change ?? 0).toFixed(2) }} ({{ (rate.change_percent ?? 0) >= 0 ? '+' : '' }}{{ Number(rate.change_percent ?? 0).toFixed(2) }}%)
                  </span>
                </div>
              </div>
              <div class="exchange-chart">
                <svg class="mini-chart" viewBox="0 0 100 40" preserveAspectRatio="none">
                  <polyline
                    :points="getChartPoints(rate.history)"
                    fill="none"
                    :stroke="(rate.change ?? 0) >= 0 ? '#10B981' : '#EF4444'"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </div>
            </div>
            <div v-if="exchangeRates.length === 0" class="empty-state">
              환율 정보가 없습니다
            </div>
          </div>
          <div class="slide-indicators">
            <span
              v-for="(rate, idx) in exchangeRates"
              :key="idx"
              :class="['indicator', { active: currentExchangeSlide === idx }]"
              @click="goToExchangeSlide(idx)"
            ></span>
          </div>
        </div>
      </section>

      <!-- Top News -->
      <section class="section">
        <h2 class="section-title">속보 TOP3</h2>
        <div class="news-list">
          <div
            v-for="news in topNews.slice(0, 3)"
            :key="news.id"
            class="news-item"
            @click="goToNews(news)"
          >
            <div class="news-thumb" v-if="news.image">
              <img :src="news.image" :alt="news.title" />
            </div>
            <div class="news-content">
              <span class="news-title">{{ news.title }}</span>
              <span class="news-desc" v-if="news.description">{{ news.description }}</span>
            </div>
            <div class="news-meta">
              <span class="news-source" v-if="news.source">{{ news.source }}</span>
              <span class="news-date">{{ news.date }}</span>
            </div>
          </div>
          <div v-if="topNews.length === 0" class="news-empty">
            속보가 없습니다
          </div>
        </div>
        <button class="news-more-btn" @click="goToNewsFeed">
          뉴스 더보기
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M9 18l6-6-6-6"/>
          </svg>
        </button>
      </section>

      <!-- AI Recommendations -->
      <section class="section">
        <h2 class="section-title">AI 추천</h2>
        <div class="ai-grid">
          <div
            v-for="(item, index) in aiRecommendations"
            :key="index"
            class="ai-card"
            @click="goToCompany(item.symbol)"
          >
            <div class="ai-card-gradient"></div>
            <div class="ai-content">
              <div class="ai-header">
                <div class="ai-logo-container">
                  <div class="ai-logo">
                    <img v-if="item.logo" :src="item.logo" :alt="item.title" />
                    <span v-else class="ai-logo-placeholder">{{ (item.title || '?').charAt(0) }}</span>
                  </div>
                </div>
                <div class="ai-title-wrap">
                  <span class="ai-title">{{ item.title }}</span>
                  <span class="ai-tag">
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor">
                      <circle cx="12" cy="12" r="3"/>
                    </svg>
                    {{ item.tag }}
                  </span>
                </div>
              </div>
              <p class="ai-desc">{{ item.description }}</p>
              <div class="ai-footer">
                <span class="ai-action">자세히 보기</span>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M5 12h14M12 5l7 7-7 7"/>
                </svg>
              </div>
            </div>
          </div>
          <div v-if="aiRecommendations.length === 0" class="ai-empty">
            AI 추천 정보가 없습니다
          </div>
        </div>
      </section>
    </div>

  </div>
</template>

<style scoped>
.home-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.home-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
}

.notification-banner {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 16px;
  margin-bottom: var(--spacing-lg);
  cursor: pointer;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
  transition: transform 0.2s, box-shadow 0.2s;
}

.notification-banner:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px var(--canvas-card-shadow);
}

.notification-banner:active {
  transform: translateY(0);
}

.notification-icon {
  display: flex;
  align-items: center;
}

.notification-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  --vc-font-bold: initial;
}

.notification-highlight {
  flex: 1;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.notification-arrow {
  color: var(--color-text-tertiary);
}

/* Notification Modal */
.notification-modal {
  padding: var(--spacing-md);
  padding-top: var(--spacing-xl);
  background: #1F2937;
  border-radius: var(--radius-lg);
}

.modal-header {
  text-align: center;
  margin-bottom: var(--spacing-md);
}

.modal-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: #F9FAFB;
}

.notification-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  max-height: 300px;
  overflow-y: auto;
  padding-right: var(--spacing-xs);
}

.notification-list::-webkit-scrollbar {
  width: 4px;
}

.notification-list::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
}

.notification-list::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 4px;
}

.notification-list::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.5);
}

.notification-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: #374151;
  border-radius: var(--radius-md);
}

.notification-item.unread {
  background: #4F46E5;
}

.noti-icon-wrap {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-full);
  color: #A5B4FC;
  flex-shrink: 0;
}

.noti-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.noti-title {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: #F9FAFB;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.noti-desc {
  font-size: 10px;
  color: #9CA3AF;
}

.noti-time {
  font-size: 10px;
  color: #9CA3AF;
  white-space: nowrap;
}

.section {
  margin-bottom: var(--spacing-2xl);
}

.section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-xs);
  text-align: center;
}

.indices-swipe-container {
  position: relative;
  overflow: hidden;
}

.swipe-fade {
  position: absolute;
  top: 0;
  bottom: 10px;
  width: 20px;
  z-index: 5;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.swipe-fade.visible {
  opacity: 1;
}

.swipe-fade.left {
  left: 0;
  background: linear-gradient(to right, rgba(17, 24, 39, 0.6) 0%, transparent 100%);
}

.swipe-fade.right {
  right: 0;
  background: linear-gradient(to left, rgba(17, 24, 39, 0.6) 0%, transparent 100%);
}

.indices-scroll {
  display: flex;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  padding: 0 8%;
  gap: 12px;
}

.indices-scroll::-webkit-scrollbar {
  display: none;
}

.indices-card {
  background: var(--color-bg-highlight);
  border-radius: var(--radius-lg);
  padding: 6px var(--spacing-sm);
  flex: 0 0 84%;
  scroll-snap-align: center;
}

.indices-row {
  display: flex;
  align-items: center;
  justify-content: space-around;
}

.index-item-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  min-width: 0;
}

.index-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
  padding: 4px 0;
  min-height: 48px;
}

.empty-state {
  flex: 0 0 84%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-lg);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  background: var(--color-bg-highlight);
  border-radius: var(--radius-lg);
  scroll-snap-align: center;
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  min-height: 92px;
  padding: var(--spacing-lg);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  background: var(--color-bg-highlight);
  border-radius: var(--radius-lg);
}

.loading-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.news-empty,
.ai-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-lg);
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 12px;
}

.ai-empty {
  grid-column: 1 / -1;
}

.vertical-divider {
  width: 1px;
  height: 36px;
  background: var(--color-border);
}

.index-label {
  font-size: 11px;
  color: var(--color-text-secondary);
  line-height: 1.2;
}

.index-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: 1.3;
}

.index-change {
  font-size: 10px;
  line-height: 1.2;
}

.index-change.positive {
  color: var(--color-stock-up);
}

.index-change.negative {
  color: var(--color-stock-down);
}

.index-divider {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 2px 0;
  gap: var(--spacing-sm);
}

.divider-line {
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

.index-tab {
  /* 수평/수직 가운데 정렬 */
  display: flex;
  align-items: center;     /* 수직 정렬 */
  justify-content: center; /* 수평 정렬 */

  /* 크기 설정 */
  width: 60px;             /* 고정 너비 */
  height: 20px;            /* 줄어든 높이 */

  /* 스타일 */
  border: 1px solid #F59E0B;
  border-radius: 12px;     /* 알약 형태 디자인 */
  margin: 0 10px;          /* 선과 탭 사이 간격 */
}

.tab-text {
  font-size: var(--font-size-xs);
  color: #F59E0B;
  white-space: nowrap;
  line-height: 1;          /* 텍스트 자체의 높이 간섭 제거 */
}

.slide-indicators {
  display: flex;
  justify-content: center;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-xs);
}

.indicator {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-border);
  transition: all 0.2s ease;
  cursor: pointer;
}

.indicator:hover {
  background: var(--color-text-tertiary);
}

.indicator.active {
  width: 18px;
  border-radius: 3px;
  background: var(--color-primary);
}

.news-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.news-more-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  margin: var(--spacing-sm) auto 0;
  padding: var(--spacing-xs) var(--spacing-md);
  background: none;
  border: none;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s;
}

.news-more-btn:hover {
  color: var(--color-text-primary);
}

.news-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  transition: transform 0.2s, box-shadow 0.2s;
  position: relative;
}

.news-item:has(.news-title:hover),
.news-item:has(.news-title:active) {
  z-index: 20;
}

.news-item:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
}

.news-thumb {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  flex-shrink: 0;
}

.news-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.news-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  position: relative;
}

.news-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  padding: 2px 4px;
  margin: -2px -4px;
  border-radius: 4px;
}

.news-title:hover,
.news-title:active {
  white-space: normal;
  word-break: keep-all;
  word-wrap: break-word;
  overflow: visible;
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
  z-index: 10;
  padding: 6px 8px;
  margin: -6px -8px;
  line-height: 1.4;
}

.news-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.news-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.news-source {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.news-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.exchange-swipe-container {
  position: relative;
  overflow: hidden;
}

.exchange-scroll {
  display: flex;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  padding: 0 8%;
  gap: 12px;
}

.exchange-scroll::-webkit-scrollbar {
  display: none;
}

.exchange-card {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 16px;
  padding: var(--spacing-md);
  flex: 0 0 84%;
  scroll-snap-align: center;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
}

.exchange-info {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--spacing-sm);
}

.exchange-left {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.currency-badge {
  background: var(--color-primary);
  color: #fff;
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}

.country-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.exchange-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.rate-value {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.rate-change {
  font-size: var(--font-size-xs);
}

.rate-change.positive {
  color: var(--color-stock-up);
}

.rate-change.negative {
  color: var(--color-stock-down);
}

.exchange-chart {
  height: 50px;
  margin-top: var(--spacing-xs);
}

.mini-chart {
  width: 100%;
  height: 100%;
}

.ai-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-md);
}

.ai-card {
  position: relative;
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 16px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  border: 1px solid var(--canvas-hairline-soft);
}

.ai-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.1) 0%, rgba(124, 58, 237, 0.1) 100%);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.ai-card:hover::before {
  opacity: 1;
}

.ai-card-gradient {
  position: absolute;
  top: -50%;
  right: -50%;
  width: 200%;
  height: 200%;
  background: radial-gradient(circle, rgba(79, 70, 229, 0.15) 0%, transparent 70%);
  opacity: 0;
  transition: opacity 0.5s ease;
  pointer-events: none;
}

.ai-card:hover .ai-card-gradient {
  opacity: 1;
}

.ai-card:hover {
  transform: translateY(-4px) scale(1.02);
  box-shadow: 0 12px 32px rgba(79, 70, 229, 0.25), 0 0 0 1px rgba(79, 70, 229, 0.3);
  border-color: rgba(79, 70, 229, 0.3);
}

.ai-card:active {
  transform: translateY(-2px) scale(1.01);
}

.ai-content {
  position: relative;
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  z-index: 1;
}

.ai-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.ai-logo-container {
  position: relative;
}

.ai-logo-container::before {
  content: '';
  position: absolute;
  inset: -3px;
  background: linear-gradient(135deg, #4F46E5, #7C3AED);
  border-radius: 10px;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.ai-card:hover .ai-logo-container::before {
  opacity: 0.6;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.6; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(1.05); }
}

.ai-logo {
  position: relative;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  flex-shrink: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.ai-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  padding: 4px;
}

.ai-logo-placeholder {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.ai-title-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ai-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-tag {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #A78BFA;
  font-weight: var(--font-weight-medium);
}

.ai-tag svg {
  flex-shrink: 0;
}

.ai-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: 1.4;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
}

.ai-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--spacing-xs);
  margin-top: auto;
  border-top: 1px solid var(--canvas-hairline-soft);
  opacity: 0;
  transform: translateY(4px);
  transition: all 0.3s ease;
}

.ai-card:hover .ai-footer {
  opacity: 1;
  transform: translateY(0);
}

.ai-action {
  font-size: 11px;
  font-weight: var(--font-weight-medium);
  color: #A78BFA;
}

.ai-footer svg {
  color: #A78BFA;
  transition: transform 0.3s ease;
}

.ai-card:hover .ai-footer svg {
  transform: translateX(4px);
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

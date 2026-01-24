<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { mockMarketIndices, mockExchangeRates, mockTopNews, mockAiRecommendations } from '@/services/mockData'

const router = useRouter()

const indices = ref(mockMarketIndices)
const exchangeRates = ref(mockExchangeRates)
const topNews = ref(mockTopNews)
const aiRecommendations = ref(mockAiRecommendations)

const currentIndexSlide = ref(0)
const indexCategories = ['domestic', 'overseas', 'coin']
const scrollContainerRef = ref(null)

const onScroll = () => {
  if (!scrollContainerRef.value) return
  const container = scrollContainerRef.value
  const scrollLeft = container.scrollLeft
  const itemWidth = container.offsetWidth * 0.85
  const newIndex = Math.round(scrollLeft / itemWidth)
  if (newIndex !== currentIndexSlide.value && newIndex >= 0 && newIndex < indexCategories.length) {
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
const notifications = ref([
  { id: 1, type: 'trade', title: '테슬라 3주 예약 매도 체결', desc: '체결가 $248.50', time: '방금 전', read: false },
  { id: 2, type: 'trade', title: '삼성전자 10주 매수 체결', desc: '체결가 71,500원', time: '5분 전', read: false },
  { id: 3, type: 'ai', title: 'AI 매매 신호', desc: 'NVIDIA 매수 추천', time: '30분 전', read: true },
  { id: 4, type: 'price', title: '목표가 도달', desc: '애플 $180 도달', time: '1시간 전', read: true },
  { id: 5, type: 'news', title: '관심 종목 뉴스', desc: '삼성전자 실적 발표', time: '2시간 전', read: true },
  { id: 6, type: 'trade', title: '카카오 5주 매수 체결', desc: '체결가 52,300원', time: '3시간 전', read: true },
  { id: 7, type: 'ai', title: 'AI 리밸런싱 완료', desc: '포트폴리오 최적화', time: '4시간 전', read: true },
  { id: 8, type: 'price', title: '급등 알림', desc: '네이버 +5.2%', time: '5시간 전', read: true }
])

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
  return new Intl.NumberFormat('ko-KR').format(num)
}

const formatChange = (change, percent) => {
  const sign = change >= 0 ? '+' : ''
  return `${sign}${change}(${sign}${percent}%)`
}

const goToNews = (news) => {
  router.push(`/news/${news.id}`)
}

const goToCompany = (symbol) => {
  router.push(`/company/${symbol}`)
}
</script>

<template>
  <div class="home-screen">
    <AppHeader title="홈" showIcon icon="home" />

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
        <div class="indices-swipe-container">
          <div class="swipe-fade left" :class="{ visible: currentIndexSlide > 0 }"></div>
          <div class="swipe-fade right" :class="{ visible: currentIndexSlide < indexCategories.length - 1 }"></div>
          <div
            ref="scrollContainerRef"
            class="indices-scroll"
            @scroll="onScroll"
          >
            <div
              v-for="category in indexCategories"
              :key="category"
              class="indices-card"
            >
              <!-- 상단 2개 지수 -->
              <div class="indices-row">
                <div class="index-item">
                  <span class="index-label">{{ indices[category].items[0].label }}</span>
                  <span class="index-value">{{ formatNumber(indices[category].items[0].value) }}</span>
                  <span :class="['index-change', indices[category].items[0].change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(indices[category].items[0].change, indices[category].items[0].changePercent) }}
                  </span>
                </div>
                <div class="vertical-divider"></div>
                <div class="index-item">
                  <span class="index-label">{{ indices[category].items[1].label }}</span>
                  <span class="index-value">{{ formatNumber(indices[category].items[1].value) }}</span>
                  <span :class="['index-change', indices[category].items[1].change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(indices[category].items[1].change, indices[category].items[1].changePercent) }}
                  </span>
                </div>
              </div>

              <!-- Center divider with tab -->
              <div class="index-divider">
                <div class="divider-line"></div>
                <div class="index-tab">
                  <span class="tab-text">{{ indices[category].label }}</span>
                </div>
                <div class="divider-line"></div>
              </div>

              <!-- 하단 2개 지수 -->
              <div class="indices-row">
                <div class="index-item">
                  <span class="index-label">{{ indices[category].items[2].label }}</span>
                  <span class="index-value">{{ formatNumber(indices[category].items[2].value) }}</span>
                  <span :class="['index-change', indices[category].items[2].change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(indices[category].items[2].change, indices[category].items[2].changePercent) }}
                  </span>
                </div>
                <div class="vertical-divider"></div>
                <div class="index-item">
                  <span class="index-label">{{ indices[category].items[3].label }}</span>
                  <span class="index-value">{{ formatNumber(indices[category].items[3].value) }}</span>
                  <span :class="['index-change', indices[category].items[3].change >= 0 ? 'positive' : 'negative']">
                    {{ formatChange(indices[category].items[3].change, indices[category].items[3].changePercent) }}
                  </span>
                </div>
              </div>
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

      <!-- Top News -->
      <section class="section">
        <h2 class="section-title">속보 TOP5</h2>
        <div class="news-list">
          <div
            v-for="news in topNews.slice(0, 5)"
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
        </div>
      </section>

      <!-- Exchange Rates -->
      <section class="section">
        <h2 class="section-title">환율</h2>
        <div class="exchange-swipe-container">
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
                  <span :class="['rate-change', rate.change >= 0 ? 'positive' : 'negative']">
                    {{ rate.change >= 0 ? '+' : '' }}{{ rate.change.toFixed(2) }} ({{ rate.change >= 0 ? '+' : '' }}{{ rate.changePercent.toFixed(2) }}%)
                  </span>
                </div>
              </div>
              <div class="exchange-chart">
                <svg class="mini-chart" viewBox="0 0 100 40" preserveAspectRatio="none">
                  <polyline
                    :points="getChartPoints(rate.history)"
                    fill="none"
                    :stroke="rate.change >= 0 ? '#10B981' : '#EF4444'"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </div>
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
            <div class="ai-header">
              <div class="ai-logo">
                <img v-if="item.logo" :src="item.logo" :alt="item.title" />
                <span v-else class="ai-logo-placeholder">{{ item.title.charAt(0) }}</span>
              </div>
              <div class="ai-title-wrap">
                <span class="ai-title">{{ item.title }}</span>
                <span class="ai-tag">{{ item.tag }}</span>
              </div>
            </div>
            <p class="ai-desc">{{ item.description }}</p>
          </div>
        </div>
      </section>
    </div>

  </div>
</template>

<style scoped>
.home-screen {
  min-height: 100vh;
  background: var(--color-bg-primary);
  padding-bottom: var(--bottom-nav-height);
}

.content {
  padding: var(--spacing-lg);
}

.notification-banner {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--color-bg-secondary);
  border-radius: var(--radius-lg);
  margin-bottom: var(--spacing-lg);
  cursor: pointer;
}

.notification-banner:active {
  opacity: 0.8;
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
}

.news-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg-card);
  border-radius: var(--radius-md);
  cursor: pointer;
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
}

.news-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.news-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
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
  background: var(--color-bg-highlight);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  flex: 0 0 84%;
  scroll-snap-align: center;
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
  gap: var(--spacing-sm);
}

.ai-card {
  background: var(--color-bg-accent);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxs);
  cursor: pointer;
  transition: transform 0.2s ease;
}

.ai-card:active {
  transform: scale(0.98);
}

.ai-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.ai-logo {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  flex-shrink: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.ai-logo-placeholder {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.ai-title-wrap {
  flex: 1;
  min-width: 0;
  line-height: 1.2;
}

.ai-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  display: block;
  margin-bottom: 1px;
}

.ai-tag {
  font-size: 10px;
  color: var(--color-text-tertiary);
}

.ai-desc {
  font-size: var(--font-size-xs);
  color: rgba(255, 255, 255, 0.75);
  line-height: 1.3;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

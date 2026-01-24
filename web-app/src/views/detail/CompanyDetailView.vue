<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { mockCompanyInfo } from '@/services/mockData'

const route = useRoute()
const router = useRouter()

const activeTab = ref('basic')
const company = ref(mockCompanyInfo)
const isFavorite = ref(false)

const toggleFavorite = () => {
  isFavorite.value = !isFavorite.value
}

const tabs = [
  { key: 'basic', label: '기본정보' },
  { key: 'ai', label: 'AI분석' },
  { key: 'financial', label: '재무제표' },
  { key: 'disclosure', label: '공시정보' }
]

const formatNumber = (num) => {
  return new Intl.NumberFormat('ko-KR').format(num)
}

const goToNews = () => {
  router.push(`/news?symbol=${company.value.symbol}`)
}

const goToTrade = () => {
  router.push(`/trading/${company.value.symbol}`)
}

onMounted(() => {
  // Fetch company data based on route.params.symbol
})
</script>

<template>
  <div class="company-detail-screen">
    <AppHeader title="기업 상세 정보" showBack />

    <div class="content">
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

      <!-- Company Header -->
      <div class="company-header">
        <div class="company-logo">
          <img :src="`https://logo.clearbit.com/${company.symbol.toLowerCase()}.com`" :alt="company.name" />
        </div>
        <span class="company-name">{{ company.name }} ({{ company.symbol }})</span>
        <button class="favorite-btn" @click="toggleFavorite">
          <van-icon
            :name="isFavorite ? 'star' : 'star-o'"
            :color="isFavorite ? '#F59E0B' : '#9CA3AF'"
            size="24"
          />
        </button>
      </div>

      <!-- Basic Info Tab -->
      <div v-if="activeTab === 'basic'" class="tab-content">
        <div class="info-badge">기본정보</div>

        <!-- Score Gauges -->
        <div class="score-grid">
          <div v-for="(score, key) in company.scores" :key="key" class="score-item">
            <div class="gauge">
              <svg viewBox="0 0 100 100">
                <circle cx="50" cy="50" r="45" fill="none" stroke="#E5E7EB" stroke-width="8"/>
                <circle
                  cx="50" cy="50" r="45"
                  fill="none"
                  stroke="#F59E0B"
                  stroke-width="8"
                  :stroke-dasharray="`${score * 2.83} 283`"
                  transform="rotate(-90 50 50)"
                />
              </svg>
              <span class="gauge-label">{{ key.toUpperCase() }}</span>
              <span class="gauge-value">{{ score }}</span>
            </div>
          </div>
        </div>

        <!-- Company Info Card -->
        <div class="info-card">
          <div class="info-row">
            <span class="info-label">기업명</span>
            <span class="info-value">{{ company.nameEn }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">주소</span>
            <span class="info-value">{{ company.address }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">홈페이지</span>
            <a class="info-value link" :href="company.website" target="_blank">{{ company.website }}</a>
          </div>
          <div class="info-row">
            <span class="info-label">섹터</span>
            <span class="info-value">{{ company.sector }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">직원수</span>
            <span class="info-value">{{ formatNumber(company.employees) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">주식수</span>
            <span class="info-value">{{ formatNumber(company.shares) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">재무통화</span>
            <span class="info-value">{{ company.currency }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">시가총액</span>
            <span class="info-value">{{ formatNumber(company.marketCap) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">배당</span>
            <span class="info-value">{{ company.dividend }}</span>
          </div>
          <div class="info-row full">
            <span class="info-label">개요</span>
            <p class="info-desc">{{ company.description }}</p>
          </div>
        </div>
      </div>

      <!-- Other tabs placeholder -->
      <div v-else class="tab-content">
        <div class="empty-tab">
          <p>{{ tabs.find(t => t.key === activeTab)?.label }} 정보를 불러오는 중...</p>
        </div>
      </div>

      <!-- Action Buttons -->
      <div class="action-buttons">
        <button class="action-btn news" @click="goToNews">뉴스</button>
        <button class="action-btn trade" @click="goToTrade">매매</button>
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

.sub-tabs {
  display: flex;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-lg);
  overflow-x: auto;
}

.sub-tab {
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg-tertiary);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  white-space: nowrap;
}

.sub-tab.active {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.company-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.company-logo {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg-tertiary);
}

.company-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.company-name {
  flex: 1;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.favorite-btn {
  background: none;
  border: none;
  padding: var(--spacing-xs);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.info-badge {
  display: inline-block;
  padding: var(--spacing-sm) var(--spacing-lg);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  margin-bottom: var(--spacing-lg);
}

.score-grid {
  display: flex;
  justify-content: space-around;
  margin-bottom: var(--spacing-lg);
}

.score-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.gauge {
  position: relative;
  width: 60px;
  height: 60px;
}

.gauge svg {
  width: 100%;
  height: 100%;
}

.gauge-label {
  position: absolute;
  top: 15px;
  left: 50%;
  transform: translateX(-50%);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.gauge-value {
  position: absolute;
  bottom: 15px;
  left: 50%;
  transform: translateX(-50%);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: #F59E0B;
}

.info-card {
  background: var(--color-bg-secondary);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: var(--spacing-sm) 0;
  border-bottom: 1px solid var(--color-border-light);
}

.info-row.full {
  flex-direction: column;
  gap: var(--spacing-sm);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: right;
}

.info-value.link {
  color: var(--color-primary);
  text-decoration: none;
}

.info-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.empty-tab {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  color: var(--color-text-tertiary);
}

.action-buttons {
  display: flex;
  gap: var(--spacing-md);
  margin-top: var(--spacing-xl);
}

.action-btn {
  flex: 1;
  padding: var(--spacing-md);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
}

.action-btn.news {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.action-btn.trade {
  background: var(--color-secondary);
  color: var(--color-text-inverse);
}
</style>

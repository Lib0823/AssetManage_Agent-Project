<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import AssetTabs from '@/components/common/AssetTabs.vue'
import { newsApi } from '@/services/api'

const route = useRoute()
const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })
const newTabList = [
  { key: 'stocks', label: '주식', disabled: false },
  { key: 'coins', label: '코인', disabled: false }
]
const dateFilters = [
  { key: 'today', label: '오늘' },
  { key: 'yesterday', label: '어제' },
  { key: 'week', label: '일주일' },
  { key: 'month', label: '1개월' }
]
const selectedDateFilter = ref('today')
const sortOrders = ['최신순', '조회순', '추천순']
const sortOrderIndex = ref(0)
const searchQuery = ref('')
const searchActive = ref(false)
const newsList = ref([])
const loading = ref(false)

const symbol = computed(() => route.query.symbol || '')

// Client-side title search over the server-filtered list.
// Only filters once the user actively edits the box, so a symbol-prefill
// (which just reflects the searched state) never hides server results.
const filteredNews = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!searchActive.value || !q) return newsList.value
  return newsList.value.filter((n) => (n.title || '').toLowerCase().includes(q))
})

const onSearchInput = () => {
  searchActive.value = true
}

const formatDate = (item) => {
  const raw = item.published_at || item.analysis_date
  if (!raw) return ''
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return String(raw)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const formatTags = (tags) => {
  if (!Array.isArray(tags) || tags.length === 0) return ''
  return tags.map((t) => `#${t}`).join(' ')
}

const sentimentLabel = (label) => {
  const map = { positive: '긍정', negative: '부정', neutral: '중립' }
  return map[label] || ''
}

const selectDateFilter = (key) => {
  selectedDateFilter.value = key
}

const toggleSortOrder = () => {
  sortOrderIndex.value = (sortOrderIndex.value + 1) % sortOrders.length
}

const goToNewsDetail = (news) => {
  router.push(`/news/${news.id}`)
}

const loadNews = async () => {
  loading.value = true
  try {
    const params = {}
    if (symbol.value) params.symbol = symbol.value
    const res = await newsApi.getList(params)
    newsList.value = (res && res.success && Array.isArray(res.data)) ? res.data : []

    // Reflect the filtered state in the search bar (stock name fallback to symbol)
    if (symbol.value) {
      const first = newsList.value[0]
      searchQuery.value = (first && first.stock_name) || symbol.value
    }
  } catch (error) {
    console.error('Failed to load news:', error)
    newsList.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadNews()
})
</script>

<template>
  <div class="news-screen">
    <AppHeader title="뉴스" showBack />

    <div class="content">
      <!-- Tabs -->
      <AssetTabs v-model="tabs" :tabs="newTabList" />

      <!-- Date Filter Buttons -->
      <div class="date-filter-section">
        <button
          v-for="filter in dateFilters"
          :key="filter.key"
          :class="['date-filter-btn', { active: selectedDateFilter === filter.key }]"
          @click="selectDateFilter(filter.key)"
        >
          {{ filter.label }}
        </button>
      </div>

      <!-- Search and Sort -->
      <div class="filter-bar">
        <button class="sort-btn" @click="toggleSortOrder">
          {{ sortOrders[sortOrderIndex] }}
        </button>
        <div class="search-input-wrapper">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="제목 / 내용"
            class="search-input"
            @input="onSearchInput"
          />
          <button class="search-btn">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2"/>
              <path d="M21 21L16.5 16.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- News List -->
      <div class="news-list">
        <div
          v-for="news in filteredNews"
          :key="news.id"
          class="news-item"
          @click="goToNewsDetail(news)"
        >
          <div class="news-content">
            <div class="news-title-row">
              <h3 class="news-title">{{ news.title }}</h3>
              <span
                v-if="sentimentLabel(news.sentiment_label)"
                :class="['sentiment-chip', news.sentiment_label]"
              >
                {{ sentimentLabel(news.sentiment_label) }}
              </span>
            </div>
            <p class="news-tags" v-if="news.tags && news.tags.length">{{ formatTags(news.tags) }}</p>
          </div>
          <div class="news-meta">
            <span class="news-source" v-if="news.source">{{ news.source }}</span>
            <span class="news-date">{{ formatDate(news) }}</span>
          </div>
        </div>

        <div v-if="!loading && filteredNews.length === 0" class="news-empty">
          뉴스가 없습니다
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.news-screen {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-primary);
  overflow: hidden;
}

.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.content :deep(.asset-tabs) {
  flex-shrink: 0;
}

.date-filter-section {
  display: flex;
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  padding: 4px;
  gap: 4px;
  margin: 0 var(--spacing-lg) var(--spacing-md);
  flex-shrink: 0;
}

.date-filter-btn {
  flex: 1;
  padding: var(--spacing-sm) var(--spacing-md);
  background: transparent;
  border: none;
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.date-filter-btn.active {
  background: #F59E0B;
  color: var(--color-text-inverse);
  font-weight: var(--font-weight-medium);
}

.date-filter-btn:hover:not(.active) {
  background: rgba(245, 158, 11, 0.1);
}

.filter-bar {
  display: flex;
  gap: var(--spacing-md);
  margin: 0 var(--spacing-lg) var(--spacing-md);
  flex-shrink: 0;
}

.sort-btn {
  padding: var(--spacing-sm) var(--spacing-md);
  background: #F59E0B;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-inverse);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: all 0.2s ease;
  min-width: 80px;
}

.sort-btn:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

.sort-btn:active {
  transform: translateY(0);
}

.search-input-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  background: var(--color-bg-secondary);
  border-radius: var(--radius-md);
  padding: 0 var(--spacing-md);
}

.search-input {
  flex: 1;
  border: none;
  background: none;
  padding: var(--spacing-sm) 0;
  font-size: var(--font-size-sm);
  outline: none;
}

.search-btn {
  background: none;
  border: none;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.news-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

.news-item {
  display: flex;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background: var(--color-bg-card);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: transform 0.2s;
}

.news-item:hover {
  transform: translateY(-2px);
}

.news-thumb {
  width: 60px;
  height: 60px;
  border-radius: var(--radius-md);
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
  gap: var(--spacing-xs);
}

.news-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.news-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: 1.4;
}

.sentiment-chip {
  flex-shrink: 0;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-full);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  background: var(--color-bg-secondary);
  color: var(--color-text-secondary);
}

.sentiment-chip.positive {
  background: rgba(16, 185, 129, 0.12);
  color: #10B981;
}

.sentiment-chip.negative {
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
}

.sentiment-chip.neutral {
  background: var(--color-bg-tertiary);
  color: var(--color-text-secondary);
}

.news-empty {
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-2xl) 0;
}

.news-tags {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.news-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.news-source {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.news-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>

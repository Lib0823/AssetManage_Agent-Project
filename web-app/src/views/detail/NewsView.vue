<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import AssetTabs from '@/components/common/AssetTabs.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { newsApi } from '@/services/api'
import { isKisOutageError } from '@/utils/kisStatus'

const route = useRoute()
const router = useRouter()

const tabs = ref({ main: 'stocks', sub: 'domestic' })
const newTabList = [
  { key: 'stocks', label: '주식', disabled: false },
  // 코인은 미지원 — AssetTabs/InvestmentTabs 기본값과 동일하게 비활성으로 노출한다.
  { key: 'coins', label: '코인', disabled: true }
]
const dateFilters = [
  { key: 'today', label: '오늘' },
  { key: 'yesterday', label: '어제' },
  { key: 'week', label: '일주일' },
  { key: 'month', label: '1개월' }
]
const selectedDateFilter = ref('today')
// 정렬 옵션은 응답(StockNewsResponse)에 실제로 존재하는 필드만 사용한다.
// 조회수/추천수 필드가 없어 '조회순'·'추천순'은 구현 불가 → 발행시각·감성점수 기준으로 대체.
const sortOrders = [
  { key: 'latest', label: '최신순' },
  { key: 'oldest', label: '오래된순' },
  { key: 'positive', label: '긍정순' }
]
const sortOrderIndex = ref(0)
const currentSort = computed(() => sortOrders[sortOrderIndex.value])
const searchQuery = ref('')
const searchActive = ref(false)
// ?symbol= 진입 시 검색창에 채워 넣은 값. 사용자 입력과 구분하기 위해 따로 기억한다.
const prefilledQuery = ref('')
const newsList = ref([])
const loading = ref(false)
// 서버 장애를 "뉴스가 없습니다"로 오인하지 않도록 SearchView/CompanyDetailView와 동일한 패턴.
const newsOutage = ref(false)

const symbol = computed(() => route.query.symbol || '')

const DAY_MS = 24 * 60 * 60 * 1000

// 국내 종목코드는 6자리 숫자, 해외는 영문 심볼 (SearchView/FavoritesView 와 동일 규칙)
const isDomesticCode = (code) => /^\d{6}$/.test(String(code || ''))

// 뉴스 1건의 기준 시각(ms). 발행시각 우선, 없으면 분석 기준일. 파싱 불가면 null.
const newsTimestamp = (item) => {
  const raw = item?.published_at || item?.analysis_date
  if (!raw) return null
  const t = new Date(raw).getTime()
  return Number.isNaN(t) ? null : t
}

const startOfDay = (ts) => {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

// 탭(주식/코인 · 국내/해외)만 적용한 목록.
// 코인 탭은 비활성이라 선택될 수 없지만, 선택되더라도 주식 뉴스를 보여주지 않도록 방어한다.
const tabFilteredNews = computed(() => {
  if (tabs.value.main !== 'stocks') return []
  const wantDomestic = tabs.value.sub === 'domestic'
  return newsList.value.filter((n) => isDomesticCode(n.stock_code) === wantDomestic)
})

// 날짜 필터의 기준일.
// 파이프라인이 평일 08:50에만 돌아 주말·휴장일에는 최신 뉴스가 며칠 전일 수 있다.
// 벽시계 기준으로 자르면 '오늘'이 항상 비어 보이므로 "목록에서 가장 최신 뉴스의 날짜"를 기준일로 삼는다.
// 전체 목록이 아니라 탭 필터 적용 후 목록에서 뽑아야 한다 — 해외 뉴스가 국내보다 최신이면
// 국내 탭의 '오늘'이 통째로 비어 보인다.
const anchorDay = computed(() => {
  const stamps = tabFilteredNews.value.map(newsTimestamp).filter((t) => t !== null)
  return startOfDay(stamps.length > 0 ? Math.max(...stamps) : Date.now())
})

// 기준일로부터 며칠 전인지 (0 = 기준일 당일)
const daysBeforeAnchor = (item) => {
  const ts = newsTimestamp(item)
  if (ts === null) return null
  return Math.round((anchorDay.value - startOfDay(ts)) / DAY_MS)
}

const matchesDateFilter = (item) => {
  const diff = daysBeforeAnchor(item)
  // 날짜를 알 수 없는 항목은 숨기지 않는다 (데이터가 조용히 사라지는 편보다 낫다)
  if (diff === null) return true
  switch (selectedDateFilter.value) {
    case 'today':
      return diff === 0
    case 'yesterday':
      return diff === 1
    case 'week':
      return diff >= 0 && diff < 7
    case 'month':
      return diff >= 0 && diff < 30
    default:
      return true
  }
}

const sortNews = (list) => {
  const sorted = [...list]
  const sortKey = currentSort.value.key
  if (sortKey === 'positive') {
    // 감성점수 내림차순. 점수 없는 항목은 뒤로.
    return sorted.sort((a, b) => {
      const sa = Number(a.sentiment_score)
      const sb = Number(b.sentiment_score)
      const va = Number.isNaN(sa) ? -Infinity : sa
      const vb = Number.isNaN(sb) ? -Infinity : sb
      return vb - va
    })
  }
  // latest / oldest. 시각 없는 항목은 항상 뒤로.
  return sorted.sort((a, b) => {
    const ta = newsTimestamp(a)
    const tb = newsTimestamp(b)
    if (ta === null && tb === null) return 0
    if (ta === null) return 1
    if (tb === null) return -1
    return sortKey === 'oldest' ? ta - tb : tb - ta
  })
}

// 탭(주식/코인 · 국내/해외) → 날짜 → 검색어 순으로 걸러낸 뒤 정렬한다.
// Client-side title search over the server-filtered list.
// Only filters once the user actively edits the box, so a symbol-prefill
// (which just reflects the searched state) never hides server results.
const filteredNews = computed(() => {
  let list = tabFilteredNews.value.filter(matchesDateFilter)

  const q = searchQuery.value.trim().toLowerCase()
  if (searchActive.value && q) {
    list = list.filter((n) => (n.title || '').toLowerCase().includes(q))
  }

  return sortNews(list)
})

const onSearchInput = () => {
  // 프리필된 종목명 그대로면 필터링하지 않는다 — ?symbol= 진입 상태를 반영한 값일 뿐,
  // 사용자가 입력한 검색어가 아니다. 실제로 편집했을 때만 제목 검색을 켠다.
  searchActive.value = searchQuery.value !== prefilledQuery.value
}

// 돋보기 버튼 / 엔터: 입력값을 검색어로 확정 적용
const applySearch = () => {
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

// 날짜 필터는 클라이언트 필터링으로 처리한다.
// (백엔드 GET /news 의 date 파라미터는 단일 날짜만 받고 symbol 지정 시에만 적용돼
//  '일주일'·'1개월' 같은 구간 조회를 서버에서 표현할 수 없다.)
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
  newsOutage.value = false
  try {
    const params = {}
    if (symbol.value) params.symbol = symbol.value
    const res = await newsApi.getList(params)
    newsList.value = (res && res.success && Array.isArray(res.data)) ? res.data : []

    // Reflect the filtered state in the search bar (stock name fallback to symbol)
    if (symbol.value) {
      const first = newsList.value[0]
      searchQuery.value = (first && first.stock_name) || symbol.value
      prefilledQuery.value = searchQuery.value
    }
  } catch (error) {
    console.error('Failed to load news:', error)
    newsOutage.value = isKisOutageError(error)
    newsList.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 종목 지정 진입(/news?symbol=AAPL)은 해당 종목의 시장에 맞춰 국내/해외 탭을 맞춘다.
  // (기본값 '국내' 그대로 두면 해외 종목 뉴스가 탭 필터에 걸려 통째로 사라진다.)
  if (symbol.value) {
    tabs.value = { ...tabs.value, sub: isDomesticCode(symbol.value) ? 'domestic' : 'overseas' }
  }
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
          {{ currentSort.label }}
        </button>
        <div class="search-input-wrapper">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="제목 / 내용"
            class="search-input"
            @input="onSearchInput"
            @keyup.enter="applySearch"
          />
          <button class="search-btn" aria-label="뉴스 검색" @click="applySearch">
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

        <KisMaintenanceNotice
          v-if="!loading && newsOutage"
          variant="card"
          title="서버 연결 오류"
          message="일시적인 서버 오류로 뉴스를 불러올 수 없어요. 잠시 후 다시 시도해 주세요."
          class="news-notice"
        />

        <div v-else-if="!loading && filteredNews.length === 0" class="news-empty">
          {{ newsList.length > 0 ? '조건에 맞는 뉴스가 없습니다' : '뉴스가 없습니다' }}
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

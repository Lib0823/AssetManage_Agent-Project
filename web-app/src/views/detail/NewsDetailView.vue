<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { newsApi } from '@/services/api'

const route = useRoute()
const router = useRouter()

const news = ref(null)
const relatedNews = ref([])
const loading = ref(false)

const newsDate = computed(() => {
  if (!news.value) return ''
  const raw = news.value.published_at || news.value.analysis_date
  if (!raw) return ''
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return String(raw)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

const openOriginal = () => {
  if (news.value && news.value.url) {
    window.open(news.value.url, '_blank')
  }
}

const goToRelatedNews = (related) => {
  router.push(`/news/${related.id}`)
}

const loadRelated = async (stockCode, currentId) => {
  if (!stockCode) {
    relatedNews.value = []
    return
  }
  try {
    const res = await newsApi.getList({ symbol: stockCode })
    const list = (res && res.success && Array.isArray(res.data)) ? res.data : []
    relatedNews.value = list.filter((n) => n.id !== currentId).slice(0, 5)
  } catch (error) {
    console.error('Failed to load related news:', error)
    relatedNews.value = []
  }
}

const loadDetail = async (id) => {
  loading.value = true
  news.value = null
  relatedNews.value = []
  try {
    const res = await newsApi.getDetail(id)
    news.value = (res && res.success) ? res.data : null
    if (news.value && news.value.stock_code) {
      await loadRelated(news.value.stock_code, news.value.id)
    }
  } catch (error) {
    console.error('Failed to load news detail:', error)
    news.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadDetail(route.params.id)
})

// Re-fetch when navigating between related news (same route, new id)
watch(() => route.params.id, (id) => {
  if (id) loadDetail(id)
})
</script>

<template>
  <div class="news-detail-screen">
    <AppHeader title="기사 내용" showBack />

    <div class="content" v-if="news">
      <!-- News Title -->
      <h1 class="news-title">{{ news.title }}</h1>

      <!-- News Meta -->
      <div class="news-meta">
        <span class="news-source" v-if="news.source">{{ news.source }}</span>
        <span class="news-date">{{ newsDate }}</span>
      </div>

      <!-- Tags -->
      <div class="news-tags" v-if="news.tags && news.tags.length">
        <span v-for="(tag, index) in news.tags" :key="tag" :class="['tag', `tag-${index % 4}`]">{{ tag }}</span>
      </div>

      <!-- News Content -->
      <div class="news-content-card">
        <p class="news-content">{{ news.summary }}</p>
      </div>

      <!-- Original Article Link -->
      <button v-if="news.url" class="original-btn" @click="openOriginal">
        원문 보기
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
          <path d="M15 3h6v6M10 14L21 3"/>
        </svg>
      </button>

      <!-- Related News -->
      <section class="related-section" v-if="relatedNews.length">
        <h2 class="section-title">관련 뉴스</h2>
        <div class="related-list">
          <div
            v-for="related in relatedNews"
            :key="related.id"
            class="related-item"
            @click="goToRelatedNews(related)"
          >
            <span class="related-title">{{ related.title }}</span>
          </div>
        </div>
      </section>
    </div>

    <div class="content" v-else-if="!loading">
      <p class="news-empty">기사를 찾을 수 없습니다</p>
    </div>
  </div>
</template>

<style scoped>
.news-detail-screen {
  min-height: 100vh;
  background: var(--color-bg-primary);
}

.content {
  padding: var(--spacing-lg);
  padding-bottom: var(--spacing-2xl);
}

.news-title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: 1.4;
  text-align: center;
  margin-bottom: var(--spacing-md);
  padding: 0 var(--spacing-md);
}

.news-meta {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.news-source {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
  position: relative;
}

.news-source::after {
  content: '';
  position: absolute;
  right: calc(var(--spacing-md) / -2 - 0.5px);
  top: 50%;
  transform: translateY(-50%);
  width: 1px;
  height: 12px;
  background: var(--color-text-tertiary);
}

.news-date {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}

.news-image {
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: var(--spacing-xl);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}

.news-image img {
  width: 100%;
  height: auto;
  display: block;
}

.news-tags {
  display: flex;
  flex-wrap: nowrap;

  overflow-x: auto;
  -webkit-overflow-scrolling: touch;

  justify-content: flex-start;

  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-lg);
  padding: 0 var(--spacing-md);

  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE/Edge */
}

/* 크롬, 사파리에서 스크롤바 숨기기 */
.news-tags::-webkit-scrollbar {
  display: none;
}

.tag {
  flex: 0 0 auto;

  padding: var(--spacing-xs) var(--spacing-md);
  border-radius: var(--radius-full);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  background: var(--color-bg-secondary);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-bg-tertiary);
  transition: all 0.2s;
  cursor: default;
}

.news-content-card {
  border-radius: var(--radius-xl);
  padding: var(--spacing-sm);
  margin-bottom: var(--spacing-3xl);
}

.news-content {
  font-size: var(--font-size-base);
  color: var(--color-text-primary);
  line-height: 1.8;
  white-space: pre-line;
}

.related-section {
  margin-top: var(--spacing-xl);
}

.section-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  text-align: left;
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-sm);
  border-bottom: 2px solid var(--color-bg-secondary);
}

.related-list {
  display: flex;
  gap: var(--spacing-md);
  overflow-x: auto;
  padding-bottom: var(--spacing-sm);
}

.related-item {
  flex: 0 0 180px;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--color-bg-card);
  border-radius: var(--radius-lg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

.related-item:hover {
  transform: translateY(-4px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
}

.related-title {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-weight: var(--font-weight-medium);
}

.original-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-lg);
  margin-bottom: var(--spacing-xl);
  background: var(--color-bg-secondary);
  border: 1px solid var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: all 0.2s;
}

.original-btn:hover {
  background: var(--color-bg-tertiary);
  transform: translateY(-1px);
}

.news-empty {
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-base);
  padding: var(--spacing-3xl) 0;
}
</style>

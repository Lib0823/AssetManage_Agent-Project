<script setup>
/**
 * 코인 검색 화면 (`/coins`).
 *
 * **마켓 목록은 서버 왕복 없이 클라이언트에서 필터링한다.** 원화마켓은 288개 안팎으로 작고
 * 자주 바뀌지 않아 한 번 받아두면 충분하며, 글자마다 서버를 부르면 업비트 시세 한도
 * (IP당 10 req/s, 전체 사용자 공유)를 검색만으로 소진한다.
 *
 * 시세는 **화면에 보이는 목록만 배치 1회**로 가져온다. 목록 전체(288개)의 시세를 받아
 * 봐야 대부분은 스크롤 밖이고, 반대로 행마다 부르면 같은 한도를 즉시 태운다.
 */
import { ref, computed, onMounted, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { coinApi } from '@/services/api'
import { logger } from '@/utils/logger'
import {
  cautionLabel,
  changeClass,
  formatCoinPrice,
  formatSignedRate,
  symbolOf
} from '@/utils/coin'

const router = useRouter()

/** 한 번에 시세를 붙일 최대 종목 수. 배치 1회 안에 담기는 상한이자 렌더 상한. */
const VISIBLE_LIMIT = 40

const markets = ref([])
const loading = ref(true)
const errorMessage = ref('')
const notice = ref('')

const searchQuery = ref('')
// 마켓코드별 티커 캐시. 배치 응답을 여기에 병합한다.
const tickerMap = ref({})
const tickerLoading = ref(false)

let searchTimer = null

const loadMarkets = async () => {
  loading.value = true
  errorMessage.value = ''
  notice.value = ''
  try {
    const res = await coinApi.getMarkets()
    const data = res?.data ?? null
    markets.value = Array.isArray(data?.markets) ? data.markets : []
    notice.value = data?.notice || ''
    if (markets.value.length === 0 && !notice.value) {
      notice.value = '마켓 목록이 비어 있습니다. 잠시 후 다시 시도해 주세요.'
    }
  } catch (error) {
    logger.debug('코인 마켓 목록 조회 실패:', error)
    markets.value = []
    errorMessage.value = '코인 목록을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

// 한글명·영문명·심볼·마켓코드 전부 검색 대상. 영문은 대소문자를 가리지 않는다.
const filtered = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return markets.value
  return markets.value.filter((m) => {
    const korean = String(m.koreanName ?? '')
    const english = String(m.englishName ?? '').toLowerCase()
    const symbol = String(m.symbol ?? '').toLowerCase()
    const market = String(m.market ?? '').toLowerCase()
    return korean.includes(q) || english.includes(q) || symbol.includes(q) || market.includes(q)
  })
})

const visible = computed(() => filtered.value.slice(0, VISIBLE_LIMIT))
const hiddenCount = computed(() => Math.max(0, filtered.value.length - visible.value.length))

/**
 * 현재 보이는 목록의 시세를 **배치 1회**로 가져온다.
 * 종목별 루프는 절대 금지 — IP당 10 req/s 를 전체 사용자가 공유한다.
 */
const loadVisibleTickers = async () => {
  const codes = visible.value.map((m) => m.market).filter(Boolean)
  if (codes.length === 0) return

  tickerLoading.value = true
  try {
    const res = await coinApi.getTickers(codes)
    const list = Array.isArray(res?.data) ? res.data : []
    const next = { ...tickerMap.value }
    for (const t of list) {
      if (t?.market) next[t.market] = t
    }
    tickerMap.value = next
  } catch (error) {
    // 시세는 부가 정보다. 실패해도 목록 자체는 그대로 쓸 수 있게 둔다.
    logger.debug('코인 시세 배치 조회 실패:', error)
  } finally {
    tickerLoading.value = false
  }
}

// 검색어가 바뀌면 보이는 목록도 바뀐다. 타자마다 부르지 않도록 디바운스한다.
watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(loadVisibleTickers, 350)
})

onMounted(async () => {
  await loadMarkets()
  await loadVisibleTickers()
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})

const tickerOf = (market) => tickerMap.value[market] ?? null

const goToDetail = (market) => {
  if (!market?.market) return
  router.push(`/coins/${market.market}`)
}
</script>

<template>
  <div class="coin-search-screen">
    <AppHeader title="코인 검색" showBack />

    <div class="content">
      <div class="search-bar">
        <input
          v-model="searchQuery"
          type="text"
          class="search-input"
          placeholder="코인명·심볼 (예: 비트코인, BTC)"
        />
      </div>

      <p class="scope-note">업비트 <strong>원화(KRW) 마켓</strong>만 지원합니다.</p>

      <p v-if="notice" class="notice-box">{{ notice }}</p>

      <div v-if="loading" class="state-box">불러오는 중...</div>

      <div v-else-if="errorMessage" class="state-box">
        {{ errorMessage }}
        <button type="button" class="retry-btn" @click="loadMarkets">다시 시도</button>
      </div>

      <div v-else-if="filtered.length === 0" class="state-box">검색 결과가 없습니다</div>

      <div v-else class="coin-list">
        <button
          v-for="market in visible"
          :key="market.market"
          type="button"
          class="coin-row"
          @click="goToDetail(market)"
        >
          <span class="coin-main">
            <span class="coin-name-row">
              <span class="coin-name">{{ market.koreanName || market.symbol }}</span>
              <!-- 유의/주의 종목 배지. 실제 자금이 오가는 화면이라 목록에서부터 노출한다. -->
              <span v-if="market.warning" class="badge warning">유의</span>
              <span
                v-for="caution in market.cautions || []"
                :key="caution"
                class="badge caution"
              >
                {{ cautionLabel(caution) }}
              </span>
            </span>
            <span class="coin-sub">{{ symbolOf(market.market) }} · {{ market.market }}</span>
          </span>

          <span class="coin-price">
            <template v-if="tickerOf(market.market)">
              <span class="price-value">
                {{ formatCoinPrice(tickerOf(market.market).tradePrice) }}
              </span>
              <span
                class="price-change"
                :class="changeClass(
                  tickerOf(market.market).change,
                  tickerOf(market.market).signedChangeRate
                )"
              >
                {{ formatSignedRate(tickerOf(market.market).signedChangeRate) }}
              </span>
            </template>
            <span v-else class="price-value muted">{{ tickerLoading ? '…' : '—' }}</span>
          </span>

          <span class="coin-arrow">›</span>
        </button>

        <p v-if="hiddenCount > 0" class="more-note">
          {{ hiddenCount }}개가 더 있습니다. 검색어를 입력해 범위를 좁혀 주세요.
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.coin-search-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: var(--spacing-xl);
}

.coin-search-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
}

.search-bar {
  display: flex;
  gap: 8px;
}

.search-input {
  flex: 1;
  padding: 12px 14px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: var(--canvas-hairline-faint);
  color: var(--color-text-primary);
  font-size: 15px;
}

.search-input:focus {
  outline: none;
  border-color: #F59E0B;
}

.scope-note {
  margin-top: 8px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.notice-box {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
}

.state-box {
  margin-top: 24px;
  padding: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--color-text-secondary);
}

.retry-btn {
  display: block;
  margin: 12px auto 0;
  padding: 8px 16px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
}

.coin-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.coin-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 14px;
  background: var(--canvas-card-start);
  cursor: pointer;
  text-align: left;
}

.coin-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.coin-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.coin-name {
  font-size: 15px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.coin-sub {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.badge {
  padding: 1px 6px;
  border-radius: var(--radius-full);
  font-size: 10px;
  font-weight: var(--font-weight-semibold);
  white-space: nowrap;
}

.badge.warning {
  background: rgba(239, 68, 68, 0.16);
  color: #EF4444;
}

.badge.caution {
  background: rgba(245, 158, 11, 0.16);
  color: #F59E0B;
}

.coin-price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.price-value {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.price-value.muted {
  color: var(--color-text-tertiary);
}

.price-change {
  font-size: 12px;
  font-weight: var(--font-weight-medium);
}

.price-change.positive {
  color: #EF4444;
}

.price-change.negative {
  color: #3B82F6;
}

.price-change.flat {
  color: var(--color-text-tertiary);
}

.coin-arrow {
  font-size: 18px;
  color: var(--color-text-tertiary);
}

.more-note {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>

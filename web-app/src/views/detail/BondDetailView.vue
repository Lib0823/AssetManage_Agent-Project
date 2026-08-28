<script setup>
/**
 * 채권 상세 화면 (`/bonds/:code`).
 *
 * 진입은 자산 화면의 보유 채권 카드에서만 이루어진다 — KIS 에 채권 검색 API 가 없어
 * 검색으로 들어올 수 없다. 그래서 이 화면은 "보유 로트 1건"을 전제로 하고, 라우트 쿼리로
 * 받은 로트 정보(매수일·매수순번·수량·분리과세 추정값)를 매도 화면까지 그대로 넘긴다.
 * 이 값들은 사용자가 입력하는 값이 아니며, 빠지면 매도 요청이 400 이 된다.
 *
 * 시세·발행정보 4종은 `Promise.allSettled` 로 병렬 조회한다 — 장내채권은 유동성이 낮아
 * 호가가 비는 것이 정상이고, 하나가 실패해도 나머지는 보여줘야 한다.
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { bondApi } from '@/services/api'
import { logger } from '@/utils/logger'
import {
  formatAmount,
  formatKisDate,
  formatQuantity,
  formatRate,
  formatUnitPrice,
  readBondLotQuery,
  textOrDash
} from '@/utils/bond'

const route = useRoute()
const router = useRouter()

const bondCode = computed(() => String(route.params.code || ''))

// 자산 화면에서 넘겨받은 보유 로트. 직접 URL 진입 시에는 비어 있을 수 있다.
const lot = ref(readBondLotQuery(route.query))

const loading = ref(true)
const bondInfo = ref(null)
const issueInfo = ref(null)
const price = ref(null)
const orderbook = ref(null)

// 4종 전부 실패(= 서버/KIS 도달 불가)했는지. 개별 notice 와 구분해 상단 안내를 띄운다.
const allQuotesFailed = ref(false)

const hasLot = computed(() => !!(lot.value.buyDate && lot.value.buySeq))
const displayName = computed(
  () => bondInfo.value?.bondName || price.value?.bondName || lot.value.bondName || bondCode.value
)

// 호가는 비는 것이 정상(유동성 부족)이므로 "없음"과 "실패"를 구분해 표시한다.
const askLevels = computed(() => orderbook.value?.asks ?? [])
const bidLevels = computed(() => orderbook.value?.bids ?? [])
const hasOrderbook = computed(() => askLevels.value.length > 0 || bidLevels.value.length > 0)

// 전일대비 부호. KIS prdy_vrss_sign: 1·2 상승, 4·5 하락, 3 보합.
const priceDirection = computed(() => {
  const diff = Number(price.value?.prevDayDiff)
  if (!Number.isFinite(diff) || diff === 0) return 'flat'
  return diff > 0 ? 'positive' : 'negative'
})

const settledValue = (result) => (result.status === 'fulfilled' ? (result.value?.data ?? null) : null)

const loadAll = async () => {
  loading.value = true
  const code = bondCode.value
  const results = await Promise.allSettled([
    bondApi.getBondInfo(code),
    bondApi.getIssueInfo(code),
    bondApi.getPrice(code),
    bondApi.getOrderbook(code)
  ])

  results.forEach((r, idx) => {
    if (r.status === 'rejected') {
      logger.debug(`채권 조회 실패 (idx=${idx}, code=${code}):`, r.reason)
    }
  })

  bondInfo.value = settledValue(results[0])
  issueInfo.value = settledValue(results[1])
  price.value = settledValue(results[2])
  orderbook.value = settledValue(results[3])

  allQuotesFailed.value = results.every((r) => r.status === 'rejected')
  loading.value = false
}

onMounted(loadAll)

// 매도 화면으로 로트 정보를 그대로 전달한다. 쿼리를 새로 만들지 않고 현재 쿼리를 넘겨
// 운반 도중 값이 유실되지 않게 한다.
const goToSell = () => {
  router.push({ path: `/bonds/${bondCode.value}/sell`, query: { ...route.query } })
}
</script>

<template>
  <div class="bond-detail-screen">
    <AppHeader title="채권 상세" showBack />

    <div class="content">
      <!-- 종목 헤더 -->
      <section class="hero">
        <h2 class="hero-name">{{ displayName }}</h2>
        <span class="hero-code">{{ bondCode }}</span>
        <span v-if="bondInfo?.bondClassName" class="hero-class">{{ bondInfo.bondClassName }}</span>
      </section>

      <div v-if="loading" class="state-box">불러오는 중...</div>

      <template v-else>
        <KisMaintenanceNotice v-if="allQuotesFailed" variant="card" />

        <!-- 보유 로트 -->
        <section class="card">
          <h3 class="card-title">보유 정보</h3>
          <p class="card-caption">매수금액 기준입니다 (평가금액이 아닙니다)</p>

          <div v-if="hasLot" class="rows">
            <div class="row">
              <span class="row-label">매수일</span>
              <span class="row-value">{{ formatKisDate(lot.buyDate) }}</span>
            </div>
            <div class="row">
              <span class="row-label">보유 수량</span>
              <span class="row-value">{{ formatQuantity(lot.quantity) }}</span>
            </div>
            <div class="row">
              <span class="row-label">주문 가능 수량</span>
              <span class="row-value">{{ formatQuantity(lot.orderableQuantity) }}</span>
            </div>
            <div class="row">
              <span class="row-label">매수 단가</span>
              <span class="row-value">{{ formatUnitPrice(lot.buyUnitPrice) }}</span>
            </div>
            <div class="row">
              <span class="row-label">매수 금액</span>
              <span class="row-value">{{ formatAmount(lot.buyAmount) }}원</span>
            </div>
            <div class="row">
              <span class="row-label">분리과세</span>
              <span class="row-value">
                {{ lot.separateTaxation === null ? '확인 필요' : lot.separateTaxation ? '분리과세' : '종합과세' }}
              </span>
            </div>
          </div>

          <p v-else class="state-box small">
            보유 로트 정보가 없습니다. 자산 화면의 채권 카드에서 다시 들어와 주세요.
          </p>
        </section>

        <!-- 시세 -->
        <section class="card">
          <h3 class="card-title">시세</h3>
          <p v-if="price?.notice" class="card-notice">{{ price.notice }}</p>

          <div class="price-main">
            <span class="price-value">{{ formatUnitPrice(price?.currentPrice) }}</span>
            <span :class="['price-change', priceDirection]">
              {{ formatUnitPrice(price?.prevDayDiff) }} ({{ formatRate(price?.prevDayRate) }})
            </span>
          </div>

          <div class="rows">
            <div class="row">
              <span class="row-label">전일 종가</span>
              <span class="row-value">{{ formatUnitPrice(price?.prevClosePrice) }}</span>
            </div>
            <div class="row">
              <span class="row-label">시가 / 고가 / 저가</span>
              <span class="row-value">
                {{ formatUnitPrice(price?.openPrice) }} / {{ formatUnitPrice(price?.highPrice) }} /
                {{ formatUnitPrice(price?.lowPrice) }}
              </span>
            </div>
            <div class="row">
              <span class="row-label">수익률</span>
              <span class="row-value">{{ formatRate(price?.earningRate) }}</span>
            </div>
            <div class="row">
              <span class="row-label">누적 거래량</span>
              <span class="row-value">{{ formatQuantity(price?.accumulatedVolume) }}</span>
            </div>
          </div>
        </section>

        <!-- 발행정보 -->
        <section class="card">
          <h3 class="card-title">발행 정보</h3>
          <p v-if="issueInfo?.notice" class="card-notice">{{ issueInfo.notice }}</p>

          <div class="rows">
            <div class="row">
              <span class="row-label">만기일</span>
              <span class="row-value">{{ formatKisDate(issueInfo?.maturityDate || lot.maturityDate) }}</span>
            </div>
            <div class="row">
              <span class="row-label">표면금리</span>
              <span class="row-value">{{ formatRate(issueInfo?.couponRate ?? bondInfo?.couponRate) }}</span>
            </div>
            <div class="row">
              <span class="row-label">액면가</span>
              <span class="row-value">{{ formatAmount(issueInfo?.faceValue) }}</span>
            </div>
            <div class="row">
              <span class="row-label">호가 단위</span>
              <span class="row-value">{{ formatUnitPrice(issueInfo?.quoteUnitPrice) }}</span>
            </div>
            <div class="row">
              <span class="row-label">다음 이자지급일</span>
              <span class="row-value">{{ formatKisDate(issueInfo?.nextInterestPaymentDate) }}</span>
            </div>
            <div class="row">
              <span class="row-label">분리과세 가능</span>
              <span class="row-value">{{ textOrDash(issueInfo?.separateTaxationPossible) }}</span>
            </div>
          </div>

          <!-- 신용등급은 평가사마다 다르다. 하나로 뭉개면 어느 평가사 등급인지 알 수 없다. -->
          <h4 class="sub-title">신용등급 (평가사별)</h4>
          <div class="grade-grid">
            <div class="grade-item">
              <span class="grade-agency">한국신용평가</span>
              <span class="grade-value">{{ textOrDash(issueInfo?.kisCreditGrade) }}</span>
            </div>
            <div class="grade-item">
              <span class="grade-agency">한국채권평가</span>
              <span class="grade-value">{{ textOrDash(issueInfo?.kbpCreditGrade) }}</span>
            </div>
            <div class="grade-item">
              <span class="grade-agency">NICE</span>
              <span class="grade-value">{{ textOrDash(issueInfo?.niceCreditGrade) }}</span>
            </div>
            <div class="grade-item">
              <span class="grade-agency">에프앤자산평가</span>
              <span class="grade-value">{{ textOrDash(issueInfo?.fnpCreditGrade) }}</span>
            </div>
          </div>

          <p v-if="issueInfo?.investmentCaution === 'Y' || bondInfo?.defaultOccurred === 'Y'" class="warn-box">
            투자유의 상품이거나 부도가 발생한 종목입니다. 주문 전 반드시 확인하세요.
          </p>
        </section>

        <!-- 호가 -->
        <section class="card">
          <h3 class="card-title">호가</h3>
          <p v-if="orderbook?.notice" class="card-notice">{{ orderbook.notice }}</p>

          <div v-if="hasOrderbook" class="orderbook">
            <div class="orderbook-col">
              <span class="orderbook-head sell">매도</span>
              <div v-for="level in askLevels" :key="`ask-${level.level}`" class="orderbook-row">
                <span class="orderbook-price sell">{{ formatUnitPrice(level.price) }}</span>
                <span class="orderbook-qty">{{ formatQuantity(level.remainQty) }}</span>
              </div>
            </div>
            <div class="orderbook-col">
              <span class="orderbook-head buy">매수</span>
              <div v-for="level in bidLevels" :key="`bid-${level.level}`" class="orderbook-row">
                <span class="orderbook-price buy">{{ formatUnitPrice(level.price) }}</span>
                <span class="orderbook-qty">{{ formatQuantity(level.remainQty) }}</span>
              </div>
            </div>
          </div>

          <!-- 장내채권은 유동성이 낮아 호가가 비는 것이 정상이다. 오류가 아니다. -->
          <p v-else class="state-box small">현재 호가가 없습니다 (장내채권은 호가가 비는 경우가 흔합니다)</p>
        </section>
      </template>
    </div>

    <!-- 매도 진입 -->
    <div class="footer-actions">
      <button type="button" class="sell-button" :disabled="!hasLot" @click="goToSell">
        매도
      </button>
      <p v-if="!hasLot" class="footer-hint">보유 로트 정보가 없어 매도할 수 없습니다</p>
    </div>
  </div>
</template>

<style scoped>
.bond-detail-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: 96px;
}

.bond-detail-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hero {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hero-name {
  font-size: 20px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  word-break: break-all;
}

.hero-code {
  font-size: 12px;
  color: var(--color-text-secondary);
  letter-spacing: 0.04em;
}

.hero-class {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.card {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 20px;
  padding: 20px;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

.card-title {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 4px;
}

.card-caption {
  font-size: 11px;
  color: var(--color-text-tertiary);
  margin-bottom: 12px;
}

.card-notice {
  font-size: 12px;
  color: #F59E0B;
  margin-bottom: 12px;
}

.sub-title {
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  margin: 16px 0 8px;
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.row-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.row-value {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  text-align: right;
  word-break: break-all;
}

.price-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 8px 0 4px;
}

.price-value {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.price-change {
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
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

.grade-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.grade-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.grade-agency {
  font-size: 11px;
  color: var(--color-text-secondary);
}

.grade-value {
  font-size: 15px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.warn-box {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
  font-size: 12px;
  font-weight: var(--font-weight-medium);
}

.orderbook {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 8px;
}

.orderbook-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.orderbook-head {
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
}

.orderbook-head.sell {
  color: #EF4444;
}

.orderbook-head.buy {
  color: #3B82F6;
}

.orderbook-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  background: var(--canvas-hairline-faint);
  border-radius: 8px;
}

.orderbook-price {
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
}

.orderbook-price.sell {
  color: #EF4444;
}

.orderbook-price.buy {
  color: #3B82F6;
}

.orderbook-qty {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.state-box {
  padding: 16px;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.state-box.small {
  font-size: 12px;
  padding: 12px;
  margin-top: 8px;
}

.footer-actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px var(--spacing-lg) 20px;
  background: var(--canvas-gradient-end);
  border-top: 1px solid var(--canvas-hairline);
}

.sell-button {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 14px;
  background: #3B82F6;
  color: #FFFFFF;
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.sell-button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.footer-hint {
  margin-top: 6px;
  font-size: 11px;
  color: var(--color-text-tertiary);
  text-align: center;
}
</style>

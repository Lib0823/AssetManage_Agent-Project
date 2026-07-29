<script setup>
import { computed } from 'vue'

const props = defineProps({
  stock: {
    type: Object,
    required: true
  },
  showActions: {
    type: Boolean,
    default: true
  }
})

defineEmits(['news', 'trade', 'info'])

const profitClass = computed(() => {
  if (props.stock.profit > 0) return 'profit-positive'
  if (props.stock.profit < 0) return 'profit-negative'
  return ''
})

const formatNumber = (num) => {
  return new Intl.NumberFormat('ko-KR').format(num)
}
</script>

<template>
  <div class="stock-card">
    <div class="card-header">
      <div class="stock-info">
        <h3 class="stock-name">{{ stock.name }} ({{ stock.symbol }})</h3>
      </div>
      <div class="stock-logo">
        <img v-if="stock.logo" :src="stock.logo" :alt="stock.name" />
        <div v-else class="logo-placeholder">
          {{ stock.symbol?.charAt(0) }}
        </div>
      </div>
    </div>

    <div class="card-body">
      <div class="stat-row">
        <div class="stat">
          <span class="stat-label">현재가</span>
          <span class="stat-value text-danger">{{ formatNumber(stock.currentPrice) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">매입금</span>
          <span class="stat-value text-danger">{{ formatNumber(stock.purchasePrice) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">평가손익</span>
          <span :class="['stat-value', profitClass]">{{ formatNumber(stock.profit) }}</span>
        </div>
      </div>
      <div class="stat-row">
        <div class="stat">
          <span class="stat-label">평균 단가</span>
          <span class="stat-value">{{ formatNumber(stock.avgPrice) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">보유수량</span>
          <span class="stat-value text-danger">{{ formatNumber(stock.quantity) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">손익률</span>
          <span :class="['stat-value', profitClass]">{{ stock.profitPercent }}%</span>
        </div>
      </div>
    </div>

    <div v-if="showActions" class="card-actions">
      <button class="action-btn" @click="$emit('news', stock)">뉴스</button>
      <button class="action-btn" @click="$emit('trade', stock)">매매</button>
      <button class="action-btn" @click="$emit('info', stock)">기업 정보</button>
    </div>
  </div>
</template>

<style scoped>
.stock-card {
  background: var(--color-bg-card);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-card);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
}

.stock-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.stock-logo {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg-tertiary);
}

.stock-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.logo-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-secondary);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.stat-row {
  display: flex;
  justify-content: space-between;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.stat-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.profit-positive {
  color: var(--color-stock-up) !important;
}

.profit-negative {
  color: var(--color-stock-down) !important;
}

.text-danger {
  color: var(--color-stock-up);
}

.card-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border-light);
}

.action-btn {
  flex: 1;
  padding: var(--spacing-sm) var(--spacing-md);
  background: transparent;
  border: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s;
}

.action-btn:hover {
  color: var(--color-primary);
}
</style>

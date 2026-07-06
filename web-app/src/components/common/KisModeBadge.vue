<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'

/**
 * KIS 계좌 모드(실전/모의) 배지.
 *
 * 실전투자는 실제 자금이 오가므로 사용자가 현재 모드를 항상 인지하도록 헤더에 상시 표시한다.
 * 모드는 authStore.accountMode('REAL'|'MOCK'|null)에서 읽는다(로그인 시 조회·프로필 저장 시 갱신).
 * 모드를 아직 모르면(null) 아무것도 렌더하지 않는다.
 */
const authStore = useAuthStore()
const { accountMode } = storeToRefs(authStore)

const isReal = computed(() => accountMode.value === 'REAL')
const label = computed(() => (isReal.value ? '실전투자' : '모의투자'))
</script>

<template>
  <span
    v-if="accountMode"
    :class="['kis-mode-badge', isReal ? 'real' : 'mock']"
    :title="isReal ? '실전투자 계좌 — 실제 자금으로 주문이 체결됩니다' : '모의투자 계좌'"
  >
    <span class="dot"></span>
    {{ label }}
  </span>
</template>

<style scoped>
.kis-mode-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: var(--radius-full);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  white-space: nowrap;
  border: 1px solid transparent;
}

.kis-mode-badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

/* 실전: 경고색(빨강)으로 실제 매매임을 강조 */
.kis-mode-badge.real {
  color: var(--color-danger);
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.4);
}

.kis-mode-badge.real .dot {
  background: var(--color-danger);
}

/* 모의: 중립색 */
.kis-mode-badge.mock {
  color: var(--color-text-secondary);
  background: rgba(107, 114, 128, 0.15);
  border-color: rgba(107, 114, 128, 0.35);
}

.kis-mode-badge.mock .dot {
  background: var(--color-text-tertiary);
}
</style>

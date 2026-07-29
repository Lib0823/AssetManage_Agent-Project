<script setup>
import { KIS_MAINTENANCE_TITLE, KIS_MAINTENANCE_MESSAGE } from '@/utils/kisStatus'

/**
 * KIS 시세/잔고 연동 불가 시 표시하는 안내(주의) 컴포넌트.
 *
 * KIS 점검 중이거나 일시 연동 오류일 때, 빈 화면이나 mock/가짜 데이터 대신
 * 이 컴포넌트를 렌더해 사용자 혼란을 막는다.
 *
 * variant:
 *  - 'card'   : 섹션 자리를 대체하는 박스 (기본)
 *  - 'banner' : 섹션 상단/영역 위에 얹는 가로 배너
 *  - 'inline' : 표의 셀/행 안에 들어가는 작은 배지
 */
defineProps({
  variant: {
    type: String,
    default: 'card',
    validator: (v) => ['card', 'banner', 'inline'].includes(v)
  },
  title: {
    type: String,
    default: KIS_MAINTENANCE_TITLE
  },
  message: {
    type: String,
    default: KIS_MAINTENANCE_MESSAGE
  }
})
</script>

<template>
  <!-- 인라인 배지: 표 셀/행 안에서 값 대신 표시 -->
  <span v-if="variant === 'inline'" class="kis-notice-inline" role="status">
    <van-icon name="warning-o" class="kis-notice-inline-icon" />
    <span>점검중</span>
  </span>

  <!-- 배너: 섹션 위에 얹는 가로 안내 -->
  <div v-else-if="variant === 'banner'" class="kis-notice-banner" role="status">
    <van-icon name="warning-o" class="kis-notice-banner-icon" />
    <span class="kis-notice-banner-text">{{ message }}</span>
  </div>

  <!-- 카드: 섹션 자리를 대체하는 박스 -->
  <div v-else class="kis-notice-card" role="status">
    <van-icon name="warning-o" class="kis-notice-card-icon" />
    <div class="kis-notice-card-body">
      <span class="kis-notice-card-title">{{ title }}</span>
      <span class="kis-notice-card-message">{{ message }}</span>
    </div>
  </div>
</template>

<style scoped>
/* ===== card ===== */
.kis-notice-card {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg);
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.4);
  border-radius: var(--radius-lg);
}

.kis-notice-card-icon {
  color: var(--color-warning);
  font-size: 22px;
  flex-shrink: 0;
  margin-top: 1px;
}

.kis-notice-card-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kis-notice-card-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.kis-notice-card-message {
  font-size: var(--font-size-xs);
  line-height: 1.5;
  color: var(--color-text-secondary);
}

/* ===== banner ===== */
.kis-notice-banner {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: var(--radius-md);
}

.kis-notice-banner-icon {
  color: var(--color-warning);
  font-size: 16px;
  flex-shrink: 0;
}

.kis-notice-banner-text {
  font-size: var(--font-size-xs);
  line-height: 1.4;
  color: var(--color-text-secondary);
}

/* ===== inline ===== */
.kis-notice-inline {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  background: rgba(245, 158, 11, 0.15);
  color: var(--color-warning);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.kis-notice-inline-icon {
  font-size: 12px;
}
</style>

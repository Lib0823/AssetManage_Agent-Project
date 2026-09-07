<script setup>
/**
 * "이 탭에서는 지원하지 않는 기능" 안내.
 *
 * 자산 종류 탭(주식/채권/코인)은 6개 화면이 공유하는데, 종류마다 지원 범위가 다르다.
 * 예를 들어 채권은 KIS 에 검색 API 가 없어 검색·관심종목·뉴스가 불가능하다.
 * 분기가 없으면 이 화면들은 **에러 없이 주식 데이터를 채권인 것처럼** 보여준다 —
 * 조용한 오류라 사용자가 알아채지 못한다. 그 자리를 이 컴포넌트로 대체한다.
 *
 * `KisMaintenanceNotice` 와 역할이 다르다: 저쪽은 "일시적 장애라 잠시 후 되는" 상태이고,
 * 이쪽은 "구조적으로 지원하지 않는" 상태다. 문구·재시도 유도가 달라야 해서 분리했다.
 */
defineProps({
  title: {
    type: String,
    required: true
  },
  message: {
    type: String,
    default: ''
  },
  /** 대안 경로 버튼 라벨 (없으면 버튼을 그리지 않는다) */
  actionLabel: {
    type: String,
    default: ''
  }
})

defineEmits(['action'])
</script>

<template>
  <div class="unsupported-notice" role="status">
    <van-icon name="info-o" class="unsupported-icon" />
    <p class="unsupported-title">{{ title }}</p>
    <p v-if="message" class="unsupported-message">{{ message }}</p>
    <button
      v-if="actionLabel"
      type="button"
      class="unsupported-action"
      @click="$emit('action')"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>

<style scoped>
.unsupported-notice {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px var(--spacing-lg);
  margin: var(--spacing-lg);
  background: var(--canvas-hairline-faint);
  border-radius: 16px;
  text-align: center;
}

.unsupported-icon {
  font-size: 28px;
  color: var(--color-text-tertiary);
}

.unsupported-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.unsupported-message {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.5;
}

.unsupported-action {
  margin-top: 8px;
  padding: 10px 20px;
  border: none;
  border-radius: 12px;
  background: #F59E0B;
  color: var(--color-text-inverse);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
}
</style>

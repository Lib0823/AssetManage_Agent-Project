<script setup>
import { useRouter } from 'vue-router'

defineProps({
  title: {
    type: String,
    default: ''
  },
  showBack: {
    type: Boolean,
    default: false
  },
  showIcon: {
    type: Boolean,
    default: false
  },
  icon: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['back'])

const router = useRouter()

const goBack = () => {
  emit('back')
  router.back()
}
</script>

<template>
  <header class="app-header safe-area-top">
    <div class="header-content">
      <!-- Back Button -->
      <button v-if="showBack" class="back-btn" @click="goBack">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
          <path d="M15 18L9 12L15 6" stroke="var(--color-primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span class="back-text">뒤로가기</span>
      </button>
      <div v-else class="spacer"></div>

      <!-- Title -->
      <div class="title-wrapper">
        <span v-if="showIcon && icon" class="title-icon">
          <!-- Home Icon -->
          <svg v-if="icon === 'home'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M3 12L12 3L21 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M5 10V19C5 19.5523 5.44772 20 6 20H18C18.5523 20 19 19.5523 19 19V10" stroke="currentColor" stroke-width="2"/>
          </svg>
          <!-- Assets Icon -->
          <svg v-else-if="icon === 'assets'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/>
            <path d="M12 7V12L15 15" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <!-- Bot Icon -->
          <svg v-else-if="icon === 'bot'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <rect x="3" y="6" width="18" height="12" rx="2" stroke="currentColor" stroke-width="2"/>
            <circle cx="8" cy="12" r="2" fill="currentColor"/>
            <circle cx="16" cy="12" r="2" fill="currentColor"/>
          </svg>
          <!-- Search Icon -->
          <svg v-else-if="icon === 'search'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2"/>
            <path d="M21 21L16.5 16.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <!-- News Icon -->
          <svg v-else-if="icon === 'news'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2"/>
            <path d="M7 7H17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M7 11H17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M7 15H12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <!-- Profile Icon -->
          <svg v-else-if="icon === 'profile'" width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="2"/>
            <path d="M4 20C4 16 8 14 12 14C16 14 20 16 20 20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
        </span>
        <h1 class="title">{{ title }}</h1>
      </div>

      <!-- Right Slot -->
      <div class="right-slot">
        <slot name="right"></slot>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  background: var(--color-bg-primary);
  z-index: 50;
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--nav-height);
  padding: 0 var(--spacing-lg);
}

.back-btn {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  background: none;
  border: none;
  cursor: pointer;
  padding: var(--spacing-sm);
  margin-left: calc(var(--spacing-sm) * -1);
}

.back-text {
  font-size: var(--font-size-base);
  color: var(--color-primary);
}

.spacer {
  width: 80px;
}

.title-wrapper {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.title-icon {
  display: flex;
  align-items: center;
  color: var(--color-text-primary);
}

.title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.right-slot {
  min-width: 80px;
  display: flex;
  justify-content: flex-end;
}
</style>

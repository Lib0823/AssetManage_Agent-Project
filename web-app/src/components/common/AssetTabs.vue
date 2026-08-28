<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({ main: 'stocks', sub: 'domestic' })
  },
  // 탭 목록을 외부에서 받을 수 있도록 추가
  tabs: {
    type: Array,
    default: () => [
      { key: 'cash', label: '현금', disabled: false },
      { key: 'stocks', label: '주식', disabled: false },
      // 채권은 보유 조회 + 매도만 지원한다. 이 탭을 쓰는 화면(자산 상세/뉴스 등)은
      // 채권 분기를 반드시 갖고 있어야 한다 — 없으면 주식 데이터를 채권인 것처럼 보여준다.
      { key: 'bonds', label: '채권', disabled: false },
      { key: 'coins', label: '코인', disabled: true }
    ]
  },
  showSubTabs: {
    type: Boolean,
    default: true
  }
})

const emit = defineEmits(['update:modelValue'])

const subTabs = [
  { key: 'domestic', label: '국내' },
  { key: 'overseas', label: '해외' }
]

const activeMain = ref(props.modelValue.main)
const activeSub = ref(props.modelValue.sub)

const selectMain = (key, disabled) => {
  if (disabled) return
  activeMain.value = key
  emit('update:modelValue', { main: key, sub: activeSub.value })
}

const selectSub = (key) => {
  activeSub.value = key
  emit('update:modelValue', { main: activeMain.value, sub: key })
}

watch(() => props.modelValue, (newVal) => {
  activeMain.value = newVal.main
  activeSub.value = newVal.sub
}, { deep: true })
</script>

<template>
  <div class="asset-tabs">
    <!-- Main Tabs -->
    <div class="main-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="['tab-btn', { active: activeMain === tab.key, disabled: tab.disabled }]"
        @click="selectMain(tab.key, tab.disabled)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Sub Tabs -->
    <div v-if="showSubTabs" class="sub-tabs">
      <button
        v-for="tab in subTabs"
        :key="tab.key"
        :class="['sub-tab-btn', { active: activeSub === tab.key }]"
        @click="selectSub(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Progress Bar -->
    <div v-if="showSubTabs" class="progress-bar">
      <div
        class="progress-fill"
        :style="{ width: activeSub === 'domestic' ? '50%' : '100%', left: activeSub === 'domestic' ? '0' : '50%' }"
      ></div>
    </div>
  </div>
</template>

<style scoped>
.asset-tabs {
  padding: var(--spacing-md) var(--spacing-lg);
}

.main-tabs {
  display: flex;
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  padding: 4px;
  gap: 4px;
}

.tab-btn {
  flex: 1;
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  background: transparent;
  border-radius: var(--radius-full);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.tab-btn.active {
  background: #F59E0B;
  color: var(--color-text-inverse);
}

.tab-btn.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.tab-btn.disabled:hover {
  background: transparent;
}

.sub-tabs {
  display: flex;
  justify-content: space-between;
  margin-top: var(--spacing-sm);
  padding: 0 var(--spacing-lg);
}

.sub-tab-btn {
  background: none;
  border: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: var(--spacing-xs) var(--spacing-sm);
}

.sub-tab-btn.active {
  color: #F59E0B;
  font-weight: var(--font-weight-medium);
}

.progress-bar {
  height: 3px;
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  margin-top: var(--spacing-xs);
  position: relative;
  overflow: hidden;
}

.progress-fill {
  position: absolute;
  height: 100%;
  width: 50%;
  background: #F59E0B;
  border-radius: var(--radius-full);
  transition: all 0.3s ease;
}
</style>

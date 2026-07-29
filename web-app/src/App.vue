<script setup>
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { showNotify } from 'vant'
import { useAuthStore } from '@/stores/auth'
import { useRealtimeStore } from '@/stores/realtime'
import { userApi } from '@/services/api'
import { logger } from '@/utils/logger'
import BottomNav from './components/common/BottomNav.vue'

const route = useRoute()
const authStore = useAuthStore()
const realtimeStore = useRealtimeStore()

const { accessToken } = storeToRefs(authStore)

const showBottomNav = computed(() => route.meta.showBottomNav)

// 체결통보 전역 구독: 계좌 단위 단일 스트림이라 App에서 1회만 구독한다.
// 인증 상태(accessToken)에 따라 구독/해제하며, 해제 함수를 보관해 중복 구독을 막는다.
let unsubscribeFills = null

// 실시간 소켓은 실계좌(REAL) 모드에서만 사용한다. 계좌 모드를 조회해 realtime 을 켜고/끈다.
// (모의/미등록이면 연결 자체를 시도하지 않아 콘솔 WebSocket 에러가 발생하지 않는다.)
async function applyRealtimeForAccountMode() {
  try {
    const res = await userApi.getKisAccount()
    const mode = res?.data?.accountMode || 'MOCK'
    authStore.setAccountMode(mode)
    realtimeStore.setEnabled(mode === 'REAL')
  } catch (error) {
    // KIS 계좌 미등록/조회 실패 → 안전하게 비활성(모의로 간주). 배지 모드는 기존 값 유지.
    realtimeStore.setEnabled(false)
    logger.debug('KIS 계좌 모드 조회 실패 → 실시간 비활성:', error)
  }
}

function startFillsSubscription() {
  if (unsubscribeFills) return
  unsubscribeFills = realtimeStore.subscribeFills((fill) => {
    if (!fill || !fill.isFill) return
    const sideLabel = fill.side === 'buy' ? '매수' : '매도'
    showNotify({
      type: 'success',
      message: `${sideLabel} 체결: ${fill.symbol} ${fill.qty}주 @ ${fill.price}`
    })
  })
}

function stopFillsSubscription() {
  if (unsubscribeFills) {
    unsubscribeFills()
    unsubscribeFills = null
  }
}

// Auto-login: Restore session from localStorage on app start
onMounted(() => {
  authStore.loadAuthDataFromStorage()
  if (authStore.isAuthenticated()) {
    startFillsSubscription()
    applyRealtimeForAccountMode()
  }
})

// 로그인/로그아웃 전환 처리.
watch(accessToken, (token) => {
  if (token) {
    startFillsSubscription()
    applyRealtimeForAccountMode()
  } else {
    stopFillsSubscription()
    realtimeStore.setEnabled(false)
  }
})

onBeforeUnmount(() => {
  stopFillsSubscription()
})
</script>

<template>
  <div class="app-container">
    <RouterView />
    <BottomNav v-if="showBottomNav" />
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  position: relative;
}
</style>

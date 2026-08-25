<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { userApi } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import toast from '@/utils/toast'
import {
  applyTheme,
  normalizeAssetOrder,
  readUiSettings,
  restoreStoredTheme,
  writeUiSettings
} from '@/utils/uiSettings'

const router = useRouter()
const authStore = useAuthStore()

const settings = ref({
  darkMode: false,
  autoLogin: false,
  notifications: {
    stocks: { news: true, trading: true },
    coins: { news: true, trading: true }
  }
})
const loading = ref(false)
const errorMessage = ref('')

const assetItems = ref(normalizeAssetOrder(readUiSettings().assetOrder))

// 저장하지 않고 화면을 벗어나면 테마를 저장값으로 되돌린다 (미리보기만 되도록)
const savedInThisVisit = ref(false)

// 토글 즉시 화면에 반영 (저장은 handleSave 에서)
watch(() => settings.value.darkMode, (darkMode) => applyTheme(darkMode))

const draggedIndex = ref(null)
const dragOverIndex = ref(null)

const handleDragStart = (index) => {
  draggedIndex.value = index
}

const handleDragOver = (index) => {
  dragOverIndex.value = index
}

const handleDragEnd = () => {
  if (draggedIndex.value !== null && dragOverIndex.value !== null && draggedIndex.value !== dragOverIndex.value) {
    const items = [...assetItems.value]
    const [movedItem] = items.splice(draggedIndex.value, 1)
    items.splice(dragOverIndex.value, 0, movedItem)
    assetItems.value = items
  }
  draggedIndex.value = null
  dragOverIndex.value = null
}

const loadSettings = async () => {
  try {
    loading.value = true
    const response = await userApi.getSettings()
    const settingsData = response?.data
    if (settingsData) {
      // darkMode/autoLogin/assetOrder 는 localStorage(uiSettings)가 권위 저장소이고
      // 서버 값은 아직 로컬에 값이 없을 때의 시드로만 쓴다 (utils/uiSettings.js 참고).
      const stored = readUiSettings()
      settings.value = {
        darkMode: stored.darkMode ?? settingsData.darkMode ?? false,
        autoLogin: stored.autoLogin ?? settingsData.autoLogin ?? false,
        notifications: {
          stocks: {
            news: settingsData.notifications?.stocks?.news ?? true,
            trading: settingsData.notifications?.stocks?.trading ?? true
          },
          coins: {
            news: settingsData.notifications?.coins?.news ?? true,
            trading: settingsData.notifications?.coins?.trading ?? true
          }
        }
      }
      assetItems.value = normalizeAssetOrder(stored.assetOrder ?? settingsData.assetOrder)

      // 화면 미리보기용으로만 테마를 적용한다. localStorage에는 쓰지 않는다 —
      // 여기서 writeUiSettings까지 하면, 저장값이 없는 OS-다크 사용자가 화면에
      // 들어오기만 해도 darkMode:false가 영구 기록되어 OS 설정을 덮어써 버린다.
      // 실제 저장은 handleSave에서만 한다. 저장 없이 나가면 onBeforeUnmount의
      // restoreStoredTheme()이 (아직 건드리지 않은) 원래 저장값으로 되돌린다.
      applyTheme(settings.value.darkMode)
    }
  } catch (error) {
    console.error('Failed to load settings:', error)
    // 서버 조회 실패 시에도 로컬 저장값으로 화면을 채운다 (graceful degrade)
    const stored = readUiSettings()
    settings.value = {
      ...settings.value,
      darkMode: stored.darkMode ?? settings.value.darkMode,
      autoLogin: stored.autoLogin ?? settings.value.autoLogin
    }
    assetItems.value = normalizeAssetOrder(stored.assetOrder)
    errorMessage.value = '설정을 불러오는데 실패했습니다'
    toast.error('설정을 불러올 수 없습니다')
  } finally {
    loading.value = false
  }
}

const handleSave = async () => {
  try {
    loading.value = true
    errorMessage.value = ''

    // Save settings to API
    await userApi.updateSettings({
      darkMode: settings.value.darkMode,
      autoLogin: settings.value.autoLogin,
      notifications: settings.value.notifications,
      assetOrder: assetItems.value
    })

    // 실제로 화면에 적용되는 값들은 localStorage 미러에 함께 저장한다.
    // (부팅 시 테마 적용 · LoginView 자동 로그인 · AssetsView 섹션 순서가 이 값을 읽는다)
    writeUiSettings({
      darkMode: settings.value.darkMode,
      autoLogin: settings.value.autoLogin,
      assetOrder: assetItems.value
    })
    applyTheme(settings.value.darkMode)
    savedInThisVisit.value = true

    toast.success('설정이 저장되었습니다')
    router.back()
  } catch (error) {
    console.error('Failed to save settings:', error)
    errorMessage.value = error.response?.data?.message || '설정 저장에 실패했습니다'
    toast.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

const handleWithdraw = async () => {
  if (!confirm('정말 회원 탈퇴하시겠습니까?\n\n모든 데이터가 삭제되며 복구할 수 없습니다.')) {
    return
  }

  try {
    loading.value = true
    await userApi.deleteAccount()

    // 정규 로그아웃과 동일하게 인증 키만 지운다 — localStorage.clear()는 uiSettings까지
    // 날리고 sessionStorage(자동 로그인 OFF일 때의 토큰 저장소)는 그대로 두는 문제가 있었다.
    authStore.clearAuthData()

    toast.success('회원 탈퇴가 완료되었습니다')
    router.push('/welcome')
  } catch (error) {
    console.error('Account deletion failed:', error)
    toast.error(error.response?.data?.message || '회원 탈퇴에 실패했습니다')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadSettings()
})

onBeforeUnmount(() => {
  // 저장하지 않고 나갔다면 토글 미리보기를 취소하고 저장된 테마로 복원
  if (!savedInThisVisit.value) {
    restoreStoredTheme()
  }
})
</script>

<template>
  <div class="settings-screen">
    <AppHeader title="설정" showBack />

    <div class="content">
      <!-- Asset Order -->
      <section class="section card">
        <h3 class="section-title">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M3 6H21M3 12H21M3 18H21" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          관심 자산 순위
        </h3>
        <p class="section-description">드래그하여 순서를 변경할 수 있습니다</p>
        <div class="asset-order-list">
          <div
            v-for="(item, index) in assetItems"
            :key="item.key"
            :class="['asset-order-item', {
              'dragging': draggedIndex === index,
              'drag-over': dragOverIndex === index
            }]"
            draggable="true"
            @dragstart="handleDragStart(index)"
            @dragover.prevent="handleDragOver(index)"
            @dragend="handleDragEnd"
          >
            <div class="drag-handle">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M9 5H9.01M9 12H9.01M9 19H9.01M15 5H15.01M15 12H15.01M15 19H15.01" stroke="currentColor" stroke-width="3" stroke-linecap="round"/>
              </svg>
            </div>
            <span class="asset-icon">{{ item.icon }}</span>
            <span class="asset-label">{{ item.label }}</span>
            <span class="asset-rank">{{ index + 1 }}</span>
          </div>
        </div>
      </section>

      <!-- General Settings -->
      <section class="section card">
        <h3 class="section-title">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z" stroke="currentColor" stroke-width="2"/>
            <path d="M19.4 15C19.2669 15.3016 19.2272 15.6362 19.286 15.9606C19.3448 16.285 19.4995 16.5843 19.73 16.82L19.79 16.88C19.976 17.0657 20.1235 17.2863 20.2241 17.5291C20.3248 17.7719 20.3766 18.0322 20.3766 18.295C20.3766 18.5578 20.3248 18.8181 20.2241 19.0609C20.1235 19.3037 19.976 19.5243 19.79 19.71C19.6043 19.896 19.3837 20.0435 19.1409 20.1441C18.8981 20.2448 18.6378 20.2966 18.375 20.2966C18.1122 20.2966 17.8519 20.2448 17.6091 20.1441C17.3663 20.0435 17.1457 19.896 16.96 19.71L16.9 19.65C16.6643 19.4195 16.365 19.2648 16.0406 19.206C15.7162 19.1472 15.3816 19.1869 15.08 19.32C14.7842 19.4468 14.532 19.6572 14.3543 19.9255C14.1766 20.1938 14.0813 20.5082 14.08 20.83V21C14.08 21.5304 13.8693 22.0391 13.4942 22.4142C13.1191 22.7893 12.6104 23 12.08 23C11.5496 23 11.0409 22.7893 10.6658 22.4142C10.2907 22.0391 10.08 21.5304 10.08 21V20.91C10.0723 20.579 9.96512 20.258 9.77251 19.9887C9.5799 19.7194 9.31074 19.5143 9 19.4C8.69838 19.2669 8.36381 19.2272 8.03941 19.286C7.71502 19.3448 7.41568 19.4995 7.18 19.73L7.12 19.79C6.93425 19.976 6.71368 20.1235 6.47088 20.2241C6.22808 20.3248 5.96783 20.3766 5.705 20.3766C5.44217 20.3766 5.18192 20.3248 4.93912 20.2241C4.69632 20.1235 4.47575 19.976 4.29 19.79C4.10405 19.6043 3.95653 19.3837 3.85588 19.1409C3.75523 18.8981 3.70343 18.6378 3.70343 18.375C3.70343 18.1122 3.75523 17.8519 3.85588 17.6091C3.95653 17.3663 4.10405 17.1457 4.29 16.96L4.35 16.9C4.58054 16.6643 4.73519 16.365 4.794 16.0406C4.85282 15.7162 4.81312 15.3816 4.68 15.08C4.55324 14.7842 4.34276 14.532 4.07447 14.3543C3.80618 14.1766 3.49179 14.0813 3.17 14.08H3C2.46957 14.08 1.96086 13.8693 1.58579 13.4942C1.21071 13.1191 1 12.6104 1 12.08C1 11.5496 1.21071 11.0409 1.58579 10.6658C1.96086 10.2907 2.46957 10.08 3 10.08H3.09C3.42099 10.0723 3.742 9.96512 4.0113 9.77251C4.28059 9.5799 4.48572 9.31074 4.6 9C4.73312 8.69838 4.77282 8.36381 4.714 8.03941C4.65519 7.71502 4.50054 7.41568 4.27 7.18L4.21 7.12C4.02405 6.93425 3.87653 6.71368 3.77588 6.47088C3.67523 6.22808 3.62343 5.96783 3.62343 5.705C3.62343 5.44217 3.67523 5.18192 3.77588 4.93912C3.87653 4.69632 4.02405 4.47575 4.21 4.29C4.39575 4.10405 4.61632 3.95653 4.85912 3.85588C5.10192 3.75523 5.36217 3.70343 5.625 3.70343C5.88783 3.70343 6.14808 3.75523 6.39088 3.85588C6.63368 3.95653 6.85425 4.10405 7.04 4.29L7.1 4.35C7.33568 4.58054 7.63502 4.73519 7.95941 4.794C8.28381 4.85282 8.61838 4.81312 8.92 4.68H9C9.29577 4.55324 9.54802 4.34276 9.72569 4.07447C9.90337 3.80618 9.99872 3.49179 10 3.17V3C10 2.46957 10.2107 1.96086 10.5858 1.58579C10.9609 1.21071 11.4696 1 12 1C12.5304 1 13.0391 1.21071 13.4142 1.58579C13.7893 1.96086 14 2.46957 14 3V3.09C14.0013 3.41179 14.0966 3.72618 14.2743 3.99447C14.452 4.26276 14.7042 4.47324 15 4.6C15.3016 4.73312 15.6362 4.77282 15.9606 4.714C16.285 4.65519 16.5843 4.50054 16.82 4.27L16.88 4.21C17.0657 4.02405 17.2863 3.87653 17.5291 3.77588C17.7719 3.67523 18.0322 3.62343 18.295 3.62343C18.5578 3.62343 18.8181 3.67523 19.0609 3.77588C19.3037 3.87653 19.5243 4.02405 19.71 4.21C19.896 4.39575 20.0435 4.61632 20.1441 4.85912C20.2448 5.10192 20.2966 5.36217 20.2966 5.625C20.2966 5.88783 20.2448 6.14808 20.1441 6.39088C20.0435 6.63368 19.896 6.85425 19.71 7.04L19.65 7.1C19.4195 7.33568 19.2648 7.63502 19.206 7.95941C19.1472 8.28381 19.1869 8.61838 19.32 8.92V9C19.4468 9.29577 19.6572 9.54802 19.9255 9.72569C20.1938 9.90337 20.5082 9.99872 20.83 10H21C21.5304 10 22.0391 10.2107 22.4142 10.5858C22.7893 10.9609 23 11.4696 23 12C23 12.5304 22.7893 13.0391 22.4142 13.4142C22.0391 13.7893 21.5304 14 21 14H20.91C20.5882 14.0013 20.2738 14.0966 20.0055 14.2743C19.7372 14.452 19.5268 14.7042 19.4 15Z" stroke="currentColor" stroke-width="2"/>
          </svg>
          일반 설정
        </h3>

        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-icon-wrapper">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <span class="setting-name">다크 모드</span>
          </div>
          <label class="toggle-wrapper">
            <input type="checkbox" v-model="settings.darkMode" />
            <span class="toggle-slider"></span>
          </label>
        </div>

        <div class="setting-divider"></div>

        <div class="setting-row">
          <div class="setting-label">
            <div class="setting-icon-wrapper">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M12 15V17M6 21H18C19.1046 21 20 20.1046 20 19V13C20 11.8954 19.1046 11 18 11H6C4.89543 11 4 11.8954 4 13V19C4 20.1046 4.89543 21 6 21ZM16 11V7C16 4.79086 14.2091 3 12 3C9.79086 3 8 4.79086 8 7V11H16Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <span class="setting-name">자동 로그인</span>
          </div>
          <label class="toggle-wrapper">
            <input type="checkbox" v-model="settings.autoLogin" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </section>

      <!-- Notifications -->
      <section class="section card">
        <h3 class="section-title">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M18 8C18 6.4087 17.3679 4.88258 16.2426 3.75736C15.1174 2.63214 13.5913 2 12 2C10.4087 2 8.88258 2.63214 7.75736 3.75736C6.63214 4.88258 6 6.4087 6 8C6 15 3 17 3 17H21C21 17 18 15 18 8Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M13.73 21C13.5542 21.3031 13.3019 21.5547 12.9982 21.7295C12.6946 21.9044 12.3504 21.9965 12 21.9965C11.6496 21.9965 11.3054 21.9044 11.0018 21.7295C10.6982 21.5547 10.4458 21.3031 10.27 21" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          알림 설정
        </h3>

        <div class="notification-category">
          <div class="category-header">
            <span class="category-name">주식</span>
          </div>
          <div class="notification-items">
            <div class="notification-item">
              <span class="notification-label">뉴스</span>
              <label class="toggle-wrapper small">
                <input type="checkbox" v-model="settings.notifications.stocks.news" />
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="notification-item">
              <span class="notification-label">매매</span>
              <label class="toggle-wrapper small">
                <input type="checkbox" v-model="settings.notifications.stocks.trading" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>

        <div class="setting-divider"></div>

        <div class="notification-category">
          <div class="category-header">
            <span class="category-name">코인 (추후 지원)</span>
          </div>
          <div class="notification-items">
            <div class="notification-item">
              <span class="notification-label">뉴스</span>
              <label class="toggle-wrapper small">
                <input type="checkbox" v-model="settings.notifications.coins.news" />
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="notification-item">
              <span class="notification-label">매매</span>
              <label class="toggle-wrapper small">
                <input type="checkbox" v-model="settings.notifications.coins.trading" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
        </div>
      </section>

      <!-- Save Button -->
      <button class="btn btn-save" @click="handleSave">
        저장
      </button>

      <!-- Withdraw -->
      <button class="withdraw-btn" @click="handleWithdraw">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
          <path d="M13 7H11V11H7V13H11V17H13V13H17V11H13V7ZM12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2Z" fill="currentColor"/>
          <path d="M7 11H17V13H7V11Z" fill="white"/>
        </svg>
        회원 탈퇴
      </button>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>
  </div>
</template>

<style scoped>
.settings-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.settings-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
}

.section {
  margin-bottom: var(--spacing-lg);
}

.card {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
  border: 1px solid var(--canvas-hairline-soft);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-xs);
  padding-bottom: var(--spacing-sm);
  border-bottom: 1px solid var(--canvas-hairline);
}

.section-title svg {
  color: var(--color-primary);
}

.section-description {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-md);
}

.asset-order-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.asset-order-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--canvas-hairline-soft);
  border: 1px solid var(--canvas-hairline);
  border-radius: var(--radius-lg);
  cursor: move;
  transition: all 0.2s;
  user-select: none;
}

.asset-order-item:hover {
  background: var(--canvas-hairline-soft);
  border-color: rgba(245, 158, 11, 0.3);
  transform: translateX(4px);
}

.asset-order-item.dragging {
  opacity: 0.5;
  transform: scale(0.95);
}

.asset-order-item.drag-over {
  border-color: var(--color-primary);
  background: rgba(245, 158, 11, 0.1);
}

.drag-handle {
  display: flex;
  align-items: center;
  color: var(--color-text-tertiary);
  cursor: grab;
}

.drag-handle:active {
  cursor: grabbing;
}

.asset-icon {
  font-size: var(--font-size-lg);
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(245, 158, 11, 0.1);
  border-radius: var(--radius-md);
}

.asset-label {
  flex: 1;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.asset-rank {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  color: white;
  border-radius: 50%;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
}

.setting-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-xs) 0;
}

.setting-row.vertical {
  flex-direction: column;
  align-items: stretch;
  gap: var(--spacing-sm);
}

.setting-label {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex: 1;
}

.setting-icon-wrapper {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(245, 158, 11, 0.1);
  border-radius: var(--radius-md);
  color: var(--color-primary);
  flex-shrink: 0;
}

.setting-text {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxs);
}

.setting-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.setting-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.setting-input,
.setting-select {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--canvas-hairline-soft);
  border: 1px solid var(--canvas-hairline);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  color: var(--color-text-primary);
  transition: all 0.2s;
}

.setting-input:focus,
.setting-select:focus {
  outline: none;
  border-color: var(--color-primary);
  background: var(--canvas-hairline-soft);
}

.setting-select {
  cursor: pointer;
}

.setting-divider {
  height: 1px;
  background: var(--canvas-hairline-soft);
  margin: var(--spacing-md) 0;
}

.toggle-wrapper {
  position: relative;
  width: 50px;
  height: 28px;
}

.toggle-wrapper.small {
  width: 40px;
  height: 22px;
}

.toggle-wrapper input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  transition: 0.3s;
}

.toggle-slider::before {
  position: absolute;
  content: "";
  height: 22px;
  width: 22px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  border-radius: 50%;
  transition: 0.3s;
}

.toggle-wrapper.small .toggle-slider::before {
  height: 16px;
  width: 16px;
}

.toggle-wrapper input:checked + .toggle-slider {
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
}

.toggle-wrapper input:checked + .toggle-slider::before {
  transform: translateX(22px);
}

.toggle-wrapper.small input:checked + .toggle-slider::before {
  transform: translateX(18px);
}

.notification-category {
  margin-bottom: var(--spacing-sm);
}

.category-header {
  display: flex;
  align-items: center;
  margin-bottom: var(--spacing-sm);
}

.category-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.notification-items {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.notification-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--spacing-xs) var(--spacing-md);
  background: var(--canvas-hairline-faint);
  border-radius: var(--radius-md);
  transition: all 0.2s;
}

.notification-item:hover {
  background: var(--canvas-hairline-soft);
}

.notification-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

.withdraw-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  width: 100%;
  padding: var(--spacing-sm);
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: var(--radius-lg);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: #EF4444;
  cursor: pointer;
  margin-top: var(--spacing-xl);
  transition: all 0.2s;
}

.withdraw-btn:hover {
  background: rgba(239, 68, 68, 0.15);
  border-color: #EF4444;
}

.withdraw-btn:active {
  transform: scale(0.98);
}

.btn-save {
  width: 100%;
  padding: var(--spacing-md);
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  color: var(--color-text-inverse);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.3);
  transition: all 0.2s;
}

.btn-save:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(245, 158, 11, 0.4);
}

.btn-save:active {
  transform: translateY(0);
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}
</style>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { userApi, authApi } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { useAuthStore } from '@/stores/auth'
import toast from '@/utils/toast'

const router = useRouter()
const realtimeStore = useRealtimeStore()
const authStore = useAuthStore()

const user = ref({
  id: null,
  username: '',
  email: '',
  name: '',
  phone: '',
  birthDate: null
})

const kisAccount = ref({
  accountNumber: '',
  appKey: '',
  appSecret: '',
  mode: 'MOCK'
})

// 모드 변경 시 기존 인증 결과 무효화 (도메인이 달라 재인증 필요)
const selectKisMode = (mode) => {
  if (kisAccount.value.mode === mode) return
  kisAccount.value.mode = mode
  validationResult.value = null
}

const showBirthCalendar = ref(false)
const loading = ref(false)
const isValidating = ref(false)
const validationResult = ref(null)

// Load user profile
const loadProfile = async () => {
  try {
    loading.value = true
    const response = await userApi.getProfile()

    // api.js interceptor가 response.data를 반환하므로
    // response 자체가 { success, message, data } 구조입니다
    if (response && response.data) {
      user.value = {
        id: response.data.id,
        username: response.data.username,
        email: response.data.email,
        name: response.data.name,
        phone: response.data.phone,
        birthDate: response.data.birthDate
      }
    }
  } catch (error) {
    console.error('Failed to load profile:', error)
    toast.error('프로필 정보를 불러올 수 없습니다')
  } finally {
    loading.value = false
  }
}

// Load KIS account
const loadKisAccount = async () => {
  try {
    const response = await userApi.getKisAccount()

    if (response && response.data) {
      kisAccount.value = {
        accountNumber: response.data.accountNumber || '',
        appKey: response.data.appKey || '',
        appSecret: response.data.appSecret || '',
        mode: response.data.accountMode || 'MOCK'
      }
      // 헤더 배지가 최신 모드를 반영하도록 동기화
      authStore.setAccountMode(kisAccount.value.mode)
      validationResult.value = response.data.isVerified ? { valid: true } : null
    }
  } catch (error) {
    console.error('Failed to load KIS account:', error)
    // KIS 계좌가 없는 경우는 에러로 처리하지 않음
    if (error.response?.status !== 404) {
      toast.error('KIS 계좌 정보를 불러올 수 없습니다')
    }
  }
}

const goToSettings = () => {
  router.push('/settings')
}

const goToResetPassword = () => {
  router.push('/reset-password')
}

const handleLogout = () => {
  localStorage.clear()
  router.push('/welcome')
}

const handleValidateKis = async () => {
  // 입력값 검증
  if (!kisAccount.value.accountNumber || !kisAccount.value.appKey || !kisAccount.value.appSecret) {
    toast.warning('KIS 계좌 정보를 모두 입력해주세요')
    return
  }

  try {
    isValidating.value = true
    validationResult.value = null

    const response = await authApi.validateKisAccount({
      accountNumber: kisAccount.value.accountNumber,
      appKey: kisAccount.value.appKey,
      appSecret: kisAccount.value.appSecret,
      mode: kisAccount.value.mode
    })

    validationResult.value = response.data
  } catch (error) {
    console.error('KIS validation error:', error)

    // 에러 메시지 처리
    if (error.response?.data?.data?.message) {
      validationResult.value = error.response.data.data
    } else if (error.response?.data?.message) {
      validationResult.value = { valid: false, message: error.response.data.message }
    } else {
      validationResult.value = { valid: false, message: 'KIS 계정 검증 중 오류 발생' }
    }
  } finally {
    isValidating.value = false
  }
}

const handleSave = async () => {
  try {
    // Validate required fields
    if (!user.value.name || !user.value.email) {
      toast.warning('이름과 이메일은 필수입니다')
      return
    }

    // Validate email format
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!emailRegex.test(user.value.email)) {
      toast.warning('올바른 이메일 형식을 입력해주세요')
      return
    }

    // Validate phone format (if provided)
    if (user.value.phone) {
      const phoneRegex = /^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$/
      if (!phoneRegex.test(user.value.phone)) {
        toast.warning('올바른 전화번호 형식을 입력해주세요')
        return
      }
    }

    // Validate KIS account if provided
    if (kisAccount.value.accountNumber || kisAccount.value.appKey || kisAccount.value.appSecret) {
      if (!kisAccount.value.accountNumber || !kisAccount.value.appKey || !kisAccount.value.appSecret) {
        toast.warning('KIS 계좌 정보를 모두 입력하거나 모두 비워주세요')
        return
      }

      if (!validationResult.value || !validationResult.value.valid) {
        toast.error('KIS 계정 인증을 먼저 완료해주세요')
        return
      }
    }

    loading.value = true

    // Save user profile
    await userApi.updateProfile({
      name: user.value.name,
      email: user.value.email,
      phone: user.value.phone || null,
      birthDate: user.value.birthDate || null
    })

    // Save KIS account if provided
    if (kisAccount.value.accountNumber && kisAccount.value.appKey && kisAccount.value.appSecret) {
      await userApi.updateKisAccount({
        accountNumber: kisAccount.value.accountNumber,
        appKey: kisAccount.value.appKey,
        appSecret: kisAccount.value.appSecret,
        mode: kisAccount.value.mode
      })

      // 저장한 계좌 모드를 반영: 헤더 배지 갱신 + 실시간 소켓 즉시 켜거나(REAL) 끄기(MOCK).
      authStore.setAccountMode(kisAccount.value.mode)
      realtimeStore.setEnabled(kisAccount.value.mode === 'REAL')
    }

    toast.success('정보가 저장되었습니다')
  } catch (error) {
    console.error('Failed to save:', error)
    if (error.response?.data?.message) {
      toast.error(error.response.data.message)
    } else {
      toast.error('저장에 실패했습니다')
    }
  } finally {
    loading.value = false
  }
}

const openBirthPicker = () => {
  showBirthCalendar.value = true
}

const confirmBirth = (value) => {
  // Convert Date to YYYY-MM-DD format
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  user.value.birthDate = `${year}-${month}-${day}`
  showBirthCalendar.value = false
}

const closeBirthPicker = () => {
  showBirthCalendar.value = false
}

const formatBirthDate = (date) => {
  if (!date) return '생년월일 선택'
  const d = new Date(date)
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}.${month}.${day}`
}

onMounted(async () => {
  const token = localStorage.getItem('accessToken')
  if (!token) {
    toast.warning('로그인이 필요합니다')
    return
  }

  await loadProfile()
  await loadKisAccount()
})
</script>

<template>
  <div class="profile-screen dark-canvas">
    <AppHeader title="내 정보" showIcon icon="profile">
      <template #right>
        <button class="icon-btn" @click="handleLogout">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
            <path d="M9 21H5C4.46957 21 3.96086 20.7893 3.58579 20.4142C3.21071 20.0391 3 19.5304 3 19V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M16 17L21 12L16 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M21 12H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <button class="icon-btn" @click="goToSettings">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/>
            <path d="M19.4 15C19.2669 15.3016 19.2272 15.6362 19.286 15.9606C19.3448 16.285 19.4995 16.5843 19.73 16.82L19.79 16.88C19.976 17.0657 20.1235 17.2863 20.2241 17.5291C20.3248 17.7719 20.3766 18.0322 20.3766 18.295C20.3766 18.5578 20.3248 18.8181 20.2241 19.0609C20.1235 19.3037 19.976 19.5243 19.79 19.71C19.6043 19.896 19.3837 20.0435 19.1409 20.1441C18.8981 20.2448 18.6378 20.2966 18.375 20.2966C18.1122 20.2966 17.8519 20.2448 17.6091 20.1441C17.3663 20.0435 17.1457 19.896 16.96 19.71L16.9 19.65C16.6643 19.4195 16.365 19.2648 16.0406 19.206C15.7162 19.1472 15.3816 19.1869 15.08 19.32C14.7842 19.4468 14.532 19.6572 14.3543 19.9255C14.1766 20.1938 14.0813 20.5082 14.08 20.83V21C14.08 21.5304 13.8693 22.0391 13.4942 22.4142C13.1191 22.7893 12.6104 23 12.08 23C11.5496 23 11.0409 22.7893 10.6658 22.4142C10.2907 22.0391 10.08 21.5304 10.08 21V20.91C10.0723 20.579 9.96512 20.258 9.77251 19.9887C9.5799 19.7194 9.31074 19.5143 9 19.4C8.69838 19.2669 8.36381 19.2272 8.03941 19.286C7.71502 19.3448 7.41568 19.4995 7.18 19.73L7.12 19.79C6.93425 19.976 6.71368 20.1235 6.47088 20.2241C6.22808 20.3248 5.96783 20.3766 5.705 20.3766C5.44217 20.3766 5.18192 20.3248 4.93912 20.2241C4.69632 20.1235 4.47575 19.976 4.29 19.79C4.10405 19.6043 3.95653 19.3837 3.85588 19.1409C3.75523 18.8981 3.70343 18.6378 3.70343 18.375C3.70343 18.1122 3.75523 17.8519 3.85588 17.6091C3.95653 17.3663 4.10405 17.1457 4.29 16.96L4.35 16.9C4.58054 16.6643 4.73519 16.365 4.794 16.0406C4.85282 15.7162 4.81312 15.3816 4.68 15.08C4.55324 14.7842 4.34276 14.532 4.07447 14.3543C3.80618 14.1766 3.49179 14.0813 3.17 14.08H3C2.46957 14.08 1.96086 13.8693 1.58579 13.4942C1.21071 13.1191 1 12.6104 1 12.08C1 11.5496 1.21071 11.0409 1.58579 10.6658C1.96086 10.2907 2.46957 10.08 3 10.08H3.09C3.42099 10.0723 3.74197 9.96512 4.01128 9.77251C4.28059 9.5799 4.48572 9.31074 4.6 9C4.73312 8.69838 4.77282 8.36381 4.714 8.03941C4.65519 7.71502 4.50054 7.41568 4.27 7.18L4.21 7.12C4.02405 6.93425 3.87653 6.71368 3.77588 6.47088C3.67523 6.22808 3.62343 5.96783 3.62343 5.705C3.62343 5.44217 3.67523 5.18192 3.77588 4.93912C3.87653 4.69632 4.02405 4.47575 4.21 4.29C4.39575 4.10405 4.61632 3.95653 4.85912 3.85588C5.10192 3.75523 5.36217 3.70343 5.625 3.70343C5.88783 3.70343 6.14808 3.75523 6.39088 3.85588C6.63368 3.95653 6.85425 4.10405 7.04 4.29L7.1 4.35C7.33568 4.58054 7.63502 4.73519 7.95941 4.794C8.28381 4.85282 8.61838 4.81312 8.92 4.68H9C9.29577 4.55324 9.54802 4.34276 9.72569 4.07447C9.90337 3.80618 9.99872 3.49179 10 3.17V3C10 2.46957 10.2107 1.96086 10.5858 1.58579C10.9609 1.21071 11.4696 1 12 1C12.5304 1 13.0391 1.21071 13.4142 1.58579C13.7893 1.96086 14 2.46957 14 3V3.09C14.0013 3.41179 14.0966 3.72618 14.2743 3.99447C14.452 4.26276 14.7042 4.47324 15 4.6C15.3016 4.73312 15.6362 4.77282 15.9606 4.714C16.285 4.65519 16.5843 4.50054 16.82 4.27L16.88 4.21C17.0657 4.02405 17.2863 3.87653 17.5291 3.77588C17.7719 3.67523 18.0322 3.62343 18.295 3.62343C18.5578 3.62343 18.8181 3.67523 19.0609 3.77588C19.3037 3.87653 19.5243 4.02405 19.71 4.21C19.896 4.39575 20.0435 4.61632 20.1441 4.85912C20.2448 5.10192 20.2966 5.36217 20.2966 5.625C20.2966 5.88783 20.2448 6.14808 20.1441 6.39088C20.0435 6.63368 19.896 6.85425 19.71 7.04L19.65 7.1C19.4195 7.33568 19.2648 7.63502 19.206 7.95941C19.1472 8.28381 19.1869 8.61838 19.32 8.92V9C19.4468 9.29577 19.6572 9.54802 19.9255 9.72569C20.1938 9.90337 20.5082 9.99872 20.83 10H21C21.5304 10 22.0391 10.2107 22.4142 10.5858C22.7893 10.9609 23 11.4696 23 12C23 12.5304 22.7893 13.0391 22.4142 13.4142C22.0391 13.7893 21.5304 14 21 14H20.91C20.5882 14.0013 20.2738 14.0966 20.0055 14.2743C19.7372 14.452 19.5268 14.7042 19.4 15V15Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </template>
    </AppHeader>

    <div class="content">
      <!-- Avatar Section -->
      <div class="avatar-section">
        <div class="avatar">
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="12" cy="8" r="4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <path d="M6 21C6 17.134 8.68629 14 12 14C15.3137 14 18 17.134 18 21" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </div>
      </div>

      <!-- User Info Card -->
      <section class="info-card">
        <h3 class="card-title">회원 정보</h3>

        <div class="info-row disabled">
          <span class="info-label">ID</span>
          <input type="text" class="info-input" :value="user.username" disabled />
        </div>

        <div class="info-row disabled">
          <span class="info-label">PW</span>
          <input type="password" class="info-input" value="************" disabled />
          <button class="action-btn" @click="goToResetPassword">재설정</button>
        </div>

        <div class="info-row">
          <span class="info-label">이름 *</span>
          <input
            type="text"
            class="info-input"
            v-model="user.name"
            placeholder="이름 입력"
            :disabled="loading"
          />
        </div>

        <div class="info-row">
          <span class="info-label">이메일 *</span>
          <input
            type="email"
            class="info-input"
            v-model="user.email"
            placeholder="이메일 입력"
            :disabled="loading"
          />
        </div>

        <div class="info-row">
          <span class="info-label">전화번호</span>
          <input
            type="tel"
            class="info-input"
            v-model="user.phone"
            placeholder="010-1234-5678"
            :disabled="loading"
          />
        </div>

        <div class="info-row">
          <span class="info-label">생년월일</span>
          <div class="info-input clickable" @click="openBirthPicker" :class="{ disabled: loading }">
            <span>{{ formatBirthDate(user.birthDate) }}</span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M8 7V3M16 7V3M7 11H17M5 21H19C20.1046 21 21 20.1046 21 19V7C21 5.89543 20.1046 5 19 5H5C3.89543 5 3 5.89543 3 7V19C3 20.1046 3.89543 21 5 21Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>
      </section>

      <!-- KIS Account Card -->
      <section class="info-card">
        <h3 class="card-title">KIS 계좌 정보</h3>

        <div class="info-row">
          <span class="info-label">투자 모드</span>
          <div class="mode-toggle">
            <button
              type="button"
              class="mode-btn"
              :class="{ 'mode-btn-active': kisAccount.mode === 'MOCK' }"
              :disabled="loading"
              @click="selectKisMode('MOCK')"
            >
              모의투자
            </button>
            <button
              type="button"
              class="mode-btn"
              :class="{ 'mode-btn-active': kisAccount.mode === 'REAL' }"
              :disabled="loading"
              @click="selectKisMode('REAL')"
            >
              실전투자
            </button>
          </div>
        </div>

        <div class="info-row">
          <span class="info-label">계좌번호</span>
          <input
            type="text"
            class="info-input"
            v-model="kisAccount.accountNumber"
            placeholder="계좌번호 입력"
            :disabled="loading"
          />
        </div>

        <div class="info-row">
          <span class="info-label">APP Key</span>
          <input
            type="text"
            class="info-input"
            v-model="kisAccount.appKey"
            placeholder="APP Key 입력"
            :disabled="loading"
          />
        </div>

        <div class="info-row">
          <span class="info-label">APP Secret</span>
          <input
            type="password"
            class="info-input"
            v-model="kisAccount.appSecret"
            placeholder="APP Secret 입력"
            :disabled="loading"
          />
        </div>

        <button
          class="btn-validate"
          :class="{ 'btn-validate-success': validationResult?.valid, 'btn-validate-error': validationResult && !validationResult.valid }"
          @click="handleValidateKis"
          :disabled="isValidating"
        >
          {{ isValidating ? '검증 중...' : validationResult?.valid ? '✓ 인증 성공' : validationResult ? '✗ 인증 실패 - 다시 시도' : 'KIS 계정 인증' }}
        </button>

        <p v-if="validationResult && !validationResult.valid" class="validation-error-message">
          {{ validationResult.message }}
        </p>
        <p v-if="!validationResult && (kisAccount.accountNumber || kisAccount.appKey || kisAccount.appSecret)" class="validation-info-message">
          저장 전에 KIS 계정 인증을 완료해주세요
        </p>
      </section>

      <!-- Save Button -->
      <button class="btn btn-save" @click="handleSave" :disabled="loading">
        {{ loading ? '저장 중...' : '저장' }}
      </button>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>

    <!-- Birth Calendar Modal -->
    <van-calendar
      v-model:show="showBirthCalendar"
      :min-date="new Date(1900, 0, 1)"
      :max-date="new Date()"
      :default-date="user.birthDate ? new Date(user.birthDate) : new Date()"
      color="#F59E0B"
      @confirm="confirmBirth"
      @close="closeBirthPicker"
      :style="{ zIndex: 10000 }"
      teleport="body"
    >
      <template #title>
        <div class="calendar-header">
          <button class="calendar-close-btn" @click="closeBirthPicker">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
              <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <span class="calendar-title">생년월일 선택</span>
          <div style="width: 24px;"></div>
        </div>
      </template>
    </van-calendar>
  </div>
</template>

<style scoped>
.profile-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0F172A 0%, #1E293B 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.profile-screen :deep(.app-header) {
  background: #0F172A;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.icon-btn {
  background: none;
  border: none;
  color: var(--color-text-primary);
  cursor: pointer;
  padding: var(--spacing-sm);
  transition: all 0.2s;
}

.icon-btn:hover {
  color: var(--color-primary);
}

.icon-btn:active {
  transform: scale(0.95);
}

.content {
  padding: var(--spacing-lg);
}

.avatar-section {
  display: flex;
  justify-content: center;
  margin-bottom: var(--spacing-xl);
}

.avatar {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.2) 0%, rgba(245, 158, 11, 0.1) 100%);
  border: 2px solid rgba(245, 158, 11, 0.3);
}

.avatar svg {
  width: 56px;
  height: 56px;
  color: var(--color-primary);
}

.info-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-xl);
  margin-bottom: var(--spacing-lg);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.card-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  text-align: center;
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.info-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.info-row:last-of-type {
  border-bottom: none;
}

.info-row.disabled {
  opacity: 0.6;
}

.info-label {
  min-width: 80px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
}

.info-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  transition: all 0.2s;
}

.info-input:focus {
  outline: none;
  border-color: var(--color-primary);
  background: rgba(255, 255, 255, 0.08);
}

.info-input:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.info-input::placeholder {
  color: var(--color-text-tertiary);
}

.info-input.clickable {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
}

.info-input.clickable:not(.disabled):hover {
  border-color: var(--color-primary);
  background: rgba(255, 255, 255, 0.08);
}

.info-input.clickable.disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.info-input.clickable svg {
  color: var(--color-text-secondary);
  flex-shrink: 0;
}

.action-btn {
  padding: var(--spacing-xs) var(--spacing-md);
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: var(--radius-md);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-primary);
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.action-btn:hover {
  background: rgba(245, 158, 11, 0.2);
  border-color: var(--color-primary);
}

.action-btn:active {
  transform: scale(0.98);
}

.btn-save {
  width: 100%;
  padding: var(--spacing-lg);
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  color: var(--color-text-inverse);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.3);
  transition: all 0.2s;
}

.btn-save:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(245, 158, 11, 0.4);
}

.btn-save:active:not(:disabled) {
  transform: translateY(0);
}

.btn-save:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}

/* Calendar Header */
.calendar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-lg);
  background: #1F2937;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.calendar-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.calendar-close-btn {
  background: none;
  border: none;
  color: var(--color-text-secondary);
  cursor: pointer;
  padding: var(--spacing-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-md);
  transition: all 0.2s;
}

.calendar-close-btn:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--color-text-primary);
}

.calendar-close-btn:active {
  transform: scale(0.95);
}

/* Override Vant Calendar styles */
:deep(.van-calendar) {
  background: #1F2937;
  z-index: 10000 !important;
}

:deep(.van-calendar__popup) {
  z-index: 10000 !important;
  background: #1F2937;
}

:deep(.van-overlay) {
  z-index: 9999 !important;
}

:deep(.van-calendar__header) {
  background: #1F2937;
  box-shadow: none;
}

:deep(.van-calendar__header-title) {
  display: none;
}

:deep(.van-calendar__header-subtitle) {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

:deep(.van-calendar__weekdays) {
  background: #1F2937;
}

:deep(.van-calendar__weekday) {
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

:deep(.van-calendar__body) {
  background: #1F2937;
}

:deep(.van-calendar__month-title) {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-bold);
  background: #1F2937;
}

:deep(.van-calendar__day) {
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
}

:deep(.van-calendar__day--disabled) {
  color: var(--color-text-tertiary);
  opacity: 0.4;
}

:deep(.van-calendar__selected-day) {
  background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
  color: white;
  border-radius: var(--radius-md);
}

:deep(.van-calendar__top-info),
:deep(.van-calendar__bottom-info) {
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

/* KIS 모의/실전 모드 토글 */
.mode-toggle {
  display: flex;
  gap: var(--spacing-xs);
  background: var(--color-bg-tertiary);
  border-radius: var(--radius-md);
  padding: 3px;
  flex: 1;
}

.mode-btn {
  flex: 1;
  padding: var(--spacing-sm);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  background: transparent;
  cursor: pointer;
  transition: all 0.2s ease;
}

.mode-btn-active {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.mode-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

/* KIS Account Validation Button */
.btn-validate {
  width: 100%;
  padding: var(--spacing-md);
  background: var(--color-bg-tertiary);
  color: var(--color-text-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  margin-top: var(--spacing-md);
  transition: all 0.2s ease;
}

.btn-validate:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-validate:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-validate-success {
  background: #10B981;
  color: white;
}

.btn-validate-error {
  background: #EF4444;
  color: white;
}

.validation-error-message {
  font-size: var(--font-size-xs);
  color: #EF4444;
  margin-top: var(--spacing-xs);
  text-align: center;
}

.validation-info-message {
  font-size: var(--font-size-xs);
  color: #F97316;
  margin-top: var(--spacing-xs);
  text-align: center;
}
</style>

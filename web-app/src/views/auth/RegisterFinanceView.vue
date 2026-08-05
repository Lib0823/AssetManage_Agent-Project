<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/services/api'
import { Toast, Dialog } from 'vant'
import { isPlatformAuthAvailable, registerBiometric } from '@/services/webauthn'
import { logger } from '@/utils/logger'

const router = useRouter()
const authStore = useAuthStore()

const stockInvestment = ref(true)
const coinInvestment = ref(false)
const isRegistering = ref(false)
const isValidating = ref(false)
const validationResult = ref(null)
const retryCountdown = ref(0)
let countdownTimer = null

const brokerForm = ref({
  accountNumber: '',
  appKey: '',
  appSecret: ''
})

// KIS 모드: MOCK(모의) / REAL(실전). 이 도메인으로 키를 인증하고 이후 매매가 라우팅됨.
const kisMode = ref('MOCK')

// 모드 변경 시 기존 인증 결과 무효화 (도메인이 달라 재인증 필요)
const selectKisMode = (mode) => {
  if (kisMode.value === mode) return
  kisMode.value = mode
  validationResult.value = null
}

// Step 1 데이터 확인
onMounted(() => {
  if (!authStore.hasStep1Data()) {
    Toast.fail('기본 정보를 먼저 입력해주세요')
    router.push('/register')
  }
})

// 타이머 정리
onUnmounted(() => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
  }
})

const handleGetAppKey = () => {
  window.open('https://apiportal.koreainvestment.com', '_blank')
}

const startRetryCountdown = (seconds) => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
  }

  retryCountdown.value = seconds
  countdownTimer = setInterval(() => {
    retryCountdown.value--
    if (retryCountdown.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

const isRateLimitError = (errorData) => {
  return errorData?.errorCode === 'EGW00133' ||
         errorData?.message?.includes('1분당 1회') ||
         errorData?.message?.includes('잠시 후 다시 시도')
}

const handleValidateKis = async () => {
  // 입력값 검증 (인증에는 APP Key와 Secret만 필요)
  if (!brokerForm.value.appKey || !brokerForm.value.appSecret) {
    Toast.fail('APP Key와 APP Secret을 입력해주세요')
    return
  }

  try {
    isValidating.value = true
    validationResult.value = null

    const response = await authApi.validateKisAccount({
      appKey: brokerForm.value.appKey,
      appSecret: brokerForm.value.appSecret,
      mode: kisMode.value
    })

    validationResult.value = response.data

    if (response.data.valid) {
      Toast.success(response.data.message)
      // 성공 시 타이머 정리
      if (countdownTimer) {
        clearInterval(countdownTimer)
        retryCountdown.value = 0
      }
    } else {
      Toast.fail(response.data.message)
      // Rate limiting 에러인 경우 타이머 시작
      if (isRateLimitError(response.data)) {
        startRetryCountdown(60)
      }
    }
  } catch (error) {
    // 요청 바디(KIS appKey/appSecret)가 담긴 error.config.data가 노출되지 않도록 status/message만 남긴다.
    logger.debug('KIS validation error:', error.response?.status, error.response?.data?.message)

    // 에러 메시지 처리
    if (error.response?.data?.data?.message) {
      Toast.fail(error.response.data.data.message)
      validationResult.value = error.response.data.data

      // Rate limiting 에러인 경우 타이머 시작
      if (isRateLimitError(error.response.data.data)) {
        startRetryCountdown(60)
      }
    } else if (error.response?.data?.message) {
      Toast.fail(error.response.data.message)
    } else {
      Toast.fail('KIS 계정 검증 중 오류가 발생했습니다')
    }
  } finally {
    isValidating.value = false
  }
}

// 가입+자동로그인 직후, 기기가 지원하면 생체 로그인 등록을 권유한다.
// 등록 실패/취소/미지원 어떤 경우에도 가입 완료 흐름을 막지 않는다(skip 허용).
const promptBiometricEnrollment = async () => {
  try {
    const available = await isPlatformAuthAvailable()
    if (!available) {
      return
    }

    await Dialog.confirm({
      title: '생체 로그인 등록',
      message: '이 기기에서 Face ID / 지문으로 로그인하시겠어요?',
      confirmButtonText: '등록',
      cancelButtonText: '나중에'
    })

    // confirm 시에만 도달 (cancel 은 reject → catch 에서 무시)
    await registerBiometric()
    Toast.success('생체 로그인이 등록되었습니다')
  } catch (error) {
    // 사용자가 '나중에'를 누르면 Dialog 가 reject(문자열 'cancel')하므로 조용히 통과
    if (error === 'cancel') {
      return
    }
    console.error('Biometric enrollment skipped:', error)
    // 등록 실패는 안내만 하고 가입 흐름은 계속 진행
    Toast('생체 로그인 등록을 건너뛰었습니다')
  }
}

const handleRegister = async () => {
  // KIS 계좌 정보 검증
  if (stockInvestment.value) {
    if (!brokerForm.value.accountNumber || !brokerForm.value.appKey || !brokerForm.value.appSecret) {
      Toast.fail('KIS 계좌 정보를 모두 입력해주세요')
      return
    }

    // KIS 계정 인증 확인
    if (!validationResult.value || !validationResult.value.valid) {
      Toast.fail('KIS 계정 인증을 먼저 완료해주세요')
      return
    }
  }

  try {
    isRegistering.value = true

    // Step 1 데이터 가져오기
    const step1Data = authStore.registrationData.step1

    // 회원가입 요청 데이터 생성
    const registrationData = {
      username: step1Data.id,
      password: step1Data.password,
      passwordConfirm: step1Data.passwordConfirm,
      email: step1Data.email || null,
      name: step1Data.name,
      phone: step1Data.phone,
      birthDate: step1Data.birthDate,
      kisAccount: stockInvestment.value ? {
        accountNumber: brokerForm.value.accountNumber,
        appKey: brokerForm.value.appKey,
        appSecret: brokerForm.value.appSecret,
        mode: kisMode.value
      } : null
    }

    // API 호출
    await authApi.register(registrationData)

    Toast.success('회원가입이 완료되었습니다')

    // 회원가입 성공 후 자동 로그인
    try {
      const loginResponse = await authApi.login({
        username: step1Data.id,
        password: step1Data.password
      })

      // 토큰 저장
      authStore.setAuthData({
        accessToken: loginResponse.accessToken,
        refreshToken: loginResponse.refreshToken,
        user: loginResponse.user
      })

      // 회원가입 데이터 정리
      authStore.clearRegistrationData()

      // 생체 로그인(패스키) 등록 권유 — 실패/취소해도 가입 흐름은 막지 않음
      await promptBiometricEnrollment()

      // Toast를 보여주고 홈 화면으로 이동
      setTimeout(() => {
        router.push('/home')
      }, 1000)
    } catch (loginError) {
      // 요청 바디(비밀번호)가 담긴 loginError.config.data가 노출되지 않도록 status/message만 남긴다.
      logger.debug('Auto-login failed:', loginError.response?.status, loginError.response?.data?.message)
      Toast.fail('회원가입은 완료되었으나 자동 로그인에 실패했습니다. 로그인 화면으로 이동합니다.')
      authStore.clearRegistrationData()
      setTimeout(() => {
        router.push('/login')
      }, 1500)
    }
  } catch (error) {
    // 요청 바디(비밀번호, KIS appKey/appSecret)가 담긴 error.config.data가 노출되지 않도록 status/message만 남긴다.
    logger.debug('Registration error:', error.response?.status, error.response?.data?.message)

    // 에러 메시지 처리
    if (error.response?.data?.message) {
      Toast.fail(error.response.data.message)
    } else if (error.response?.data?.error) {
      Toast.fail(error.response.data.error)
    } else {
      Toast.fail('회원가입 중 오류가 발생했습니다')
    }

    // 중복 에러인 경우 Step 1로 돌아가기
    if (error.response?.data?.code === 3001 || error.response?.data?.code === 3002) {
      setTimeout(() => {
        router.push('/register')
      }, 1500)
    }
  } finally {
    isRegistering.value = false
  }
}
</script>

<template>
  <div class="register-finance-screen">
    <div class="content">
      <!-- Header -->
      <div class="header">
        <div class="logo">
          <span class="logo-text">F</span>
          <span class="logo-dot">.</span>
        </div>
        <div class="title-group">
          <h1 class="title">회원가입</h1>
          <p class="step-indicator">• 금융 정보</p>
        </div>
      </div>

      <!-- Form -->
      <div class="form">
        <!-- Stock Investment -->
        <div class="option-group">
          <label class="checkbox-wrapper">
            <input type="checkbox" v-model="stockInvestment" disabled checked />
            <span class="checkbox-label">주식/채권 투자 (필수)</span>
          </label>

          <div v-if="stockInvestment" class="broker-card">
            <h3 class="broker-title">한국 투자 증권</h3>

            <!-- 모의/실전 모드 선택 -->
            <div class="form-group">
              <label class="label">투자 모드</label>
              <div class="mode-toggle">
                <button
                  type="button"
                  class="mode-btn"
                  :class="{ 'mode-btn-active': kisMode === 'MOCK' }"
                  @click="selectKisMode('MOCK')"
                >
                  모의투자
                </button>
                <button
                  type="button"
                  class="mode-btn"
                  :class="{ 'mode-btn-active': kisMode === 'REAL' }"
                  @click="selectKisMode('REAL')"
                >
                  실전투자
                </button>
              </div>
              <p class="mode-hint">
                {{ kisMode === 'REAL'
                  ? '실전투자 KIS 앱키로 인증합니다 (실제 주문 체결)'
                  : '모의투자 KIS 앱키로 인증합니다' }}
              </p>
            </div>

            <div class="form-group">
              <label class="label">계좌번호</label>
              <input
                v-model="brokerForm.accountNumber"
                type="text"
                class="input"
                placeholder="Account Number"
              />
            </div>

            <div class="form-group">
              <label class="label">APP_Key</label>
              <input
                v-model="brokerForm.appKey"
                type="text"
                class="input"
                placeholder="APP Key"
              />
            </div>

            <div class="form-group">
              <label class="label">APP_Secret</label>
              <input
                v-model="brokerForm.appSecret"
                type="password"
                class="input"
                placeholder="APP Secret"
              />
            </div>

            <button class="link-btn" @click="handleGetAppKey">
              APP Key(Secret) 발급 >
            </button>

            <p class="kis-info-message">
              💡 인증에는 APP Key와 Secret만 필요합니다
            </p>

            <button
              class="btn-validate"
              :class="{ 'btn-validate-success': validationResult?.valid, 'btn-validate-error': validationResult && !validationResult.valid }"
              @click="handleValidateKis"
              :disabled="isValidating || retryCountdown > 0"
            >
              {{
                isValidating ? '검증 중...' :
                retryCountdown > 0 ? `재시도 가능 (${retryCountdown}초 후)` :
                validationResult?.valid ? '✓ 인증 성공' :
                validationResult ? '✗ 인증 실패 - 다시 시도' :
                'KIS 계정 인증'
              }}
            </button>

            <!-- 성공 카드 -->
            <div v-if="validationResult && validationResult.valid" class="success-card">
              <div class="success-header">
                <span class="success-icon">✓</span>
                <span class="success-title">인증 성공</span>
              </div>
              <div class="success-content">
                <div class="success-message">{{ validationResult.message }}</div>
                <div class="success-hint">
                  💡 KIS 계정 인증이 완료되었습니다. 회원가입을 진행해주세요.
                </div>
              </div>
            </div>

            <!-- 에러 카드 -->
            <div v-if="validationResult && !validationResult.valid" class="error-card">
              <div class="error-header">
                <span class="error-icon">⚠️</span>
                <span class="error-title">인증 실패</span>
              </div>
              <div class="error-content">
                <div class="error-message">{{ validationResult.message }}</div>
                <div v-if="validationResult.errorCode" class="error-code">
                  에러 코드: {{ validationResult.errorCode }}
                </div>
                <div v-if="isRateLimitError(validationResult)" class="error-hint">
                  💡 KIS API는 1분에 1회만 토큰 발급이 가능합니다.
                  잠시 후 다시 시도해주세요.
                </div>
                <div v-else class="error-hint">
                  💡 APP Key와 APP Secret을 다시 확인해주세요.
                </div>
              </div>
            </div>

            <p v-if="!validationResult" class="validation-info-message">
              회원가입 전에 KIS 계정 인증을 완료해주세요
            </p>
          </div>
        </div>

        <!-- Coin Investment -->
        <div class="option-group">
          <label class="checkbox-wrapper disabled">
            <input type="checkbox" v-model="coinInvestment" disabled />
            <span class="checkbox-label">코인 투자 (준비 중)</span>
          </label>
        </div>

        <!-- Buttons -->
        <div class="button-group">
          <button class="btn btn-back" @click="router.push('/register')">
            이전
          </button>
          <button
            class="btn btn-register"
            :class="{ 'btn-register-disabled': stockInvestment && (!validationResult || !validationResult.valid) }"
            @click="handleRegister"
            :disabled="isRegistering || (stockInvestment && (!validationResult || !validationResult.valid))"
          >
            {{ isRegistering ? '처리 중...' : '회원가입' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-finance-screen {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: var(--spacing-3xl) var(--spacing-xl);
  background: var(--color-bg-primary);
}

.content {
  display: flex;
  flex-direction: column;
  max-width: 340px;
  margin: 0 auto;
  width: 100%;
}

.header {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-3xl);
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  background: var(--color-primary);
  border-radius: var(--radius-lg);
  flex-shrink: 0;
}

.logo-text {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-inverse);
}

.logo-dot {
  font-size: 28px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-inverse);
}

.title-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.step-indicator {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2xl);
}

.option-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.checkbox-wrapper {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  cursor: pointer;
}

.checkbox-wrapper:has(input:disabled:not([type="checkbox"]:disabled)) {
  cursor: not-allowed;
  opacity: 0.5;
}

.checkbox-wrapper.disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.checkbox-wrapper input {
  width: 20px;
  height: 20px;
  accent-color: var(--color-primary);
}

.checkbox-wrapper input:disabled {
  cursor: not-allowed;
}

.checkbox-label {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.broker-card {
  background: var(--color-bg-highlight);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
}

.broker-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  text-align: center;
  margin-bottom: var(--spacing-lg);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-md);
}

.label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.input {
  padding: var(--spacing-md);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  outline: none;
  background: var(--color-bg-primary);
}

.input::placeholder {
  color: var(--color-text-tertiary);
}

.link-btn {
  background: none;
  border: none;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  cursor: pointer;
  text-align: center;
  width: 100%;
}

.mode-toggle {
  display: flex;
  gap: var(--spacing-xs);
  background: var(--color-bg-primary);
  border-radius: var(--radius-md);
  padding: 3px;
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

.mode-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-top: var(--spacing-xs);
}

.kis-info-message {
  font-size: var(--font-size-xs);
  color: #3B82F6;
  text-align: center;
  margin-top: var(--spacing-xs);
  padding: var(--spacing-xs);
  background: #EFF6FF;
  border-radius: var(--radius-sm);
}

.button-group {
  display: flex;
  gap: var(--spacing-md);
  margin-top: var(--spacing-lg);
}

.btn {
  flex: 1;
  padding: var(--spacing-lg);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-back {
  background: var(--color-bg-tertiary);
  color: var(--color-text-primary);
}

.btn-back:hover {
  opacity: 0.8;
}

.btn-register {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.btn-register:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-register:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-register-disabled {
  background: var(--color-bg-tertiary) !important;
  color: var(--color-text-secondary) !important;
}

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
  margin-top: var(--spacing-sm);
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

.success-card {
  margin-top: var(--spacing-md);
  background: #F0FDF4;
  border: 1px solid #86EFAC;
  border-radius: var(--radius-md);
  overflow: hidden;
}

.success-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: #DCFCE7;
  border-bottom: 1px solid #86EFAC;
}

.success-icon {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: #16A34A;
}

.success-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: #16A34A;
}

.success-content {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.success-message {
  font-size: var(--font-size-sm);
  color: #16A34A;
  font-weight: var(--font-weight-medium);
}

.success-hint {
  font-size: var(--font-size-xs);
  color: #15803D;
  background: #FEF9C3;
  border: 1px solid #FDE047;
  padding: var(--spacing-sm);
  border-radius: var(--radius-sm);
  margin-top: var(--spacing-xs);
}

.error-card {
  margin-top: var(--spacing-md);
  background: #FEF2F2;
  border: 1px solid #FCA5A5;
  border-radius: var(--radius-md);
  overflow: hidden;
}

.error-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: #FEE2E2;
  border-bottom: 1px solid #FCA5A5;
}

.error-icon {
  font-size: var(--font-size-lg);
}

.error-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: #DC2626;
}

.error-content {
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.error-message {
  font-size: var(--font-size-sm);
  color: #DC2626;
  font-weight: var(--font-weight-medium);
}

.error-code {
  font-size: var(--font-size-xs);
  color: #991B1B;
  font-family: monospace;
  background: #FEE2E2;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  width: fit-content;
}

.error-hint {
  font-size: var(--font-size-xs);
  color: #7C2D12;
  background: #FFF7ED;
  border: 1px solid #FDBA74;
  padding: var(--spacing-sm);
  border-radius: var(--radius-sm);
  margin-top: var(--spacing-xs);
}

.validation-info-message {
  font-size: var(--font-size-xs);
  color: #F97316;
  margin-top: var(--spacing-xs);
  text-align: center;
}
</style>

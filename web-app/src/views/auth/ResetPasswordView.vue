<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/services/api'
import { Toast } from 'vant'
import { logger } from '@/utils/logger'

const router = useRouter()

const form = ref({
  username: '',
  phone: '',
  newPassword: '',
  passwordConfirm: ''
})

const isResetting = ref(false)

const handleReset = async () => {
  // 유효성 검사
  if (!form.value.username) {
    Toast.fail('아이디를 입력해주세요')
    return
  }

  if (!form.value.phone) {
    Toast.fail('핸드폰 번호를 입력해주세요')
    return
  }

  if (!form.value.newPassword) {
    Toast.fail('새 비밀번호를 입력해주세요')
    return
  }

  if (form.value.newPassword.length < 8) {
    Toast.fail('비밀번호는 8자 이상이어야 합니다')
    return
  }

  if (form.value.newPassword !== form.value.passwordConfirm) {
    Toast.fail('비밀번호가 일치하지 않습니다')
    return
  }

  try {
    isResetting.value = true

    // 전화번호에서 하이픈 제거 (백엔드는 숫자만 받음)
    const phoneNumber = form.value.phone.replace(/[^0-9]/g, '')

    await authApi.resetPassword({
      username: form.value.username,
      phone: phoneNumber,
      newPassword: form.value.newPassword,
      passwordConfirm: form.value.passwordConfirm
    })

    Toast.success('비밀번호가 재설정되었습니다')

    setTimeout(() => {
      router.push('/login')
    }, 1000)
  } catch (error) {
    // 요청 바디(새 비밀번호)가 담긴 error.config.data가 그대로 노출되지 않도록 status/message만 남긴다.
    logger.debug('Password reset error:', error.response?.status, error.response?.data?.message)

    // 에러 메시지 처리
    if (error.response?.data?.message) {
      Toast.fail(error.response.data.message)
    } else if (error.response?.data?.error) {
      Toast.fail(error.response.data.error)
    } else {
      Toast.fail('비밀번호 재설정 중 오류가 발생했습니다')
    }
  } finally {
    isResetting.value = false
  }
}
</script>

<template>
  <div class="reset-password-screen">
    <div class="content">
      <!-- Header -->
      <div class="header">
        <div class="logo">
          <span class="logo-text">F</span>
          <span class="logo-dot">.</span>
        </div>
        <h1 class="title">비밀번호 재설정</h1>
      </div>

      <!-- Form -->
      <div class="form">
        <!-- User Identification -->
        <h3 class="card-title">사용자 정보</h3>
        <div class="info-card">
          <div class="form-group">
            <label class="label">아이디</label>
            <input
              v-model="form.username"
              type="text"
              class="input"
              placeholder="Username"
            />
          </div>
        </div>

        <!-- New Password -->
        <h3 class="card-title">새 비밀번호</h3>
        <div class="info-card">
          <div class="form-group">
            <label class="label">새 비밀번호</label>
            <input
              v-model="form.newPassword"
              type="password"
              class="input"
              placeholder="New Password (8자 이상)"
            />
          </div>

          <div class="form-group">
            <label class="label">비밀번호 확인</label>
            <input
              v-model="form.passwordConfirm"
              type="password"
              class="input"
              placeholder="Password Check"
            />
          </div>
        </div>

        <!-- Phone -->
        <h3 class="card-title">휴대폰 번호</h3>
        <div class="info-card">
          <div class="form-group">
            <label class="label">휴대폰 번호</label>
            <input
              v-model="form.phone"
              type="tel"
              class="input"
              placeholder="010-1234-5678"
            />
          </div>
        </div>

        <!-- Reset Button -->
        <button class="btn btn-reset" @click="handleReset" :disabled="isResetting">
          {{ isResetting ? '처리 중...' : '재설정' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reset-password-screen {
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
  align-items: center;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-2xl);
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

.title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.info-card {
  background: var(--color-bg-highlight);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-sm);
}

.card-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-xs);
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: var(--spacing-sm) 0;
}

.info-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.verification-section {
  margin-top: var(--spacing-sm);
}

.section-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-xs);
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
  flex: 1;
  padding: var(--spacing-md);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  outline: none;
  background: var(--color-bg-tertiary);
}

.input::placeholder {
  color: var(--color-text-tertiary);
}

.input-with-btn {
  display: flex;
  gap: var(--spacing-sm);
  align-items: center;
}

.inline-btn {
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg-tertiary);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
  cursor: pointer;
  white-space: nowrap;
}

.inline-btn.orange {
  background: #F97316;
  color: var(--color-text-inverse);
}

.btn-reset {
  width: 100%;
  padding: var(--spacing-lg);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  margin-top: var(--spacing-lg);
}

.btn-reset:hover {
  background: var(--color-primary-dark);
}
</style>

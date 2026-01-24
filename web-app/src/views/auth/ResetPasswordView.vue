<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const existingInfo = ref({
  id: 'testID',
  password: '************',
  lastChanged: '2024-06-12'
})

const newInfo = ref({
  password: '',
  passwordConfirm: ''
})

const verification = ref({
  phone: '010-1234-5678',
  authCode: ''
})

const handleSendAuthCode = () => {
  console.log('Send auth code to:', verification.value.phone)
}

const handleVerifyCode = () => {
  console.log('Verify code:', verification.value.authCode)
}

const handleReset = () => {
  console.log('Reset password')
  router.push('/login')
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
        <h3 class="card-title">내 정보</h3>
        <!-- Existing Info -->
        <div class="info-card">
          <div class="info-row">
            <span class="info-label">아이디</span>
            <span class="info-value">{{ existingInfo.id }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">비밀번호</span>
            <span class="info-value">{{ existingInfo.password }}</span>
          </div>

          <div class="info-row">
            <span class="info-label">마지막 변경일</span>
            <span class="info-value">{{ existingInfo.lastChanged }}</span>
          </div>
        </div>

        <!-- New Info -->
        <h3 class="card-title">변경 정보</h3>
        <div class="info-card">
          <div class="form-group">
            <label class="label">변경 비밀번호</label>
            <input
              v-model="newInfo.password"
              type="password"
              class="input"
              placeholder="New Password"
            />
          </div>

          <div class="form-group">
            <label class="label">비밀번호 확인</label>
            <input
              v-model="newInfo.passwordConfirm"
              type="password"
              class="input"
              placeholder="Password Check"
            />
          </div>
        </div>

        <!-- Verification -->
        <div class="verification-section">
          <h3 class="section-title">본인 인증</h3>

          <div class="form-group">
            <label class="label">휴대폰 번호</label>
            <div class="input-with-btn">
              <input
                v-model="verification.phone"
                type="tel"
                class="input"
                readonly
              />
              <button class="inline-btn orange" @click="handleSendAuthCode">인증 번호 전송</button>
            </div>
          </div>

          <div class="form-group">
            <label class="label">인증 번호</label>
            <div class="input-with-btn">
              <input
                v-model="verification.authCode"
                type="text"
                class="input"
                placeholder="Auth Number"
              />
              <button class="inline-btn" @click="handleVerifyCode">인증</button>
            </div>
          </div>
        </div>

        <!-- Reset Button -->
        <button class="btn btn-reset" @click="handleReset">
          재설정
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

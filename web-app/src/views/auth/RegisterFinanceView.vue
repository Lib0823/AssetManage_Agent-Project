<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const stockInvestment = ref(true)
const coinInvestment = ref(false)

const brokerForm = ref({
  accountNumber: '',
  appKey: '',
  appSecret: ''
})

const handleGetAppKey = () => {
  window.open('https://apiportal.koreainvestment.com', '_blank')
}

const handleRegister = () => {
  // Register and redirect to terms
  router.push('/terms')
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
            <input type="checkbox" v-model="stockInvestment" />
            <span class="checkbox-label">주식/채권 투자</span>
          </label>

          <div v-if="stockInvestment" class="broker-card">
            <h3 class="broker-title">한국 투자 증권</h3>

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
          </div>
        </div>

        <!-- Coin Investment -->
        <div class="option-group">
          <label class="checkbox-wrapper">
            <input type="checkbox" v-model="coinInvestment" />
            <span class="checkbox-label">코인 투자</span>
          </label>
        </div>

        <!-- Register Button -->
        <button class="btn btn-register" @click="handleRegister">
          회원가입
        </button>
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

.checkbox-wrapper input {
  width: 20px;
  height: 20px;
  accent-color: var(--color-primary);
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

.btn-register {
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

.btn-register:hover {
  background: var(--color-primary-dark);
}
</style>

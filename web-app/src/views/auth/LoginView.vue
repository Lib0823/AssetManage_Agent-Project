<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const form = ref({
  id: '',
  password: ''
})

const autoLogin = ref(true)

const handleLogin = async () => {
  // Mock login - in real app, call API
  localStorage.setItem('accessToken', 'mock-token')
  router.push('/home')
}

const goToResetPassword = () => {
  router.push('/reset-password')
}
</script>

<template>
  <div class="login-screen">
    <div class="content">
      <!-- Logo & Title -->
      <div class="header">
        <div class="logo">
          <span class="logo-text">F</span>
          <span class="logo-dot">.</span>
        </div>
        <h1 class="title">로그인</h1>
      </div>

      <!-- Form -->
      <div class="form">
        <div class="form-group">
          <label class="label">ID</label>
          <input
            v-model="form.id"
            type="text"
            class="input"
            placeholder="Input ID"
          />
        </div>

        <div class="form-group">
          <label class="label">PW</label>
          <input
            v-model="form.password"
            type="password"
            class="input"
            placeholder="Input Password"
          />
        </div>

        <button class="btn btn-login" @click="handleLogin">
          로그인
        </button>

        <div class="options">
          <label class="checkbox-wrapper">
            <input type="checkbox" v-model="autoLogin" />
            <span class="checkmark"></span>
            <span class="checkbox-label">자동 로그인</span>
          </label>

          <button class="link-btn" @click="goToResetPassword">
            비밀번호 재설정
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-screen {
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
  gap: var(--spacing-xl);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.label {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.input {
  padding: var(--spacing-md) 0;
  border: none;
  border-bottom: 1px solid var(--color-border);
  font-size: var(--font-size-base);
  outline: none;
  background: transparent;
}

.input::placeholder {
  color: var(--color-text-tertiary);
}

.input:focus {
  border-bottom-color: var(--color-primary);
}

.btn-login {
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

.btn-login:hover {
  background: var(--color-primary-dark);
}

.options {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
  accent-color: var(--color-success);
}

.checkbox-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.link-btn {
  background: none;
  border: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.link-btn:hover {
  color: var(--color-primary);
}
</style>

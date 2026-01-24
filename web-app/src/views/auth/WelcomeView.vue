<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const goToLogin = () => {
  router.push('/login')
}

const goToRegister = () => {
  router.push('/register')
}

const goToFaceId = () => {
  // Face ID / Biometric authentication
  console.log('Face ID')
}
</script>

<template>
  <div class="welcome-screen">
    <div class="content-card">
      <!-- Logo -->
      <div class="logo">
        <span class="logo-text">F.</span>
        <span class="logo-dot">.</span>
      </div>

      <!-- Title -->
      <h1 class="title">Let's start<br />managing your<br />finances</h1>

      <!-- Subtitle -->
      <p class="subtitle">YOUR PERSONAL FINANCE<br />MANAGER</p>

      <!-- Actions -->
      <div class="actions">
        <div class="login-row">
          <button class="btn btn-login" @click="goToLogin">Log In</button>
          <button class="btn btn-faceid" @click="goToFaceId">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
              <rect x="3" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2"/>
              <rect x="14" y="3" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2"/>
              <rect x="3" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2"/>
              <rect x="14" y="14" width="7" height="7" rx="1" stroke="currentColor" stroke-width="2"/>
            </svg>
          </button>
        </div>
        <button class="btn btn-register" @click="goToRegister">Register</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.welcome-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xl);
  background: linear-gradient(180deg, #1F2937 0%, #111827 100%);
}

.content-card {
  width: 100%;
  max-width: 340px;
  background: var(--color-bg-secondary);
  border-radius: var(--radius-2xl);
  padding: var(--spacing-3xl);
}

.logo {
  display: flex;
  align-items: baseline;
  margin-bottom: var(--spacing-xl);
}

.logo-text {
  font-size: 32px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-inverse);
  background: var(--color-primary);
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg);
}

.logo-dot {
  font-size: 32px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-inverse);
  background: var(--color-primary);
  width: 48px;
  height: 48px;
  display: none;
}

.title {
  font-size: var(--font-size-3xl);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-primary);
  line-height: 1.3;
  margin-bottom: var(--spacing-lg);
}

.subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: var(--spacing-3xl);
}

.actions {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.login-row {
  display: flex;
  gap: var(--spacing-md);
}

.btn {
  padding: var(--spacing-lg) var(--spacing-xl);
  border-radius: var(--radius-lg);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
}

.btn-login {
  flex: 1;
  background: #1F2937;
  color: var(--color-text-inverse);
}

.btn-login:hover {
  background: #374151;
}

.btn-faceid {
  width: 56px;
  height: 56px;
  background: #1F2937;
  color: var(--color-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.btn-faceid:hover {
  background: #374151;
}

.btn-register {
  width: 100%;
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.btn-register:hover {
  background: var(--color-primary-dark);
}
</style>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const form = ref({
  id: '',
  password: '',
  passwordConfirm: '',
  name: '',
  phone: '',
  authCode: '',
  birth: ['1990', '01', '01']
})

const showDatePicker = ref(false)

const formattedBirth = computed(() => {
  const [year, month, day] = form.value.birth
  return `${year}년 ${month}월 ${day}일`
})

const onConfirmDate = ({ selectedValues }) => {
  form.value.birth = selectedValues
  showDatePicker.value = false
}

const handleCheckDuplicate = () => {
  // Check duplicate ID
  console.log('Check duplicate:', form.value.id)
}

const handleSendAuthCode = () => {
  // Send auth code
  console.log('Send auth code to:', form.value.phone)
}

const handleVerifyCode = () => {
  // Verify auth code
  console.log('Verify code:', form.value.authCode)
}

const handleNext = () => {
  router.push('/register/finance')
}
</script>

<template>
  <div class="register-screen">
    <div class="content">
      <!-- Header -->
      <div class="header">
        <div class="logo">
          <span class="logo-text">F</span>
          <span class="logo-dot">.</span>
        </div>
        <div class="title-group">
          <h1 class="title">회원가입</h1>
          <p class="step-indicator">• 기본 정보</p>
        </div>
      </div>

      <!-- Form -->
      <div class="form">
        <!-- ID -->
        <div class="form-group">
          <label class="label">아이디</label>
          <div class="input-with-btn">
            <input
              v-model="form.id"
              type="text"
              class="input"
              placeholder="ID"
            />
            <button class="inline-btn" @click="handleCheckDuplicate">중복 확인</button>
          </div>
        </div>

        <!-- Password -->
        <div class="form-group">
          <label class="label">비밀번호</label>
          <input
            v-model="form.password"
            type="password"
            class="input"
            placeholder="Password"
          />
        </div>

        <!-- Password Confirm -->
        <div class="form-group">
          <label class="label">비밀번호 확인</label>
          <input
            v-model="form.passwordConfirm"
            type="password"
            class="input"
            placeholder="Password Check"
          />
        </div>

        <!-- Name -->
        <div class="form-group">
          <label class="label">이름</label>
          <input
            v-model="form.name"
            type="text"
            class="input"
            placeholder="Name"
          />
        </div>

        <!-- Phone -->
        <div class="form-group">
          <label class="label">핸드폰</label>
          <div class="input-with-btn">
            <input
              v-model="form.phone"
              type="tel"
              class="input"
              placeholder="Phone Number"
            />
            <button class="inline-btn orange" @click="handleSendAuthCode">인증 번호 전송</button>
          </div>
        </div>

        <!-- Auth Code -->
        <div class="form-group">
          <label class="label">인증번호</label>
          <div class="input-with-btn">
            <input
              v-model="form.authCode"
              type="text"
              class="input"
              placeholder="Auth Number"
            />
            <button class="inline-btn" @click="handleVerifyCode">인증</button>
          </div>
        </div>

        <!-- Birth Date -->
        <div class="form-group">
          <label class="label">생년월일</label>
          <div class="date-display" @click="showDatePicker = true">
            <span class="date-value">{{ formattedBirth }}</span>
            <van-icon name="arrow-down" class="date-icon" />
          </div>
        </div>

        <!-- Date Picker Popup -->
        <van-popup v-model:show="showDatePicker" position="bottom" round>
          <van-date-picker
            v-model="form.birth"
            title="생년월일 선택"
            :min-date="new Date(1900, 0, 1)"
            :max-date="new Date()"
            @confirm="onConfirmDate"
            @cancel="showDatePicker = false"
          />
        </van-popup>

        <!-- Next Button -->
        <button class="btn btn-next" @click="handleNext">
          다음
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-screen {
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
  gap: var(--spacing-lg);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.input {
  flex: 1;
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

.input-with-btn {
  display: flex;
  gap: var(--spacing-sm);
  align-items: flex-end;
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

.inline-btn:hover {
  opacity: 0.9;
}

.date-display {
  padding: var(--spacing-md);
  background: var(--color-bg-highlight);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}

.date-display:active {
  opacity: 0.8;
}

.date-value {
  font-size: var(--font-size-base);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.date-icon {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-md);
}

.btn-next {
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

.btn-next:hover {
  background: var(--color-primary-dark);
}
</style>

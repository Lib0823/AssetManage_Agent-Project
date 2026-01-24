<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const handleAgree = () => {
  localStorage.setItem('accessToken', 'mock-token')
  router.push('/home')
}

const handleDisagree = () => {
  router.push('/welcome')
}

const handleSendEmail = () => {
  console.log('Send terms via email')
}
</script>

<template>
  <div class="terms-screen">
    <div class="content">
      <!-- Header -->
      <div class="header">
        <div class="logo">
          <span class="logo-text">F</span>
          <span class="logo-dot">.</span>
        </div>
        <h1 class="title">이용 약관</h1>
      </div>

      <!-- Terms Content -->
      <div class="terms-content">
        <section class="section">
          <h2 class="section-title">중요사항</h2>
          <p class="section-text">
            본 서비스를 이용하기 전에 다음의 약관을 반드시 읽어 주시기 바랍니다. 서비스 이용 시 본 약관에 동의한 것으로 간주됩니다.
          </p><br>
        </section>

        <section class="section">
          <h2 class="section-title highlight">A. 서비스 이용 약관</h2>

        </section>

        <div class="terms-body">
          <p>
            <strong>F.</strong>는 AI 기반 주식 자동 투자 및 포트폴리오 관리 서비스입니다. 본 서비스는 한국투자증권 API를 통해 국내/미국 주식 시세 조회, 매수/매도 주문, AI 자동매매 기능을 제공합니다.
          </p>
          <p>
            <strong>투자 위험 고지:</strong> 주식 투자는 원금 손실의 위험이 있으며, 과거 수익률이 미래 수익을 보장하지 않습니다. AI 추천 및 자동매매 기능은 참고용이며, 최종 투자 결정과 책임은 회원 본인에게 있습니다.
          </p>
          <p>
            <strong>개인정보 보호:</strong> 회원의 증권사 API 키, 계좌 정보 등 민감 정보는 암호화하여 안전하게 보관되며, 서비스 제공 목적 외에는 사용되지 않습니다.
          </p>
          <p>
            <strong>서비스 제한:</strong> 시스템 점검, 증권사 API 장애, 시장 휴장일 등의 사유로 서비스가 일시 중단될 수 있으며, 이로 인한 손해에 대해 회사는 책임지지 않습니다.
          </p>
        </div>
      </div>

      <!-- Actions -->
      <div class="actions">
        <button class="link-btn disagree" @click="handleDisagree">
          동의하지 않음
        </button>
        <button class="link-btn agree" @click="handleAgree">
          동의
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.terms-screen {
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
  margin-bottom: var(--spacing-xl);
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

.email-btn {
  background: none;
  border: none;
  font-size: var(--font-size-base);
  color: var(--color-primary);
  cursor: pointer;
  text-align: center;
  margin-bottom: var(--spacing-2xl);
}

.terms-content {
  flex: 1;
  overflow-y: auto;
}

.section {
  margin-bottom: var(--spacing-lg);
}

.section-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-sm);
}

.section-title.highlight {
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.expandable {
  color: var(--color-primary);
}

.section-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.terms-body {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 1.8;
}

.terms-body p {
  margin-bottom: var(--spacing-lg);
}

.terms-body strong {
  color: var(--color-text-primary);
}

.actions {
  display: flex;
  justify-content: space-between;
  margin-top: var(--spacing-2xl);
  padding-top: var(--spacing-lg);
  border-top: 1px solid var(--color-border-light);
}

.link-btn {
  background: none;
  border: none;
  font-size: var(--font-size-base);
  cursor: pointer;
}

.link-btn.disagree {
  color: var(--color-primary);
}

.link-btn.agree {
  color: var(--color-primary);
  font-weight: var(--font-weight-semibold);
}
</style>

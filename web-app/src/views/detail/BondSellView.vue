<script setup>
/**
 * 채권 매도 화면 (`/bonds/:code/sell`).
 *
 * **매도 대상은 종목이 아니라 매수 로트다.** `buyDate`/`buySeq`/`separateTaxation` 셋은
 * 사용자가 입력하는 값이 아니라 잔고 응답을 그대로 되돌려 보내는 값이며, 하나라도 빠지면
 * 서버가 400 을 준다. 그래서 이 화면은 진입 시 잔고를 다시 조회해 로트를 확정하고
 * (직접 URL 진입·새로고침에도 견디도록), 실패하면 라우트 쿼리로 받은 값을 폴백으로 쓴다.
 * 둘 다 없으면 주문 자체를 막는다 — 로트 없이 나가는 매도는 KIS 가 어느 매수분을 파는지
 * 모르는 상태로 처리된다.
 *
 * 단가는 소수를 갖는다. 입력값을 `Number` 로 왕복시키지 않고 **사용자가 친 문자열 그대로**
 * 전송해 반올림·지수표기 변질을 원천 차단한다(`type="number"` 를 쓰지 않는 이유이기도 하다).
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import { bondApi } from '@/services/api'
import { logger } from '@/utils/logger'
import { showError } from '@/utils/toast'
import {
  calcExpectedAmount,
  formatAmount,
  formatKisDate,
  formatQuantity,
  formatUnitPrice,
  readBondLotQuery
} from '@/utils/bond'

const route = useRoute()
const router = useRouter()

const bondCode = computed(() => String(route.params.code || ''))

// 쿼리로 받은 로트(폴백). 잔고 조회가 성공하면 그 값으로 덮어쓴다.
const lot = ref(readBondLotQuery(route.query))

// 예상 금액 환산 계수. **서버 설정값이므로 없으면 계산하지 않는다** (하드코딩 금지).
const faceValueDivisor = ref(null)

const balanceLoading = ref(true)
const balanceNotice = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const showConfirm = ref(false)

const form = ref({
  quantity: '',
  unitPrice: ''
})

// 사용자가 확인/변경할 수 있는 분리과세 여부. null 이면 선택을 강제한다(서버가 400 을 준다).
const separateTaxation = ref(null)

// 소수 허용 양수. type="number" 는 브라우저마다 소수 처리가 달라 쓰지 않는다.
const DECIMAL_PATTERN = /^\d+(\.\d+)?$/

const hasLot = computed(() => !!(lot.value.buyDate && lot.value.buySeq))
const bondName = computed(() => lot.value.bondName || bondCode.value)

const quantityValue = computed(() =>
  DECIMAL_PATTERN.test(form.value.quantity.trim()) ? Number(form.value.quantity.trim()) : null
)
const unitPriceValue = computed(() =>
  DECIMAL_PATTERN.test(form.value.unitPrice.trim()) ? Number(form.value.unitPrice.trim()) : null
)

// 매도 수량 상한: 주문가능수량 우선, 없으면 잔고수량.
const maxQuantity = computed(() => lot.value.orderableQuantity ?? lot.value.quantity ?? null)

const expectedAmount = computed(() =>
  calcExpectedAmount(quantityValue.value, unitPriceValue.value, faceValueDivisor.value)
)

/** 주문을 막는 이유 (없으면 null). 버튼 비활성화 사유를 그대로 화면에 보여준다. */
const blockReason = computed(() => {
  if (!hasLot.value) {
    return '보유 로트 정보(매수일·매수순번)가 없어 매도할 수 없습니다. 자산 화면에서 다시 들어와 주세요.'
  }
  if (quantityValue.value === null || quantityValue.value <= 0) return '수량을 입력해 주세요.'
  if (maxQuantity.value !== null && quantityValue.value > maxQuantity.value) {
    return `주문 가능 수량(${formatQuantity(maxQuantity.value)})을 초과했습니다.`
  }
  if (unitPriceValue.value === null || unitPriceValue.value <= 0) return '단가를 입력해 주세요.'
  if (separateTaxation.value === null) return '분리과세 여부를 선택해 주세요.'
  return null
})

const canSubmit = computed(() => blockReason.value === null && !submitting.value)

/**
 * 잔고에서 이 로트를 다시 찾아 확정한다.
 * 쿼리 값은 사용자가 URL 을 만질 수 있고 시간이 지나면 낡으므로, 서버 값이 있으면 그쪽이 정답이다.
 */
const loadLotFromBalance = async () => {
  balanceLoading.value = true
  balanceNotice.value = ''
  try {
    const res = await bondApi.getBalance()
    const data = res?.data ?? null
    if (!data) {
      balanceNotice.value = '잔고를 확인할 수 없어 이전 화면에서 받은 정보로 진행합니다.'
      return
    }

    faceValueDivisor.value = data.faceValueDivisor ?? null
    if (data.notice) balanceNotice.value = data.notice

    const holdings = Array.isArray(data.holdings) ? data.holdings : []
    const matched = holdings.find(
      (h) =>
        h.bondCode === bondCode.value &&
        String(h.buyDate ?? '') === String(lot.value.buyDate ?? '') &&
        String(h.buySeq ?? '') === String(lot.value.buySeq ?? '')
    )

    if (matched) {
      lot.value = {
        bondName: matched.bondName || lot.value.bondName,
        buyDate: matched.buyDate,
        buySeq: matched.buySeq,
        quantity: matched.quantity ?? null,
        orderableQuantity: matched.orderableQuantity ?? null,
        buyUnitPrice: matched.buyUnitPrice ?? null,
        buyAmount: matched.buyAmount ?? null,
        maturityDate: matched.maturityDate ?? lot.value.maturityDate,
        separateTaxation: matched.separateTaxation ?? lot.value.separateTaxation
      }
    } else if (holdings.length > 0) {
      balanceNotice.value = '잔고에서 이 매수 로트를 찾지 못했습니다. 이미 매도됐는지 확인해 주세요.'
    }
  } catch (error) {
    logger.debug('채권 잔고 재확인 실패:', error)
    balanceNotice.value = '잔고를 확인할 수 없어 이전 화면에서 받은 정보로 진행합니다.'
  } finally {
    separateTaxation.value = lot.value.separateTaxation
    // 매수 단가를 단가 입력의 시드로 둔다(사용자가 고칠 수 있다).
    if (!form.value.unitPrice && lot.value.buyUnitPrice != null) {
      form.value.unitPrice = String(lot.value.buyUnitPrice)
    }
    balanceLoading.value = false
  }
}

onMounted(loadLotFromBalance)

const setMaxQuantity = () => {
  if (maxQuantity.value !== null) {
    form.value.quantity = String(maxQuantity.value)
  }
}

const openConfirm = () => {
  errorMessage.value = ''
  if (!canSubmit.value) {
    showError(blockReason.value || '주문 정보를 확인해 주세요.')
    return
  }
  showConfirm.value = true
}

const submitSell = async () => {
  // 다이얼로그 표시와 별개의 최종 가드 (연타·키보드 submit 대비)
  if (!canSubmit.value) {
    showConfirm.value = false
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    await bondApi.sell({
      bondCode: bondCode.value,
      bondName: lot.value.bondName || null,
      // 사용자가 입력한 문자열을 그대로 보낸다 — Number 왕복은 소수를 변질시킨다.
      quantity: form.value.quantity.trim(),
      unitPrice: form.value.unitPrice.trim(),
      // 아래 셋이 로트 식별자다. 잔고에서 받은 값을 그대로 되돌려 보낸다.
      buyDate: lot.value.buyDate,
      buySeq: lot.value.buySeq,
      separateTaxation: separateTaxation.value
    })
    showConfirm.value = false
    router.push('/transactions')
  } catch (error) {
    logger.debug('채권 매도 실패:', error)
    // 주문 경로는 degrade 대상이 아니다. 서버 메시지를 그대로 노출한다.
    errorMessage.value =
      error.response?.data?.message || '매도 주문에 실패했습니다. 잠시 후 다시 시도해 주세요.'
    showConfirm.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="bond-sell-screen">
    <AppHeader title="채권 매도" showBack />

    <div class="content">
      <!-- 매도 대상 로트 -->
      <section class="card">
        <h3 class="card-title">매도 대상</h3>
        <p class="card-caption">채권 매도는 종목이 아니라 <strong>매수 로트</strong> 단위입니다</p>

        <div class="rows">
          <div class="row">
            <span class="row-label">종목</span>
            <span class="row-value">{{ bondName }}</span>
          </div>
          <div class="row">
            <span class="row-label">종목코드</span>
            <span class="row-value">{{ bondCode }}</span>
          </div>
          <div class="row">
            <span class="row-label">매수일</span>
            <span class="row-value">{{ formatKisDate(lot.buyDate) }}</span>
          </div>
          <div class="row">
            <span class="row-label">보유 / 주문가능 수량</span>
            <span class="row-value">
              {{ formatQuantity(lot.quantity) }} / {{ formatQuantity(lot.orderableQuantity) }}
            </span>
          </div>
          <div class="row">
            <span class="row-label">매수 단가</span>
            <span class="row-value">{{ formatUnitPrice(lot.buyUnitPrice) }}</span>
          </div>
        </div>

        <p v-if="balanceLoading" class="hint">잔고를 확인하는 중...</p>
        <p v-else-if="balanceNotice" class="warn-box">{{ balanceNotice }}</p>
      </section>

      <!-- 주문 입력 -->
      <section class="card">
        <h3 class="card-title">주문 정보</h3>

        <label class="field">
          <span class="field-label">수량</span>
          <div class="field-row">
            <input
              v-model="form.quantity"
              type="text"
              inputmode="decimal"
              class="field-input"
              placeholder="0"
            />
            <button type="button" class="chip" :disabled="maxQuantity === null" @click="setMaxQuantity">
              최대
            </button>
          </div>
        </label>

        <!-- 단가는 소수를 갖는다. type="number" 는 브라우저별 소수 처리가 달라 쓰지 않는다. -->
        <label class="field">
          <span class="field-label">단가 (소수점 입력 가능)</span>
          <input
            v-model="form.unitPrice"
            type="text"
            inputmode="decimal"
            class="field-input"
            placeholder="예: 9850.5"
          />
        </label>

        <div class="field">
          <span class="field-label">분리과세 여부</span>
          <p class="field-hint">
            보유 정보에서 유도한 값입니다. 과세 방식이 달라지므로 확인 후 진행해 주세요.
          </p>
          <div class="radio-row">
            <button
              type="button"
              :class="['radio-btn', { active: separateTaxation === true }]"
              @click="separateTaxation = true"
            >
              분리과세 (Y)
            </button>
            <button
              type="button"
              :class="['radio-btn', { active: separateTaxation === false }]"
              @click="separateTaxation = false"
            >
              종합과세 (N)
            </button>
          </div>
        </div>

        <!-- 예상 금액은 수량 단위(액면금액/좌수) 미확정 상태의 추정치다. -->
        <div class="expected">
          <div class="expected-row">
            <span class="expected-label">예상 금액</span>
            <span class="expected-value">
              {{ expectedAmount === null ? '—' : `${formatAmount(expectedAmount)}원` }}
            </span>
          </div>
          <p class="expected-note">
            예상 금액은 참고용입니다. 채권 주문 수량 단위가 확정되지 않아 실제 체결 금액과 다를 수 있습니다.
          </p>
        </div>
      </section>

      <p v-if="blockReason" class="block-reason">{{ blockReason }}</p>
      <p v-if="errorMessage" class="error-box">{{ errorMessage }}</p>
    </div>

    <div class="footer-actions">
      <button type="button" class="submit-button" :disabled="!canSubmit" @click="openConfirm">
        {{ submitting ? '주문 처리 중...' : '매도 주문' }}
      </button>
    </div>

    <!-- 주문 확인: 실제 자금이 움직인다. -->
    <div v-if="showConfirm" class="modal-backdrop" @click.self="showConfirm = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h3 class="modal-title">매도 주문을 실행할까요?</h3>
        <div class="rows">
          <div class="row">
            <span class="row-label">종목</span>
            <span class="row-value">{{ bondName }}</span>
          </div>
          <div class="row">
            <span class="row-label">매수 로트</span>
            <span class="row-value">{{ formatKisDate(lot.buyDate) }} (#{{ lot.buySeq }})</span>
          </div>
          <div class="row">
            <span class="row-label">수량</span>
            <span class="row-value">{{ formatQuantity(form.quantity) }}</span>
          </div>
          <div class="row">
            <span class="row-label">단가</span>
            <span class="row-value">{{ formatUnitPrice(form.unitPrice) }}</span>
          </div>
          <div class="row">
            <span class="row-label">예상 금액</span>
            <span class="row-value">
              {{ expectedAmount === null ? '—' : `${formatAmount(expectedAmount)}원` }}
            </span>
          </div>
          <div class="row">
            <span class="row-label">분리과세</span>
            <span class="row-value">{{ separateTaxation ? '분리과세 (Y)' : '종합과세 (N)' }}</span>
          </div>
        </div>
        <p class="modal-note">예상 금액은 참고용이며 실제 체결 금액과 다를 수 있습니다.</p>
        <div class="modal-actions">
          <button type="button" class="modal-btn cancel" @click="showConfirm = false">취소</button>
          <button type="button" class="modal-btn confirm" :disabled="submitting" @click="submitSell">
            매도 실행
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bond-sell-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, var(--canvas-gradient-start) 0%, var(--canvas-gradient-end) 100%);
  padding-bottom: 96px;
}

.bond-sell-screen :deep(.app-header) {
  background: var(--canvas-gradient-start);
  border-bottom: 1px solid var(--canvas-hairline);
}

.content {
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card {
  background: linear-gradient(135deg, var(--canvas-card-start) 0%, var(--canvas-card-end) 100%);
  border-radius: 20px;
  padding: 20px;
  box-shadow: 0 4px 16px var(--canvas-card-shadow);
}

.card-title {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 4px;
}

.card-caption {
  font-size: 11px;
  color: var(--color-text-tertiary);
  margin-bottom: 12px;
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.row-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.row-value {
  font-size: 14px;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  text-align: right;
  word-break: break-all;
}

.field {
  display: block;
  margin-top: 16px;
}

.field-label {
  display: block;
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}

.field-hint {
  font-size: 11px;
  color: var(--color-text-tertiary);
  margin-bottom: 8px;
}

.field-row {
  display: flex;
  gap: 8px;
}

.field-input {
  flex: 1;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: var(--canvas-hairline-faint);
  color: var(--color-text-primary);
  font-size: 16px;
  font-weight: var(--font-weight-semibold);
}

.field-input:focus {
  outline: none;
  border-color: #3B82F6;
}

.chip {
  padding: 0 16px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}

.chip:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.radio-row {
  display: flex;
  gap: 8px;
}

.radio-btn {
  flex: 1;
  padding: 12px;
  border: 1px solid var(--canvas-hairline);
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: var(--font-weight-medium);
  cursor: pointer;
}

.radio-btn.active {
  background: #F59E0B;
  border-color: #F59E0B;
  color: var(--color-text-inverse);
}

.expected {
  margin-top: 16px;
  padding: 12px;
  background: var(--canvas-hairline-faint);
  border-radius: 12px;
}

.expected-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.expected-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.expected-value {
  font-size: 18px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.expected-note {
  margin-top: 6px;
  font-size: 11px;
  color: #F59E0B;
}

.hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.warn-box,
.block-reason {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #F59E0B;
  font-size: 12px;
  font-weight: var(--font-weight-medium);
}

.warn-box {
  margin-top: 12px;
}

.error-box {
  padding: 12px;
  border-radius: 12px;
  background: rgba(239, 68, 68, 0.12);
  color: #EF4444;
  font-size: 13px;
  font-weight: var(--font-weight-medium);
}

.footer-actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px var(--spacing-lg) 20px;
  background: var(--canvas-gradient-end);
  border-top: 1px solid var(--canvas-hairline);
}

.submit-button {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: 14px;
  background: #3B82F6;
  color: #FFFFFF;
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.submit-button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-lg);
  z-index: 100;
}

.modal {
  width: 100%;
  max-width: 360px;
  background: var(--canvas-card-start);
  border-radius: 20px;
  padding: 20px;
}

.modal-title {
  font-size: 16px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: 12px;
}

.modal-note {
  margin-top: 12px;
  font-size: 11px;
  color: #F59E0B;
}

.modal-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.modal-btn {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: var(--font-weight-bold);
  cursor: pointer;
}

.modal-btn.cancel {
  background: var(--canvas-hairline-soft);
  color: var(--color-text-secondary);
}

.modal-btn.confirm {
  background: #3B82F6;
  color: #FFFFFF;
}

.modal-btn.confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>

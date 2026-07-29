<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '@/components/common/AppHeader.vue'
import KisMaintenanceNotice from '@/components/common/KisMaintenanceNotice.vue'
import { userApi, tradingApi, marketAnalysisApi } from '@/services/api'
import { isKisOutageError } from '@/utils/kisStatus'
import toast from '@/utils/toast'
import { logger } from '@/utils/logger'

const router = useRouter()

const botEnabled = ref(false)
const botStatus = ref({
  totalValuation: 0,
  profitAmount: 0,  // 손익금액
  profitPercent: 0,  // 손익률
  totalInvestment: 0,
  currentInvestedAmount: 0  // 현재 투자된 금액 (매입금액 합계)
})

// 보유 종목과 분석 정보를 통합
const botStocks = ref([])

// KIS API 점검/장애 여부 (보유 종목 조회 실패 시 true)
const holdingsKisDown = ref(false)

// 종목 카드 펼침 상태 관리
const expandedStocks = ref(new Set())

// 설정 모달 관련
const showSettingsModal = ref(false)
const settingsBotEnabled = ref(false)
const settingsInvestment = ref('0')
const settingsMarket = ref('domestic')

const formatNumber = (num) => {
  if (!num) return '0'
  return new Intl.NumberFormat('ko-KR').format(num)
}

const handleAmountInput = (e) => {
  const value = e.target.value.replace(/[^0-9]/g, '')
  if (value) {
    settingsInvestment.value = formatNumber(parseInt(value))
  } else {
    settingsInvestment.value = ''
  }
}

const loadTradeConfig = async () => {
  try {
    const response = await userApi.getTradeConfig()
    if (response.success && response.data) {
      botEnabled.value = response.data.isActive || false
      botStatus.value.totalInvestment = response.data.orderAmount || 0
    }
  } catch (error) {
    logger.debug('거래 설정 조회 실패:', error)
    toast.error('거래 설정을 불러올 수 없습니다')
  }
}

const loadHoldings = async () => {
  holdingsKisDown.value = false
  try {
    const response = await tradingApi.getHoldings()
    logger.debug('Holdings API Response:', response)
    if (response.success && response.data) {
      const holdingsData = response.data
      logger.debug('Holdings Data:', holdingsData)

      // 총 평가금액: KIS API에서 제공하는 총 평가금액 (소수점 제거)
      botStatus.value.totalValuation = Math.floor(holdingsData.totalEvaluationAmount || 0)

      // 현재 투자금액: KIS API에서 제공하는 총 매입금액 사용 (소수점 제거)
      botStatus.value.currentInvestedAmount = Math.floor(holdingsData.totalPurchaseAmount || 0)

      // 손익금액: KIS API에서 제공하는 총 손익금액 사용 (소수점 제거)
      botStatus.value.profitAmount = Math.floor(holdingsData.totalProfitLoss || 0)

      // 손익률: KIS API에서 제공하는 총 손익률 사용 (소수점 2자리까지)
      // Backend가 BigDecimal을 Number로 변환하므로 toFixed로 소수점 2자리까지 표시
      const rawProfitRate = holdingsData.totalProfitLossRate || 0
      logger.debug('Raw Profit Rate from API:', rawProfitRate, 'Type:', typeof rawProfitRate)
      botStatus.value.profitPercent = Number(rawProfitRate).toFixed(2)

      logger.debug('Holdings Data:', holdingsData)
      logger.debug('Total Valuation:', botStatus.value.totalValuation)
      logger.debug('Current Invested (Purchase Amount):', botStatus.value.currentInvestedAmount)
      logger.debug('Total Profit Loss:', botStatus.value.profitAmount)
      logger.debug('Profit Percent:', botStatus.value.profitPercent)

      // 보유 종목 데이터 매핑
      botStocks.value = holdingsData.holdings.map((holding) => ({
        symbol: holding.stockCode,
        name: holding.stockName,
        currentPrice: Math.floor(holding.currentPrice || 0),  // 현재 단가 (소수점 제거)
        profitPercent: parseFloat(holding.profitLossRate || 0).toFixed(2),
        purchasePrice: holding.evaluationAmount,  // 평가금액
        profit: holding.profitLoss,  // 평가손익
        quantity: holding.availableQuantity,  // 매도가능수량
        avgPrice: Math.floor(holding.averagePrice || 0),  // 평균단가 (소수점 제거)
        // AI 분석: 초기 로딩 상태로 시작, loadStockAnalyses()에서 실제 데이터로 교체
        aiDecision: null,
        aiRank: null,
        analysis: {
          loading: true,
          hasAnalysis: null,
          headline: '',
          metrics: [],
          analysisDate: null
        }
      }))

      // 보유 종목 렌더링을 막지 않도록 분석 데이터는 별도로 비동기 로딩
      loadStockAnalyses()
    }
  } catch (error) {
    logger.debug('보유 종목 조회 실패:', error)
    if (isKisOutageError(error)) {
      // KIS 점검/장애: 배너·카드로 안내하므로 별도 toast는 생략 (중복 알림 방지)
      holdingsKisDown.value = true
    } else {
      toast.error('보유 종목 정보를 불러올 수 없습니다')
    }
  }
}

// 보유 종목별 AI 분석을 병렬로 조회하여 카드에 채워 넣음
const loadStockAnalyses = async () => {
  await Promise.all(
    botStocks.value.map(async (stock) => {
      try {
        const res = await marketAnalysisApi.getStockAnalysis(stock.symbol)

        if (res && res.success && res.data) {
          stock.analysis = {
            loading: false,
            hasAnalysis: res.data.has_analysis,
            headline: res.data.headline,
            metrics: Array.isArray(res.data.metrics) ? res.data.metrics : [],
            analysisDate: res.data.analysis_date
          }
        } else {
          stock.analysis = {
            loading: false,
            hasAnalysis: false,
            headline: 'AI 분석을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
            metrics: [],
            analysisDate: null
          }
        }
      } catch (error) {
        logger.debug(`AI 분석 조회 실패 (${stock.symbol}):`, error)
        stock.analysis = {
          loading: false,
          hasAnalysis: false,
          headline: 'AI 분석을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
          metrics: [],
          analysisDate: null
        }
      }
    })
  )

  // botStocks.value는 plain object 배열이므로, 배열 참조를 교체해 카드 재렌더링을 보장
  botStocks.value = [...botStocks.value]
}

const openSettings = () => {
  settingsBotEnabled.value = botEnabled.value
  settingsInvestment.value = formatNumber(botStatus.value.totalInvestment)
  settingsMarket.value = 'domestic'
  showSettingsModal.value = true
}

const saveSettings = async () => {
  // Toast 공통 로직: 로딩 메시지는 표시하지 않고, 최종 결과(성공/실패)만 표시
  // - toast.success(): 작업 성공 시
  // - toast.error(): 작업 실패 시
  // - toast.loading()은 사용하지 않음 (사용자 혼란 방지)

  try {
    // 쉼표 제거 후 숫자로 변환
    const numericValue = parseInt(settingsInvestment.value.replace(/,/g, '')) || 0

    logger.debug('저장 요청:', {
      orderAmount: numericValue,
      isActive: settingsBotEnabled.value
    })

    const response = await userApi.updateTradeConfig({
      orderAmount: numericValue,
      maxHoldings: 10,  // 기본값 유지
      orderType: 'market',  // 기본값 유지
      isActive: settingsBotEnabled.value
    })

    logger.debug('API 응답:', response)

    // API 응답 성공 여부 확인
    // 백엔드 응답 구조: { success: boolean, message: string, data: TradeConfigResponse }
    // api.js interceptor가 response.data를 리턴하므로 위 전체 구조를 받음
    if (response && response.success && response.data) {
      // 로컬 상태 업데이트 (응답 데이터 사용)
      botEnabled.value = response.data.isActive
      botStatus.value.totalInvestment = response.data.orderAmount
      showSettingsModal.value = false

      // 최종 성공 메시지만 표시
      toast.success('설정이 저장되었습니다')
    } else {
      // API 응답 실패 또는 유효하지 않은 응답
      logger.debug('API 응답 실패:', response)
      toast.error('설정 저장에 실패했습니다')
    }
  } catch (error) {
    // 네트워크 오류 또는 예외 발생 시
    logger.debug('거래 설정 저장 실패:', error)
    logger.debug('Error details:', {
      message: error.message,
      response: error.response?.data,
      status: error.response?.status
    })

    // 최종 실패 메시지만 표시
    toast.error('설정 저장에 실패했습니다')
  }
}

onMounted(async () => {
  await loadTradeConfig()
  await loadHoldings()
})

const goToNews = (stock) => {
  router.push(`/news?symbol=${stock.symbol}`)
}

const goToTrade = (stock) => {
  router.push(`/trading/${stock.symbol}`)
}

const goToInfo = (stock) => {
  router.push(`/company/${stock.symbol}`)
}

const goToMarketAnalysis = () => {
  router.push('/market-analysis')
}

const toggleStockDetails = (symbol) => {
  if (expandedStocks.value.has(symbol)) {
    expandedStocks.value.delete(symbol)
  } else {
    expandedStocks.value.add(symbol)
  }
  // Trigger reactivity
  expandedStocks.value = new Set(expandedStocks.value)
}

const isStockExpanded = (symbol) => {
  return expandedStocks.value.has(symbol)
}
</script>

<template>
  <div class="bot-screen">
    <AppHeader title="투자 봇" showIcon icon="bot" show-kis-mode />

    <div class="content">
      <!-- KIS 점검 안내 배너 -->
      <KisMaintenanceNotice v-if="holdingsKisDown" variant="banner" />

      <!-- Total Valuation Section -->
      <div class="total-valuation-section">
        <div class="valuation-label">총 평가금액</div>
        <template v-if="!holdingsKisDown">
          <div class="valuation-amount">{{ formatNumber(botStatus.totalValuation) }}원</div>
          <div
            class="valuation-profit"
            :class="botStatus.profitAmount >= 0 ? 'profit-up' : 'profit-down'"
          >
            {{ botStatus.profitAmount >= 0 ? '+' : '' }}{{ formatNumber(botStatus.profitAmount) }}원
            ({{ parseFloat(botStatus.profitPercent) >= 0 ? '+' : '' }}{{ botStatus.profitPercent }}%)
          </div>
        </template>
        <div v-else class="valuation-amount">—</div>
      </div>

      <!-- Bot Avatar & Status -->
      <div class="bot-status">
        <div class="bot-avatar-wrapper">
          <div class="bot-avatar">
            <svg viewBox="0 0 200 200" xmlns="http://www.w3.org/2000/svg">
              <!-- Head/Monitor -->
              <rect x="50" y="35" width="100" height="80" rx="12" fill="#1E293B"/>
              <rect x="55" y="40" width="90" height="70" rx="8" fill="#0F172A"/>

              <!-- Screen Display -->
              <rect x="60" y="45" width="80" height="60" rx="6" fill="#10B981" opacity="0.1"/>

              <!-- Brain Waves Animation -->
              <path d="M 70 65 Q 80 55, 90 65 T 110 65 Q 120 55, 130 65"
                    stroke="#10B981" stroke-width="2" fill="none" opacity="0.8">
                <animate attributeName="d"
                         values="M 70 65 Q 80 55, 90 65 T 110 65 Q 120 55, 130 65;
                                 M 70 65 Q 80 75, 90 65 T 110 65 Q 120 75, 130 65;
                                 M 70 65 Q 80 55, 90 65 T 110 65 Q 120 55, 130 65"
                         dur="2s" repeatCount="indefinite"/>
              </path>
              <path d="M 70 75 Q 80 65, 90 75 T 110 75 Q 120 65, 130 75"
                    stroke="#34D399" stroke-width="2" fill="none" opacity="0.6">
                <animate attributeName="d"
                         values="M 70 75 Q 80 65, 90 75 T 110 75 Q 120 65, 130 75;
                                 M 70 75 Q 80 85, 90 75 T 110 75 Q 120 85, 130 75;
                                 M 70 75 Q 80 65, 90 75 T 110 75 Q 120 65, 130 75"
                         dur="2.5s" repeatCount="indefinite"/>
              </path>

              <!-- AI Processor Icon -->
              <circle cx="100" cy="90" r="10" fill="#0F172A" stroke="#10B981" stroke-width="2"/>
              <circle cx="100" cy="90" r="5" fill="#10B981">
                <animate attributeName="r" values="5;7;5" dur="1.5s" repeatCount="indefinite"/>
                <animate attributeName="opacity" values="1;0.5;1" dur="1.5s" repeatCount="indefinite"/>
              </circle>

              <!-- Connection Lines -->
              <line x1="90" y1="90" x2="75" y2="75" stroke="#10B981" stroke-width="1.5" opacity="0.4"/>
              <line x1="110" y1="90" x2="125" y2="75" stroke="#10B981" stroke-width="1.5" opacity="0.4"/>
              <line x1="90" y1="90" x2="75" y2="105" stroke="#10B981" stroke-width="1.5" opacity="0.4"/>
              <line x1="110" y1="90" x2="125" y2="105" stroke="#10B981" stroke-width="1.5" opacity="0.4"/>

              <!-- Data Points -->
              <circle cx="75" cy="75" r="3" fill="#34D399">
                <animate attributeName="opacity" values="0.3;1;0.3" dur="2s" repeatCount="indefinite"/>
              </circle>
              <circle cx="125" cy="75" r="3" fill="#34D399">
                <animate attributeName="opacity" values="1;0.3;1" dur="2s" repeatCount="indefinite"/>
              </circle>
              <circle cx="75" cy="105" r="3" fill="#34D399">
                <animate attributeName="opacity" values="0.5;1;0.5" dur="2s" repeatCount="indefinite"/>
              </circle>
              <circle cx="125" cy="105" r="3" fill="#34D399">
                <animate attributeName="opacity" values="1;0.5;1" dur="2s" repeatCount="indefinite"/>
              </circle>

              <!-- Antenna -->
              <line x1="100" y1="35" x2="100" y2="20" stroke="#10B981" stroke-width="3"/>
              <circle cx="100" cy="17" r="6" fill="#F59E0B" stroke="#10B981" stroke-width="2">
                <animate attributeName="fill" values="#F59E0B;#FCD34D;#F59E0B" dur="1s" repeatCount="indefinite"/>
              </circle>

              <!-- Body -->
              <rect x="60" y="120" width="80" height="55" rx="10" fill="#1E293B"/>
              <rect x="65" y="125" width="70" height="45" rx="8" fill="#334155"/>

              <!-- Chest Display Panel -->
              <rect x="80" y="135" width="40" height="25" rx="4" fill="#0F172A"/>
              <text x="100" y="150" font-family="monospace" font-size="12" fill="#10B981" text-anchor="middle">AI</text>
              <circle cx="90" cy="155" r="2" fill="#10B981">
                <animate attributeName="opacity" values="1;0.3;1" dur="0.5s" repeatCount="indefinite"/>
              </circle>
              <circle cx="100" cy="155" r="2" fill="#10B981">
                <animate attributeName="opacity" values="0.3;1;0.3" dur="0.5s" repeatCount="indefinite"/>
              </circle>
              <circle cx="110" cy="155" r="2" fill="#10B981">
                <animate attributeName="opacity" values="1;0.3;1" dur="0.5s" repeatCount="indefinite"/>
              </circle>

              <!-- Arms -->
              <rect x="35" y="125" width="20" height="40" rx="6" fill="#1E293B"/>
              <rect x="145" y="125" width="20" height="40" rx="6" fill="#1E293B"/>

              <!-- Hands -->
              <circle cx="45" cy="170" r="10" fill="#334155"/>
              <circle cx="155" cy="170" r="10" fill="#334155"/>
            </svg>
          </div>
        </div>

        <!-- Status Badge -->
        <div class="bot-status-badge" :class="{ active: botEnabled }">
          <div class="status-indicator"></div>
          <span class="status-text">{{ botEnabled ? '트레이딩 중' : '쉬는 중' }}</span>
        </div>
      </div>

      <!-- Investment Section -->
      <div class="investment-section">
        <div class="investment-info">
          <div class="investment-main">
            <div class="investment-item">
              <span class="investment-label">최대 투자 금액</span>
              <span class="investment-value">{{ formatNumber(botStatus.totalInvestment) }}원</span>
            </div>
            <div class="investment-divider"></div>
            <div class="investment-item">
              <span class="investment-label">현재 투자금액</span>
              <span class="investment-value">
                {{ holdingsKisDown ? '—' : formatNumber(botStatus.currentInvestedAmount) + '원' }}
              </span>
            </div>
          </div>
        </div>
        <button class="settings-btn" @click="openSettings">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M19.4 15C19.2669 15.3016 19.2272 15.6362 19.286 15.9606C19.3448 16.285 19.4995 16.5843 19.73 16.82L19.79 16.88C19.976 17.0657 20.1235 17.2863 20.2241 17.5291C20.3248 17.7719 20.3766 18.0322 20.3766 18.295C20.3766 18.5578 20.3248 18.8181 20.2241 19.0609C20.1235 19.3037 19.976 19.5243 19.79 19.71C19.6043 19.896 19.3837 20.0435 19.1409 20.1441C18.8981 20.2448 18.6378 20.2966 18.375 20.2966C18.1122 20.2966 17.8519 20.2448 17.6091 20.1441C17.3663 20.0435 17.1457 19.896 16.96 19.71L16.9 19.65C16.6643 19.4195 16.365 19.2648 16.0406 19.206C15.7162 19.1472 15.3816 19.1869 15.08 19.32C14.7842 19.4468 14.532 19.6572 14.3543 19.9255C14.1766 20.1938 14.0813 20.5082 14.08 20.83V21C14.08 21.5304 13.8693 22.0391 13.4942 22.4142C13.1191 22.7893 12.6104 23 12.08 23C11.5496 23 11.0409 22.7893 10.6658 22.4142C10.2907 22.0391 10.08 21.5304 10.08 21V20.91C10.0723 20.579 9.96512 20.258 9.77251 19.9887C9.5799 19.7194 9.31074 19.5143 9 19.4C8.69838 19.2669 8.36381 19.2272 8.03941 19.286C7.71502 19.3448 7.41568 19.4995 7.18 19.73L7.12 19.79C6.93425 19.976 6.71368 20.1235 6.47088 20.2241C6.22808 20.3248 5.96783 20.3766 5.705 20.3766C5.44217 20.3766 5.18192 20.3248 4.93912 20.2241C4.69632 20.1235 4.47575 19.976 4.29 19.79C4.10405 19.6043 3.95653 19.3837 3.85588 19.1409C3.75523 18.8981 3.70343 18.6378 3.70343 18.375C3.70343 18.1122 3.75523 17.8519 3.85588 17.6091C3.95653 17.3663 4.10405 17.1457 4.29 16.96L4.35 16.9C4.58054 16.6643 4.73519 16.365 4.794 16.0406C4.85282 15.7162 4.81312 15.3816 4.68 15.08C4.55324 14.7842 4.34276 14.532 4.07447 14.3543C3.80618 14.1766 3.49179 14.0813 3.17 14.08H3C2.46957 14.08 1.96086 13.8693 1.58579 13.4942C1.21071 13.1191 1 12.6104 1 12.08C1 11.5496 1.21071 11.0409 1.58579 10.6658C1.96086 10.2907 2.46957 10.08 3 10.08H3.09C3.42099 10.0723 3.742 9.96512 4.0113 9.77251C4.28059 9.5799 4.48572 9.31074 4.6 9C4.73312 8.69838 4.77282 8.36381 4.714 8.03941C4.65519 7.71502 4.50054 7.41568 4.27 7.18L4.21 7.12C4.02405 6.93425 3.87653 6.71368 3.77588 6.47088C3.67523 6.22808 3.62343 5.96783 3.62343 5.705C3.62343 5.44217 3.67523 5.18192 3.77588 4.93912C3.87653 4.69632 4.02405 4.47575 4.21 4.29C4.39575 4.10405 4.61632 3.95653 4.85912 3.85588C5.10192 3.75523 5.36217 3.70343 5.625 3.70343C5.88783 3.70343 6.14808 3.75523 6.39088 3.85588C6.63368 3.95653 6.85425 4.10405 7.04 4.29L7.1 4.35C7.33568 4.58054 7.63502 4.73519 7.95941 4.794C8.28381 4.85282 8.61838 4.81312 8.92 4.68H9C9.29577 4.55324 9.54802 4.34276 9.72569 4.07447C9.90337 3.80618 9.99872 3.49179 10 3.17V3C10 2.46957 10.2107 1.96086 10.5858 1.58579C10.9609 1.21071 11.4696 1 12 1C12.5304 1 13.0391 1.21071 13.4142 1.58579C13.7893 1.96086 14 2.46957 14 3V3.09C14.0013 3.41179 14.0966 3.72618 14.2743 3.99447C14.452 4.26276 14.7042 4.47324 15 4.6C15.3016 4.73312 15.6362 4.77282 15.9606 4.714C16.285 4.65519 16.5843 4.50054 16.82 4.27L16.88 4.21C17.0657 4.02405 17.2863 3.87653 17.5291 3.77588C17.7719 3.67523 18.0322 3.62343 18.295 3.62343C18.5578 3.62343 18.8181 3.67523 19.0609 3.77588C19.3037 3.87653 19.5243 4.02405 19.71 4.21C19.896 4.39575 20.0435 4.61632 20.1441 4.85912C20.2448 5.10192 20.2966 5.36217 20.2966 5.625C20.2966 5.88783 20.2448 6.14808 20.1441 6.39088C20.0435 6.63368 19.896 6.85425 19.71 7.04L19.65 7.1C19.4195 7.33568 19.2648 7.63502 19.206 7.95941C19.1472 8.28381 19.1869 8.61838 19.32 8.92V9C19.4468 9.29577 19.6572 9.54802 19.9255 9.72569C20.1938 9.90337 20.5082 9.99872 20.83 10H21C21.5304 10 22.0391 10.2107 22.4142 10.5858C22.7893 10.9609 23 11.4696 23 12C23 12.5304 22.7893 13.0391 22.4142 13.4142C22.0391 13.7893 21.5304 14 21 14H20.91C20.5882 14.0013 20.2738 14.0966 20.0055 14.2743C19.7372 14.452 19.5268 14.7042 19.4 15Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>

      <!-- AI Market Analysis Button -->
      <button class="market-analysis-btn" @click="goToMarketAnalysis">
        <div class="btn-icon">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/>
            <path d="M12 1v4M12 19v4M4.22 4.22l2.83 2.83M16.95 16.95l2.83 2.83M1 12h4M19 12h4M4.22 19.78l2.83-2.83M16.95 7.05l2.83-2.83" stroke="currentColor" stroke-width="2"/>
          </svg>
        </div>
        <div class="btn-content">
          <div class="btn-title">AI 시장 분석</div>
          <div class="btn-subtitle">30종목 히트맵 · Gemini TOP3 · 시장 감성</div>
        </div>
        <div class="btn-arrow">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M9 18l6-6-6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
      </button>

      <!-- Divider -->
      <div class="section-divider"></div>

      <!-- Bot Stocks Section -->
      <div class="bot-stocks-section">
        <h3 class="section-title">보유 종목 및 분석</h3>

        <KisMaintenanceNotice v-if="holdingsKisDown" variant="card" />

        <div v-for="stock in botStocks" :key="stock.symbol" class="stock-analysis-card">
          <!-- Stock Info Header (Clickable) -->
          <div class="stock-header" @click="toggleStockDetails(stock.symbol)">
            <div class="stock-logo">
              <img v-if="stock.logo" :src="stock.logo" :alt="stock.name" />
              <div v-else class="logo-placeholder">{{ stock.symbol?.charAt(0) }}</div>
            </div>
            <div class="stock-info">
              <div class="stock-name-row">
                <span class="stock-name">{{ stock.name }}</span>
                <span class="stock-symbol">{{ stock.symbol }}</span>
              </div>
              <div class="stock-price-row">
                <span class="current-price">₩{{ formatNumber(stock.purchasePrice) }}</span>
                <span class="profit-badge" :class="stock.profitPercent >= 0 ? 'positive' : 'negative'">
                  {{ stock.profitPercent >= 0 ? '+' : '' }}{{ stock.profitPercent }}%
                </span>
              </div>
            </div>
            <!-- Expand/Collapse Icon -->
            <div class="expand-icon" :class="{ expanded: isStockExpanded(stock.symbol) }">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M19 9l-7 7-7-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
          </div>

          <!-- Stock Details (Collapsible) -->
          <transition name="slide-fade">
            <div v-if="isStockExpanded(stock.symbol)" class="stock-details">
              <div class="detail-item">
                <span class="detail-label">현재 단가</span>
                <span class="detail-value">{{ formatNumber(stock.currentPrice) }}원</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">평가손익</span>
                <span class="detail-value profit" :class="stock.profit >= 0 ? 'positive' : 'negative'">
                  {{ stock.profit >= 0 ? '+' : '' }}{{ formatNumber(stock.profit) }}원
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">매도 가능 수량</span>
                <span class="detail-value">{{ formatNumber(stock.quantity) }}주</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">평균단가</span>
                <span class="detail-value">{{ formatNumber(stock.avgPrice) }}원</span>
              </div>
            </div>
          </transition>

          <!-- Analysis Section (Always Visible) -->
          <div class="analysis-section">
            <div class="analysis-header">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" stroke="#F59E0B" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span class="analysis-title">AI 분석</span>
              <span v-if="stock.analysis.analysisDate" class="analysis-date">
                ({{ stock.analysis.analysisDate }} 기준)
              </span>
            </div>
            <div v-if="stock.analysis.loading" class="analysis-loading">
              AI 분석 불러오는 중...
            </div>
            <template v-else>
              <p class="analysis-headline">{{ stock.analysis.headline }}</p>
              <div v-if="stock.analysis.metrics.length > 0" class="metric-table">
                <div
                  v-for="(metric, index) in stock.analysis.metrics"
                  :key="index"
                  class="metric-row"
                >
                  <span class="metric-label">{{ metric.label }}</span>
                  <span class="metric-value" :class="`tone-${metric.tone}`">{{ metric.value }}</span>
                </div>
              </div>
            </template>
          </div>

          <!-- Action Buttons -->
          <div class="action-buttons">
            <button class="action-btn news-btn" @click="goToNews(stock)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h10a2 2 0 012 2v1m2 13a2 2 0 01-2-2V7m2 13a2 2 0 002-2V9a2 2 0 00-2-2h-2m-4-3H9M7 16h6M7 8h6v4H7V8z" stroke="currentColor" stroke-width="2"/>
              </svg>
              뉴스
            </button>
            <button class="action-btn trade-btn" @click="goToTrade(stock)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              거래
            </button>
            <button class="action-btn info-btn" @click="goToInfo(stock)">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              정보
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Spacer for bottom nav -->
    <div class="bottom-spacer"></div>

    <!-- Settings Modal -->
    <van-popup
      v-model:show="showSettingsModal"
      round
      position="center"
      :style="{ background: '#1F2937', padding: '24px', width: '90%', maxWidth: '400px' }"
    >
      <div class="settings-modal">
        <h3 class="modal-title">봇 설정</h3>

        <!-- Bot Enabled Toggle -->
        <div class="setting-item-toggle">
          <div class="toggle-header">
            <span class="setting-label">봇 활성화</span>
            <label class="toggle-wrapper">
              <input type="checkbox" v-model="settingsBotEnabled" />
              <span class="toggle-slider"></span>
            </label>
          </div>
          <p class="disclaimer-text">
            ⚠️ 투자로 인한 손실이 발생할 수 있으며, 모든 투자 책임은 본인에게 있습니다.
          </p>
        </div>

        <!-- Investment Amount -->
        <div class="setting-item">
          <label class="setting-label">최대 투자 금액</label>
          <div class="input-wrapper">
            <input
              type="text"
              class="amount-input"
              :value="settingsInvestment"
              @input="handleAmountInput"
              placeholder="0"
            />
            <span class="input-unit">원</span>
          </div>
        </div>

        <!-- Market Selection -->
        <div class="setting-item">
          <span class="setting-label">시장 선택</span>
          <div class="market-buttons">
            <button
              :class="['market-btn', { active: settingsMarket === 'domestic' }]"
              @click="settingsMarket = 'domestic'"
            >
              국내
            </button>
            <button
              :class="['market-btn', { active: settingsMarket === 'overseas', disabled: true }]"
              disabled
            >
              해외
            </button>
          </div>
        </div>

        <!-- Save Button -->
        <button class="save-btn" @click="saveSettings">
          저장
        </button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.bot-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0F172A 0%, #1E293B 100%);
  padding-bottom: var(--bottom-nav-height);
}

/* Header Override */
.bot-screen :deep(.app-header) {
  background: #0F172A;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.content {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
}

.bot-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.bot-avatar-wrapper {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-md);
}

.bot-avatar {
  width: 120px;
  height: 120px;
  border-radius: var(--radius-xl);
  overflow: hidden;
  background: var(--color-bg-highlight);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.bot-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bot-status-badge {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-md);
  background: var(--color-bg-secondary);
  border-radius: var(--radius-full);
  border: 1px solid var(--color-border);
  transition: all 0.3s ease;
}

.bot-status-badge.active {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(16, 185, 129, 0.25) 100%);
  border-color: var(--color-stock-up);
}

.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  transition: all 0.3s ease;
}

.bot-status-badge.active .status-indicator {
  background: var(--color-stock-up);
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.2);
  }
}

.status-text {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  transition: color 0.3s ease;
}

.bot-status-badge.active .status-text {
  color: var(--color-stock-up);
}

.status-info {
  text-align: center;
}

.status-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.status-value {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  justify-content: center;
}

.amount {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  transition: color 0.3s ease;
}

.amount.profit-up {
  color: var(--color-stock-up);
}

.amount.profit-down {
  color: var(--color-stock-down);
}

.percent {
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  transition: color 0.3s ease;
}

.percent.profit-up {
  color: var(--color-stock-up);
}

.percent.profit-down {
  color: var(--color-stock-down);
}

.total-valuation-section {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  margin-top: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}

.valuation-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

.valuation-amount {
  font-size: 24px;
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  letter-spacing: -0.5px;
}

.valuation-profit {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
}

.valuation-profit.profit-up {
  color: var(--color-stock-up);
}

.valuation-profit.profit-down {
  color: var(--color-stock-down);
}

.investment-section {
  background: var(--color-bg-secondary);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.investment-info {
  flex: 1;
}

.investment-main {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--spacing-lg);
}

.investment-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}

.investment-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.1);
}

.investment-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.investment-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.settings-btn {
  padding: var(--spacing-sm);
  background: var(--color-bg-tertiary);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.settings-btn:hover {
  background: var(--color-bg-highlight);
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.settings-btn:active {
  transform: scale(0.95);
}

.section-divider {
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--color-border), transparent);
  margin: var(--spacing-xl) 0;
}

.section-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: var(--spacing-md);
}

/* Bot Stocks Section */
.bot-stocks-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.stock-analysis-card {
  background: linear-gradient(135deg, #1E293B 0%, #334155 100%);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

/* Stock Header */
.stock-header {
  display: flex;
  gap: var(--spacing-md);
  align-items: center;
  padding: var(--spacing-sm);
  margin: calc(var(--spacing-sm) * -1);
  margin-bottom: var(--spacing-md);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.2s ease;
  position: relative;
}

.stock-header:hover {
  background: rgba(255, 255, 255, 0.05);
}

.stock-header:active {
  background: rgba(255, 255, 255, 0.08);
}

.stock-logo {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg-secondary);
  flex-shrink: 0;
}

.stock-logo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.logo-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  color: white;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.stock-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.stock-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stock-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.stock-symbol {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

.stock-price-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.current-price {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.profit-badge {
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

.profit-badge.positive {
  background: rgba(16, 185, 129, 0.2);
  color: var(--color-stock-up);
}

.profit-badge.negative {
  background: rgba(239, 68, 68, 0.2);
  color: var(--color-stock-down);
}

/* Expand Icon */
.expand-icon {
  margin-left: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-tertiary);
  transition: transform 0.3s ease, color 0.2s ease;
}

.expand-icon:hover {
  color: var(--color-text-secondary);
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.expand-icon svg {
  width: 20px;
  height: 20px;
}

/* Stock Details */
.stock-details {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--radius-md);
}

.detail-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxs);
}

.detail-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.detail-value {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.detail-value.profit.positive {
  color: var(--color-stock-up);
}

.detail-value.profit.negative {
  color: var(--color-stock-down);
}


/* Analysis Section */
.analysis-section {
  background: rgba(245, 158, 11, 0.1);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
  border: 1px solid rgba(245, 158, 11, 0.2);
}

.analysis-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-md);
}

.analysis-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: #F59E0B;
}

.analysis-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

.analysis-headline {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: 1.6;
  margin: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.metric-table {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--spacing-xs) var(--spacing-md);
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid rgba(245, 158, 11, 0.2);
}

.metric-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing-sm);
  min-width: 0;
}

.metric-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.metric-value {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  text-align: right;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.metric-value.tone-positive {
  color: var(--color-stock-up);
}

.metric-value.tone-negative {
  color: var(--color-stock-down);
}

.metric-value.tone-neutral {
  color: var(--color-text-secondary);
}

.analysis-loading {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  font-weight: var(--font-weight-medium);
}

/* Action Buttons */
.action-buttons {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm);
  background: var(--color-bg-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover {
  background: var(--color-bg-highlight);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.action-btn:active {
  transform: scale(0.98);
}

.bottom-spacer {
  height: var(--bottom-nav-height);
}

/* Settings Modal */
.settings-modal {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.modal-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 var(--spacing-md) 0;
  text-align: center;
}

.setting-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.setting-item-toggle {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: var(--radius-lg);
}

.toggle-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.disclaimer-text {
  font-size: var(--font-size-xs);
  color: #FCA5A5;
  line-height: 1.5;
  margin: 0;
  padding-top: var(--spacing-xs);
  border-top: 1px solid rgba(239, 68, 68, 0.2);
}

.setting-label {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.toggle-wrapper {
  position: relative;
  width: 50px;
  height: 28px;
}

.toggle-wrapper input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--color-bg-tertiary);
  border-radius: var(--radius-full);
  transition: 0.3s;
}

.toggle-slider::before {
  position: absolute;
  content: "";
  height: 22px;
  width: 22px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  border-radius: 50%;
  transition: 0.3s;
}

.toggle-wrapper input:checked + .toggle-slider {
  background-color: var(--color-stock-up);
}

.toggle-wrapper input:checked + .toggle-slider::before {
  transform: translateX(22px);
}

.input-wrapper {
  position: relative;
  background: var(--color-bg-secondary);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  border: 2px solid var(--color-border);
  transition: border-color 0.2s;
}

.input-wrapper:focus-within {
  border-color: #10B981;
}

.amount-input {
  width: 100%;
  background: transparent;
  border: none;
  outline: none;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  text-align: right;
  padding-right: 40px;
}

.amount-input::placeholder {
  color: var(--color-text-tertiary);
}

.input-unit {
  position: absolute;
  right: var(--spacing-md);
  top: 50%;
  transform: translateY(-50%);
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-semibold);
}

.market-buttons {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-sm);
}

.market-btn {
  padding: var(--spacing-md);
  background: var(--color-bg-secondary);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.market-btn.active {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(16, 185, 129, 0.25) 100%);
  border-color: var(--color-stock-up);
  color: var(--color-stock-up);
}

.market-btn.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.market-btn:not(.disabled):hover {
  border-color: var(--color-stock-up);
}

.market-btn:not(.disabled):active {
  transform: scale(0.98);
}

.save-btn {
  width: 100%;
  padding: var(--spacing-lg);
  background: linear-gradient(135deg, #10B981 0%, #059669 100%);
  border: none;
  border-radius: var(--radius-lg);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: white;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.3);
  margin-top: var(--spacing-md);
}

.save-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(16, 185, 129, 0.4);
}

.save-btn:active {
  transform: translateY(0);
}

/* Market Analysis Button */
.market-analysis-btn {
  width: 100%;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.15) 0%, rgba(124, 58, 237, 0.15) 100%);
  border: 1px solid rgba(167, 139, 250, 0.3);
  border-radius: var(--radius-xl);
  padding: var(--spacing-lg);
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  cursor: pointer;
  transition: all 0.2s ease;
  margin-bottom: var(--spacing-lg);
}

.market-analysis-btn:hover {
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.25) 0%, rgba(124, 58, 237, 0.25) 100%);
  border-color: var(--color-primary);
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(167, 139, 250, 0.2);
}

.market-analysis-btn:active {
  transform: translateY(0);
}

.btn-icon {
  width: 40px;
  height: 40px;
  background: linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: white;
}

.btn-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: left;
}

.btn-title {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.btn-subtitle {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.btn-arrow {
  color: var(--color-primary);
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

/* Transition Animations */
.slide-fade-enter-active {
  transition: all 0.3s ease-out;
}

.slide-fade-leave-active {
  transition: all 0.3s ease-in;
}

.slide-fade-enter-from {
  transform: translateY(-10px);
  opacity: 0;
}

.slide-fade-leave-to {
  transform: translateY(-10px);
  opacity: 0;
}
</style>

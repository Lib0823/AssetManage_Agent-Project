import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/services/api'
import { getToken, setTokens, clearTokens } from '@/utils/tokenStorage'

export const useAuthStore = defineStore('auth', () => {
  // Registration multi-step data
  const registrationData = ref({
    // Step 1: Personal information
    step1: {
      id: '',
      password: '',
      passwordConfirm: '',
      email: '',
      name: '',
      phone: '',
      birthDate: ''
    },
    // Step 2: Financial information
    step2: {
      stockInvestment: false,
      kisAccount: null
    },
    // Validation states
    validation: {
      isIdChecked: false,
      isIdAvailable: false,
      isEmailChecked: false,
      isEmailAvailable: false,
      isPhoneVerified: true // 임시 우회: 기본값 true
    }
  })

  // User authentication state
  const user = ref(null)
  const accessToken = ref(null)
  const refreshToken = ref(null)
  // KIS 계좌 모드: 'REAL'(실전) | 'MOCK'(모의) | null(미등록/미조회). 헤더 배지·실시간 게이트에 사용.
  const accountMode = ref(null)

  // Actions
  function saveStep1Data(data) {
    registrationData.value.step1 = {
      id: data.id,
      password: data.password,
      passwordConfirm: data.passwordConfirm,
      email: data.email || '',
      name: data.name,
      phone: data.phone,
      birthDate: data.birthDate
    }
  }

  function saveStep2Data(data) {
    registrationData.value.step2 = {
      stockInvestment: data.stockInvestment,
      kisAccount: data.kisAccount
    }
  }

  function setIdCheckResult(available) {
    registrationData.value.validation.isIdChecked = true
    registrationData.value.validation.isIdAvailable = available
  }

  function setEmailCheckResult(available) {
    registrationData.value.validation.isEmailChecked = true
    registrationData.value.validation.isEmailAvailable = available
  }

  function setPhoneVerified(verified) {
    registrationData.value.validation.isPhoneVerified = verified
  }

  function clearRegistrationData() {
    registrationData.value = {
      step1: {
        id: '',
        password: '',
        passwordConfirm: '',
        email: '',
        name: '',
        phone: '',
        birthDate: ''
      },
      step2: {
        stockInvestment: false,
        kisAccount: null
      },
      validation: {
        isIdChecked: false,
        isIdAvailable: false,
        isEmailChecked: false,
        isEmailAvailable: false,
        isPhoneVerified: true
      }
    }
  }

  function setAuthData(data) {
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    user.value = data.user

    // 자동 로그인 설정에 따라 localStorage(영구) 또는 sessionStorage(탭 종료 시 소멸)에 저장.
    setTokens({
      accessToken: data.accessToken,
      refreshToken: data.refreshToken,
      user: JSON.stringify(data.user)
    })
  }

  // KIS 계좌 모드 설정 (로그인 시 조회·프로필 저장 시 갱신). 토큰과 같은 저장소에 유지.
  function setAccountMode(mode) {
    accountMode.value = mode || null
    if (mode) {
      setTokens({ accountMode: mode })
    } else {
      localStorage.removeItem('accountMode')
      sessionStorage.removeItem('accountMode')
    }
  }

  function clearAuthData() {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    accountMode.value = null

    // 인증 관련 키만 지운다 (uiSettings 등 다른 localStorage 항목은 보존).
    clearTokens()
  }

  function loadAuthDataFromStorage() {
    const storedAccessToken = getToken('accessToken')
    const storedRefreshToken = getToken('refreshToken')
    const storedUser = getToken('user')

    if (storedAccessToken && storedRefreshToken && storedUser) {
      accessToken.value = storedAccessToken
      refreshToken.value = storedRefreshToken
      user.value = JSON.parse(storedUser)
      accountMode.value = getToken('accountMode') || null
      return true
    }
    return false
  }

  async function logout() {
    try {
      const token = refreshToken.value
      if (token) {
        await authApi.logout(token)
      }
    } catch (error) {
      console.error('Logout error:', error)
      // 에러가 발생해도 로컬 데이터는 삭제
    } finally {
      clearAuthData()
    }
  }

  // Getters
  function hasStep1Data() {
    const step1 = registrationData.value.step1
    return step1.id && step1.password && step1.name && step1.phone && step1.birthDate
  }

  function isAuthenticated() {
    return !!accessToken.value && !!user.value
  }

  return {
    // State
    registrationData,
    user,
    accessToken,
    refreshToken,
    accountMode,

    // Actions
    saveStep1Data,
    saveStep2Data,
    setIdCheckResult,
    setEmailCheckResult,
    setPhoneVerified,
    clearRegistrationData,
    setAuthData,
    setAccountMode,
    clearAuthData,
    loadAuthDataFromStorage,
    logout,

    // Getters
    hasStep1Data,
    isAuthenticated
  }
})

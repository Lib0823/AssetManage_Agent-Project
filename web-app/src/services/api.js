import axios from 'axios'
import { getToken, setTokens, clearTokens } from '@/utils/tokenStorage'
import { useAuthStore } from '@/stores/auth'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:7070/api'

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// Request interceptor
api.interceptors.request.use(
  (config) => {
    const token = getToken('accessToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Token refresh state
let isRefreshing = false
let refreshSubscribers = []

// Response interceptor with automatic token refresh
api.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    const originalRequest = error.config

    // 공개 인증 엔드포인트는 인터셉터 처리 제외 (401을 그대로 호출부에 반환).
    // webauthn login/*은 비로그인 상태에서 호출되므로 refresh 대상이 아니다 — 여기서
    // 걸러내지 않으면 인증 실패 401이 강제 페이지 리로드로 이어진다.
    if (originalRequest.url?.includes('/auth/login') ||
        originalRequest.url?.includes('/auth/register') ||
        originalRequest.url?.includes('/auth/reset-password') ||
        originalRequest.url?.includes('/auth/webauthn/login/')) {
      return Promise.reject(error)
    }

    // 401 에러이고, 아직 재시도하지 않은 요청인 경우
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true

      // 이미 refresh 중이면 대기
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          refreshSubscribers.push({
            resolve: (token) => {
              originalRequest.headers.Authorization = `Bearer ${token}`
              resolve(api(originalRequest))
            },
            reject
          })
        })
      }

      isRefreshing = true

      try {
        const refreshToken = getToken('refreshToken')
        if (!refreshToken) {
          throw new Error('No refresh token')
        }

        // Refresh token으로 새 access token 요청
        const response = await axios.post(`${API_BASE_URL}/auth/refresh`, {
          refreshToken: refreshToken
        })

        const newAccessToken = response.data.data.accessToken

        // 새 토큰 저장 (기존 저장소 그대로 — 자동 로그인 설정에 맞춰 유지)
        setTokens({ accessToken: newAccessToken })
        // Pinia 상태도 같이 갱신해야 저장소와 store가 어긋나지 않는다.
        useAuthStore().accessToken = newAccessToken

        // 대기 중인 요청들에 새 토큰 전달
        const pending = refreshSubscribers
        refreshSubscribers = []
        pending.forEach(({ resolve }) => resolve(newAccessToken))

        // 원래 요청 재시도
        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`
        return api(originalRequest)
      } catch (refreshError) {
        // Refresh 실패 시 로그아웃
        console.error('Token refresh failed:', refreshError)
        // 대기 큐를 비워 pending 프로미스를 매달아 두지 않는다 (리다이렉트는 비동기라
        // 언로드 전까지 큐가 살아 있고, 언로드가 취소되면 영구 pending이 된다).
        const pending = refreshSubscribers
        refreshSubscribers = []
        pending.forEach(({ reject }) => reject(refreshError))
        clearTokens()
        window.location.href = '/login'
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)

// Auth API
export const authApi = {
  login: (credentials) => api.post('/auth/login', credentials),
  register: (userData) => api.post('/auth/register', userData),
  resetPassword: (data) => api.post('/auth/reset-password', data),
  checkUsername: (username) => api.get('/auth/check-username', { params: { username } }),
  checkEmail: (email) => api.get('/auth/check-email', { params: { email } }),
  logout: (refreshToken) => api.post('/auth/logout', { refreshToken }),
  validateKisAccount: (kisData) => api.post('/auth/validate-kis-account', kisData)
}

// WebAuthn (생체 로그인/패스키) API
// register/* 는 JWT 필요(로그인 상태에서 기기 등록), login/* 은 공개(usernameless)
export const webauthnApi = {
  registerStart: () => api.post('/auth/webauthn/register/start'),
  registerFinish: (data) => api.post('/auth/webauthn/register/finish', data),
  loginStart: () => api.post('/auth/webauthn/login/start'),
  loginFinish: (data) => api.post('/auth/webauthn/login/finish', data)
}

// User API
export const userApi = {
  getProfile: () => api.get('/users/me'),
  updateProfile: (data) => api.put('/users/me', data),
  getSettings: () => api.get('/users/settings'),
  updateSettings: (data) => api.put('/users/settings', data),
  deleteAccount: () => api.delete('/users/me'),
  getKisAccount: () => api.get('/users/kis-account'),
  updateKisAccount: (data) => api.put('/users/kis-account', data),
  getTradeConfig: () => api.get('/users/trade-config'),
  updateTradeConfig: (data) => api.put('/users/trade-config', data)
}

// Asset API
export const assetApi = {
  getHoldings: () => api.get('/assets/holdings'),
  getBalance: () => api.get('/assets/balance'),
  // 총자산 일별 스냅샷 기록/조회 (자산 추이 라인차트용)
  recordSnapshot: (totalAsset) => api.post('/assets/snapshot', { totalAsset }),
  getHistory: (days = 30) => api.get('/assets/history', { params: { days } })
}

// Trading API
export const tradingApi = {
  buy: (order) => api.post('/trading/buy', order),
  sell: (order) => api.post('/trading/sell', order),
  getHistory: (config) => api.get('/trading/history', config),
  getRecentTrades: (config) => api.get('/trading/recent', config),
  getHoldings: () => api.get('/trading/holdings'),
  getPendingOrders: () => api.get('/trading/pending-orders'),
  getOrderable: (stockCode, price) => api.get('/trading/orderable', { params: { stockCode, price } }),
  // 예약주문 (국내 실전 계좌 전용 — KIS 모의 미지원)
  getReservedOrders: () => api.get('/trading/reserved-orders'),
  placeReservedOrder: (body) => api.post('/trading/reserved-orders', body),
  cancelReservedOrder: (seq, params) => api.delete(`/trading/reserved-orders/${seq}`, { params })
}

// Stock API (Spring Boot api-server)
export const stockApi = {
  search: (q) => api.get('/stocks/search', { params: { q } }),
  searchOverseas: (q) => api.get('/stocks/search', { params: { q, market: 'US' } }),
  getTop: (market) => api.get('/stocks/top', { params: market ? { market } : {} }),
  getPrice: (stockCode) => api.get(`/stocks/${stockCode}/price`),
  getOrderbook: (stockCode) => api.get(`/stocks/${stockCode}/orderbook`)
}

// Overseas (US) Stock API (Spring Boot api-server)
export const overseasApi = {
  getPrice: (symbol, exchange) =>
    api.get(`/overseas/stocks/${symbol}/price`, { params: { exchange } }),
  getOrderbook: (symbol, exchange) =>
    api.get(`/overseas/stocks/${symbol}/orderbook`, { params: { exchange } }),
  getBalance: () => api.get('/overseas/balance'),
  getHistory: (exchange) => api.get('/overseas/history', { params: { exchange } }),
  getPendingOrders: (exchange) => api.get('/overseas/pending-orders', { params: { exchange } }),
  getOrderable: (symbol, exchange, price) =>
    api.get('/overseas/orderable', { params: { symbol, exchange, price } }),
  buy: (order) => api.post('/overseas/buy', order),
  sell: (order) => api.post('/overseas/sell', order)
}

// Favorite API (Spring Boot api-server)
export const favoriteApi = {
  list: () => api.get('/favorites'),
  // payload: 문자열(종목코드) 또는 { stockCode, stockName?, exchangeCode? }
  add: (payload) =>
    api.post('/favorites', typeof payload === 'string' ? { stockCode: payload } : payload),
  remove: (stockCode) => api.delete(`/favorites/${stockCode}`)
}

// Company API (Spring Boot api-server)
export const companyApi = {
  getBasicInfo: (stockCode) => api.get(`/company/${stockCode}/basic-info`),
  getFinancials: (stockCode) => api.get(`/company/${stockCode}/financials`),
  getDisclosures: (stockCode) => api.get(`/company/${stockCode}/disclosures`)
}

// News API (Spring Boot api-server)
export const newsApi = {
  // params: { symbol?, date? } — symbol omitted → recent feed, date omitted → latest available date
  getList: (params) => api.get('/news', { params }),
  getDetail: (id) => api.get(`/news/${id}`)
}

// Market API (Spring Boot api-server — MarketDataController/MarketAnalysisController)
export const marketApi = {
  getIndices: (config) => api.get('/market/indices', config),
  getExchangeRates: () => api.get('/market/exchange-rates'),
  getTopNews: () => api.get('/market/news'),
  getAiRecommendations: () => api.get('/market/decisions')
}

// Market Analysis API (Spring Boot api-server)
export const marketAnalysisApi = {
  getSummary: (date) => api.get('/market/summary', { params: date ? { date } : {} }),
  getSentiment: (date) => api.get('/market/sentiment', { params: date ? { date } : {} }),
  getDecisions: (date) => api.get('/market/decisions', { params: date ? { date } : {} }),
  getLatestDate: () => api.get('/market/latest-date'),
  getHeatmap: (date) => api.get('/market/heatmap', { params: date ? { date } : {} }),
  getStockAnalysis: (stockCode, date) =>
    api.get(`/market/stock-analysis/${stockCode}`, { params: date ? { date } : {} }),
  getStockDetail: (stockCode, date) =>
    api.get(`/market/stock-detail/${stockCode}`, { params: date ? { date } : {} })
}

export default api

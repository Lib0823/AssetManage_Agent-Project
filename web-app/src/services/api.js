import axios from 'axios'

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
    const token = localStorage.getItem('accessToken')
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

    // 로그인/회원가입 요청은 인터셉터 처리 제외 (401을 그대로 반환)
    if (originalRequest.url?.includes('/auth/login') ||
        originalRequest.url?.includes('/auth/register') ||
        originalRequest.url?.includes('/auth/reset-password')) {
      return Promise.reject(error)
    }

    // 401 에러이고, 아직 재시도하지 않은 요청인 경우
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true

      // 이미 refresh 중이면 대기
      if (isRefreshing) {
        return new Promise((resolve) => {
          refreshSubscribers.push((token) => {
            originalRequest.headers.Authorization = `Bearer ${token}`
            resolve(api(originalRequest))
          })
        })
      }

      isRefreshing = true

      try {
        const refreshToken = localStorage.getItem('refreshToken')
        if (!refreshToken) {
          throw new Error('No refresh token')
        }

        // Refresh token으로 새 access token 요청
        const response = await axios.post(`${API_BASE_URL}/auth/refresh`, {
          refreshToken: refreshToken
        })

        const newAccessToken = response.data.data.accessToken

        // 새 토큰 저장
        localStorage.setItem('accessToken', newAccessToken)

        // 대기 중인 요청들에 새 토큰 전달
        refreshSubscribers.forEach((callback) => callback(newAccessToken))
        refreshSubscribers = []

        // 원래 요청 재시도
        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`
        return api(originalRequest)
      } catch (refreshError) {
        // Refresh 실패 시 로그아웃
        console.error('Token refresh failed:', refreshError)
        localStorage.clear()
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
  refreshToken: (refreshToken) => api.post('/auth/refresh', { refreshToken }),
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
  getBalance: () => api.get('/assets/balance')
}

// Trading API
export const tradingApi = {
  buy: (order) => api.post('/trading/buy', order),
  sell: (order) => api.post('/trading/sell', order),
  getHistory: (config) => api.get('/trading/history', config),
  getRecentTrades: (config) => api.get('/trading/recent', config),
  getHoldings: () => api.get('/trading/holdings'),
  getPendingOrders: () => api.get('/trading/pending-orders'),
  getOrderable: (stockCode, price) => api.get('/trading/orderable', { params: { stockCode, price } })
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

// Market API (Handled by FastAPI ai-agent)
export const marketApi = {
  getIndices: (config) => api.get('/market/indices', config),
  getExchangeRates: () => api.get('/market/exchange-rates'),
  getTopNews: () => api.get('/market/news'),
  getAiRecommendations: () => api.get('/market/decisions')
}

// AI Bot API (Handled by FastAPI ai-agent)
export const botApi = {
  getStatus: () => api.get('/bot/status'),
  getAnalysis: (symbol) => api.get(`/bot/analysis/${symbol}`),
  toggleBot: (enabled) => api.post('/bot/toggle', { enabled }),
  updateSettings: (settings) => api.put('/bot/settings', settings)
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

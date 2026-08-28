import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/tokenStorage'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior() {
    // Always scroll to top on navigation
    return { top: 0, behavior: 'instant' }
  },
  routes: [
    // Auth routes
    {
      path: '/',
      name: 'splash',
      component: () => import('../views/auth/SplashView.vue')
    },
    {
      path: '/welcome',
      name: 'welcome',
      component: () => import('../views/auth/WelcomeView.vue')
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/auth/LoginView.vue')
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/auth/RegisterView.vue')
    },
    {
      path: '/register/finance',
      name: 'register-finance',
      component: () => import('../views/auth/RegisterFinanceView.vue')
    },
    {
      path: '/terms',
      name: 'terms',
      component: () => import('../views/auth/TermsView.vue')
    },
    {
      path: '/reset-password',
      name: 'reset-password',
      component: () => import('../views/auth/ResetPasswordView.vue')
    },

    // Main routes (with bottom navigation)
    {
      path: '/home',
      name: 'home',
      component: () => import('../views/main/HomeView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/assets',
      name: 'assets',
      component: () => import('../views/main/AssetsView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/bot',
      name: 'bot',
      component: () => import('../views/main/BotView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('../views/main/SearchView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/favorites',
      name: 'favorites',
      component: () => import('../views/main/FavoritesView.vue'),
      meta: { showBottomNav: true }
    },

    // Detail routes
    {
      path: '/assets/detail',
      name: 'assets-detail',
      component: () => import('../views/detail/AssetDetailView.vue')
    },
    {
      path: '/company/:symbol',
      name: 'company-detail',
      component: () => import('../views/detail/CompanyDetailView.vue')
    },
    {
      path: '/trading/:symbol',
      name: 'trading',
      component: () => import('../views/detail/TradingView.vue')
    },
    // 채권 — 진입은 자산 화면의 보유 채권 카드에서만 이루어진다(검색 화면이 없다).
    // :code 는 12자리 영숫자 표준종목코드(KR2033022D33)이며, 매수일·매수순번 등 매수 로트
    // 정보는 쿼리로 함께 운반된다(매도에 필수).
    {
      path: '/bonds/:code',
      name: 'bond-detail',
      component: () => import('../views/detail/BondDetailView.vue')
    },
    {
      path: '/bonds/:code/sell',
      name: 'bond-sell',
      component: () => import('../views/detail/BondSellView.vue')
    },
    {
      path: '/transactions',
      name: 'transactions',
      component: () => import('../views/detail/TransactionsView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/news',
      name: 'news',
      component: () => import('../views/detail/NewsView.vue')
    },
    {
      path: '/news/:id',
      name: 'news-detail',
      component: () => import('../views/detail/NewsDetailView.vue')
    },
    {
      path: '/market-analysis',
      name: 'market-analysis',
      component: () => import('../views/analysis/MarketAnalysisView.vue')
    },

    // Settings routes
    {
      path: '/profile',
      name: 'profile',
      component: () => import('../views/settings/ProfileView.vue'),
      meta: { showBottomNav: true }
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/settings/SettingsView.vue')
    }
  ]
})

// Navigation guard
router.beforeEach((to, from, next) => {
  const publicPages = ['/', '/welcome', '/login', '/register', '/register/finance', '/terms', '/reset-password']
  const authRequired = !publicPages.includes(to.path)
  const token = getToken('accessToken')

  // For development, skip auth check
  if (import.meta.env.DEV) {
    next()
    return
  }

  if (authRequired && !token) {
    next('/welcome')
  } else {
    next()
  }
})

export default router

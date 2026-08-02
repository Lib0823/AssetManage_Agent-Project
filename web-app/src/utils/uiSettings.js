/**
 * UI 설정(다크 모드 / 자동 로그인 / 자산 표시 순서)의 단일 진실 공급원.
 *
 * 이 세 값은 앱 부팅 시점(테마)과 로그인 전(자동 로그인)에도 필요해서 인증이 필요한 API로는
 * 읽을 수 없다. 그래서 localStorage('uiSettings')를 권위 저장소로 쓰고, 백엔드
 * (`userApi.updateSettings`)에는 계정 단위 기록용으로 함께 저장한다.
 * localStorage에 값이 없을 때만 서버 값을 시드로 사용한다(SettingsView.loadSettings 참고).
 *
 * 소비처: main.js(부팅 시 테마 적용), SettingsView(읽기/쓰기),
 * LoginView(자동 로그인), AssetsView(자산 섹션 순서).
 */
import { ref } from 'vue'
import { logger } from '@/utils/logger'

const STORAGE_KEY = 'uiSettings'

/** 설정 화면의 '관심 자산 순위' 기본 순서 (label/icon은 프론트가 소유) */
export const DEFAULT_ASSET_ORDER = [
  { key: 'stocks_domestic', label: '주식 (국내)', icon: '🏠' },
  { key: 'stocks_overseas', label: '주식 (해외)', icon: '📈' },
  { key: 'coins', label: '코인 (추후 지원)', icon: '🪙' },
  { key: 'bonds', label: '채권 (추후 지원)', icon: '📜' }
]

const readRaw = () => {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch (error) {
    logger.debug('uiSettings 파싱 실패, 기본값 사용:', error)
    return {}
  }
}

/**
 * 화면들이 공유하는 반응형 스냅샷 (localStorage 미러).
 * 저장된 키만 담고 있으므로 `?? fallback`으로 "값 없음"을 구분할 수 있다.
 */
export const uiSettings = ref(readRaw())

/** 현재 저장값 사본 (저장된 키만 포함) */
export const readUiSettings = () => ({ ...uiSettings.value })

/** 일부 키만 병합 저장 후 병합 결과 반환 */
export const writeUiSettings = (patch) => {
  const next = { ...uiSettings.value, ...patch }
  uiSettings.value = next
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch (error) {
    logger.debug('uiSettings 저장 실패:', error)
  }
  return next
}

/**
 * 다크 모드 값을 DOM에 반영. `:root[data-theme=...]` 규칙(base.css)이
 * `@media (prefers-color-scheme: dark)`보다 우선하므로 OS 설정을 덮어쓴다.
 */
export const applyTheme = (darkMode) => {
  document.documentElement.setAttribute('data-theme', darkMode ? 'dark' : 'light')
}

/** 저장된 테마 값이 없으면 OS 설정(@media)으로 되돌린다 */
export const clearTheme = () => {
  document.documentElement.removeAttribute('data-theme')
}

/** 앱 부팅 시 1회 호출 — 저장된 다크 모드 값을 즉시 반영 (없으면 OS 설정 유지) */
export const initUiSettings = () => {
  const { darkMode } = uiSettings.value
  if (typeof darkMode === 'boolean') {
    applyTheme(darkMode)
  } else {
    clearTheme()
  }
}

/** 저장된 테마 값을 다시 적용 (설정 화면에서 저장 없이 이탈했을 때 되돌리기용) */
export const restoreStoredTheme = () => {
  initUiSettings()
}

/**
 * 저장된 자산 순서를 화면에서 쓸 수 있는 형태로 정규화한다.
 * - 서버/목업이 `['stocks_domestic', ...]` 처럼 key 배열만 주는 경우도 허용
 * - 모르는 key는 버리고, 빠진 key는 기본 순서 뒤에 붙인다 (섹션 누락 방지)
 */
export const normalizeAssetOrder = (raw) => {
  if (!Array.isArray(raw) || raw.length === 0) {
    return DEFAULT_ASSET_ORDER.map((item) => ({ ...item }))
  }

  const byKey = new Map(DEFAULT_ASSET_ORDER.map((item) => [item.key, item]))
  const result = []

  for (const entry of raw) {
    const key = typeof entry === 'string' ? entry : entry?.key
    if (!byKey.has(key) || result.some((item) => item.key === key)) continue
    result.push({ ...byKey.get(key) })
  }

  for (const item of DEFAULT_ASSET_ORDER) {
    if (!result.some((existing) => existing.key === item.key)) {
      result.push({ ...item })
    }
  }

  return result
}

/** 저장된 자산 순서의 key 배열 (AssetsView 섹션 배치용) */
export const getAssetOrderKeys = () =>
  normalizeAssetOrder(uiSettings.value.assetOrder).map((item) => item.key)

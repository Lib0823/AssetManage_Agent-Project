/**
 * 인증 토큰(accessToken/refreshToken/user/accountMode) 저장소.
 *
 * "자동 로그인" 설정(uiSettings.autoLogin)에 따라 저장 위치를 가른다:
 * - true(기본값)  → localStorage: 브라우저를 껐다 켜도 로그인 유지
 * - false         → sessionStorage: 새로고침엔 살아남지만 탭/브라우저를 닫으면 사라짐
 *
 * 어느 쪽에 저장됐는지 모르는 소비처(라우터 가드, axios 인터셉터)를 위해 조회는 항상
 * 양쪽을 다 본다. 두 저장소에 동시에 값이 남지 않도록 쓰기/삭제 시 반대쪽도 정리한다.
 */
import { readUiSettings } from './uiSettings'

const KEYS = ['accessToken', 'refreshToken', 'user', 'accountMode']

const activeStorage = () => (readUiSettings().autoLogin === false ? sessionStorage : localStorage)
const otherStorage = (storage) => (storage === localStorage ? sessionStorage : localStorage)

/** 저장 위치를 몰라도 값을 읽는다 (localStorage 우선, 없으면 sessionStorage). */
export const getToken = (key) => localStorage.getItem(key) ?? sessionStorage.getItem(key)

/** 여러 키를 현재 자동 로그인 설정에 맞는 저장소에 쓰고, 반대쪽의 잔여값은 지운다. */
export const setTokens = (pairs) => {
  const storage = activeStorage()
  const other = otherStorage(storage)
  for (const [key, value] of Object.entries(pairs)) {
    if (value == null) continue
    storage.setItem(key, value)
    other.removeItem(key)
  }
}

/** 인증 관련 키만 양쪽 저장소에서 전부 지운다 (uiSettings 등 다른 키는 건드리지 않는다). */
export const clearTokens = () => {
  for (const key of KEYS) {
    localStorage.removeItem(key)
    sessionStorage.removeItem(key)
  }
}

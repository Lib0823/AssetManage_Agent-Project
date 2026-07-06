/**
 * 경량 로거.
 *
 * KIS 미연동/점검처럼 "예상되고 이미 화면에서 처리한(안내 표시)" 실패는
 * 콘솔 에러(빨간 로그)로 남기지 않는다 — 사용자·개발자 모두에게 노이즈가 된다.
 * 대신 debug/info 레벨로 조용히 남기고, 운영(prod)에서는 debug/info/warn 을 억제한다.
 *
 * 레벨 가이드
 *  - debug : 예상된 흐름(로딩 실패 degrade, KIS 미연동 등). dev 에서만 출력.
 *  - info  : 참고 정보. dev 에서만 출력.
 *  - warn  : 주의가 필요하지만 치명적이지 않음. dev 에서만 출력.
 *  - error : 진짜 예상 못한 오류. 항상 출력.
 */
const isDev = import.meta.env.DEV

export const logger = {
  debug: (...args) => {
    if (isDev) console.debug('[app]', ...args)
  },
  info: (...args) => {
    if (isDev) console.info('[app]', ...args)
  },
  warn: (...args) => {
    if (isDev) console.warn('[app]', ...args)
  },
  error: (...args) => {
    console.error('[app]', ...args)
  }
}

export default logger

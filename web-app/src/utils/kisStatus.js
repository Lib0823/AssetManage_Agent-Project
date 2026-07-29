/**
 * KIS(한국투자증권) 시세/잔고 연동 상태 판별 유틸.
 *
 * 백엔드는 KIS 실패를 하나의 신호로 통일해 주지 않는다.
 * - "하드" 엔드포인트(잔고/거래 실행/보유): KIS 다운 시 HTTP 500 또는 503 으로 예외 전파.
 * - "소프트" 엔드포인트(지수/시세/호가/해외 시세): 항상 HTTP 200 이고 값이 null/빈배열로 degrade,
 *   일부는 응답 body 에 notice 문자열을 담는다.
 *
 * 따라서 화면마다 (1) 에러 객체 (2) 200 응답의 빈값/notice 를 함께 보고 "점검중" 여부를 판단한다.
 */

/**
 * 사용자에게 보여줄 통일 안내 문구.
 * 지수·시세·잔고·호가·거래내역 등 모든 KIS 연동 화면에서 동일하게 사용한다
 * (문구가 화면마다 달라지지 않도록 이 상수만 참조).
 */
export const KIS_MAINTENANCE_TITLE = 'KIS 점검 중'
export const KIS_MAINTENANCE_MESSAGE =
  'KIS 점검 시간이거나 일시적인 연동 오류로 실시간 정보를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.'

/**
 * axios 에러가 "KIS 연동 불가(점검/네트워크/서버 오류)"로 볼 수 있는지 판별.
 * 하드 엔드포인트(잔고/거래/보유)의 catch 블록에서 사용.
 *
 * @param {any} error axios 에러 객체
 * @returns {boolean}
 */
export function isKisOutageError(error) {
  if (!error) return false
  // 타임아웃
  if (error.code === 'ECONNABORTED') return true
  // 네트워크 도달 실패(응답 자체 없음)
  if (!error.response) return true
  const status = error.response.status
  // KIS 다운은 백엔드에서 500(대부분) 또는 503(콜드 토큰 OAuth 실패)으로 전파됨.
  // 502/504(게이트웨이/타임아웃)도 상류 장애로 간주.
  return status === 500 || status === 502 || status === 503 || status === 504
}

/**
 * 소프트 엔드포인트 응답 body 에 담긴 notice 가 "KIS 시세 미연동"을 뜻하는지 판별.
 * (키 미설정과 일시 점검을 문구로 구분할 수 없으므로 notice 존재 자체를 미연동 신호로 본다.)
 *
 * @param {string|null|undefined} notice
 * @returns {boolean}
 */
export function isKisUnavailableNotice(notice) {
  return typeof notice === 'string' && notice.trim().length > 0
}

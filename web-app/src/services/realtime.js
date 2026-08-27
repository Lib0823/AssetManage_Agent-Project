/**
 * Realtime WebSocket service (Phase 1: 실시간 호가 + 체결가)
 *
 * 네이티브 WebSocket 싱글톤. 브라우저는 KIS에 직접 붙지 못하므로 Spring 브리지
 * `/ws/realtime`에 JWT 핸드셰이크로 연결하고, 서버가 단일 상향 KIS 연결을
 * 심볼 ref-count로 멀티플렉싱한다. (신규 의존성 0 — 네이티브 WebSocket 사용)
 *
 * 프로토콜 (서버 RealtimeWebSocketHandler와 합의):
 *  - 구독:  client → server  {action:'subscribe',   market, symbol, type, exchange}
 *  - 해제:  client → server  {action:'unsubscribe', market, symbol, type, exchange}
 *  - 데이터: server → client  {type:'quote'|'tick', market, symbol, ...}
 *  - 상태:  server → client  {type:'status', state:'disabled'|'reconnecting'|..., notice}
 *
 *  - market : 'KR' | 'US'
 *  - type   : 'orderbook' | 'tick'
 *  - exchange : (US 전용) OverseasExchange enum 값. KR이면 무시/생략.
 *
 * Graceful degrade: 연결 실패/서버 disabled 상태에서도 절대 throw 하지 않는다.
 * 구독자 콜백은 데이터 프레임만, 상태 변화는 onStatus 리스너로 전달한다.
 */

import { logger } from '@/utils/logger'
import { getToken } from '@/utils/tokenStorage'

const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30000
// 연속 실패가 이 횟수에 도달하면 자동 재연결을 멈춘다(무한 재시도 → 콘솔에 브라우저 네이티브
// WebSocket 실패 로그가 계속 쌓이는 것을 방지). 실시간 브리지가 꺼져 있으면(kis.realtime.enabled=false)
// 엔드포인트가 없어 매번 실패하므로, 몇 번 시도 후 'disabled'로 포기하고 사용자 액션(구독) 시 재시도한다.
const MAX_RECONNECT_ATTEMPTS = 4

// KIS 계좌가 등록되지 않았을 때 표시할 안내. 이 경우 연결 자체를 시도하지 않는다.
const NOTICE_REALTIME_NO_ACCOUNT = '실시간 시세는 KIS 계좌를 등록해야 이용할 수 있습니다'

/**
 * 연결 상태 (store와 동일 enum)
 * connecting | open | reconnecting | disabled | closed
 */
function deriveWsUrl() {
  const base = import.meta.env.VITE_API_BASE_URL || 'http://localhost:7070/api'
  // http(s)://host:port/api → ws(s)://host:port/ws/realtime
  // baseURL에서 컨텍스트 경로(/api 등)는 떼고 호스트만 사용한다.
  let origin
  try {
    const u = new URL(base, window.location.origin)
    origin = `${u.protocol}//${u.host}`
  } catch {
    origin = base.replace(/\/api\/?$/, '')
  }
  const wsOrigin = origin.replace(/^http/, 'ws')
  return `${wsOrigin}/ws/realtime`
}

// 체결통보(fills)는 종목이 없는 계좌 단위 스트림이라 market/symbol/type 키 조합을 쓰지 않는다.
// 전용 this.fillsSub(refCount + 콜백 Set)로 dedupe/라우팅하고, 와이어 프로토콜은
// {action, type:'fills'}로 보낸다. (별도 문자열 키 불필요)

/**
 * 구독 dedupe 키. (서버 SubKey와 무관 — 클라이언트 측 콜백 라우팅용)
 * exchange는 키에 넣지 않는다: 서버 데이터 프레임에 exchange 필드가 없어
 * routeKey가 같은 형태를 만들 수 없기 때문이다.
 */
function subKey(market, symbol, type) {
  return `${market}:${symbol}:${type}`
}

/** 메시지 라우팅 키. 서버가 status 프레임에는 symbol을 안 줄 수 있으므로 분리. */
function routeKey(market, symbol, type) {
  return `${market}:${symbol}:${type}`
}

class RealtimeClient {
  constructor() {
    this.ws = null
    this.url = null
    this.state = 'closed'

    // subKey → { market, symbol, type, exchange, refCount, callbacks:Set<fn> }
    this.subscriptions = new Map()
    // 상태 변화 리스너 Set<fn(state, notice)>
    this.statusListeners = new Set()

    // 체결통보(fills) 구독: 계좌 단위 단일 스트림. refCount + 콜백 Set.
    // { refCount, callbacks:Set<fn> }
    this.fillsSub = { refCount: 0, callbacks: new Set() }

    this.reconnectAttempts = 0
    this.reconnectTimer = null
    this.intentionalClose = false
    // 연속 실패로 자동 재연결을 포기한 상태. 사용자 액션(subscribe/subscribeFills) 시 초기화된다.
    this.gaveUp = false
    // 실시간 사용 여부. KIS 계좌가 등록된 경우에만 true. false(기본, 미등록)면 연결을 시도하지 않는다.
    // 로그인·프로필 저장 시 계좌 등록 여부에 따라 setEnabled()로 갱신한다.
    this.enabled = false
  }

  /**
   * 실시간 연결 사용 여부 설정. KIS 계좌가 등록된 경우에만 true 로 켠다.
   * - true : 이전 포기 상태를 초기화하고, 구독이 있으면 즉시 연결
   * - false: 진행 중 연결/타이머를 정리하고 재연결을 막는다(미등록 → 콘솔 에러 0)
   */
  setEnabled(enabled) {
    const next = !!enabled
    if (this.enabled === next) return
    this.enabled = next
    if (next) {
      this.gaveUp = false
      this.reconnectAttempts = 0
      this.intentionalClose = false
      if (this.subscriptions.size > 0 || this.fillsSub.refCount > 0) {
        this.connect()
      }
    } else {
      this._disable(NOTICE_REALTIME_NO_ACCOUNT)
    }
  }

  /** 연결을 의도적으로 종료하고 재연결을 억제한다(계좌 미등록/로그아웃). */
  _disable(notice) {
    this.intentionalClose = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      try {
        this.ws.close()
      } catch {
        /* noop */
      }
      this.ws = null
    }
    this.reconnectAttempts = 0
    this.gaveUp = false
    this._setState('disabled', notice || null)
  }

  /**
   * 사용자 액션(구독) 진입점에서 호출. 이전에 재연결을 포기(gaveUp)했다면 카운터를 초기화해
   * 한 번 더 재시도할 기회를 준다. (진행 중인 재연결 사이클은 건드리지 않는다.)
   */
  _wakeUp() {
    if (this.gaveUp) {
      this.gaveUp = false
      this.reconnectAttempts = 0
    }
  }

  /**
   * 현재 accessToken. 자동 로그인 OFF면 토큰이 sessionStorage에 있으므로 양쪽을 보는
   * getToken()을 써야 한다 (localStorage 직접 조회는 그 경우 빈 값이 되어 연결이 죽는다).
   */
  _token() {
    return getToken('accessToken') || ''
  }

  _setState(state, notice) {
    this.state = state
    for (const fn of this.statusListeners) {
      try {
        fn(state, notice)
      } catch (e) {
        logger.warn('[realtime] status listener error:', e)
      }
    }
  }

  /** 상태 변화 구독. 해제 함수 반환. */
  onStatus(fn) {
    this.statusListeners.add(fn)
    // 현재 상태를 즉시 한 번 통지
    try {
      fn(this.state)
    } catch (e) {
      logger.warn('[realtime] status listener error:', e)
    }
    return () => this.statusListeners.delete(fn)
  }

  /** 연결 (멱등). 비활성(계좌 미등록)·토큰 없음이면 연결 시도하지 않고 disabled 처리. */
  connect() {
    // KIS 계좌가 없으면 아예 연결을 시도하지 않는다(미등록 → 콘솔 WS 에러 방지).
    if (!this.enabled) {
      this._setState('disabled', NOTICE_REALTIME_NO_ACCOUNT)
      return
    }

    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    const token = this._token()
    if (!token) {
      // 인증 토큰이 없으면 브리지에 붙을 수 없다 → degrade.
      this._setState('disabled', '인증 토큰이 없어 실시간 연결을 사용할 수 없습니다.')
      return
    }

    this.intentionalClose = false
    this.url = deriveWsUrl()

    let socket
    try {
      socket = new WebSocket(`${this.url}?token=${encodeURIComponent(token)}`)
    } catch (e) {
      // 생성 자체 실패 → 절대 throw 하지 않고 재연결 스케줄.
      logger.warn('[realtime] WebSocket 생성 실패:', e)
      this._scheduleReconnect()
      return
    }

    this.ws = socket
    this._setState(this.reconnectAttempts > 0 ? 'reconnecting' : 'connecting')

    socket.onopen = () => {
      this.reconnectAttempts = 0
      this._setState('open')
      // 재연결 시 기존 모든 구독 재등록.
      this._resubscribeAll()
    }

    socket.onmessage = (event) => {
      this._handleMessage(event.data)
    }

    socket.onerror = (event) => {
      // onclose가 뒤따라 오므로 여기서는 로깅만. KIS 브리지 점검/다운 시 반복 발생하므로
      // 빨간 콘솔 에러 대신 debug 로 조용히 남긴다(재연결 상태는 onStatus 로 전달됨).
      logger.debug('[realtime] WebSocket error:', event)
    }

    socket.onclose = () => {
      this.ws = null
      if (this.intentionalClose) {
        this._setState('closed')
        return
      }
      this._scheduleReconnect()
    }
  }

  _scheduleReconnect() {
    if (this.intentionalClose) return
    if (this.reconnectTimer) return
    // 재등록할 구독(심볼 or 체결통보)이 하나도 없으면 굳이 재연결하지 않는다.
    if (this.subscriptions.size === 0 && this.fillsSub.refCount === 0) {
      this._setState('closed')
      return
    }

    // 연속 실패가 상한에 도달하면 자동 재연결을 멈춘다(무한 루프 → 콘솔 네이티브 WS 에러 누적 방지).
    // 사용자가 실시간이 필요한 화면으로 이동해 다시 구독하면 _wakeUp()으로 재시도한다.
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.gaveUp = true
      this._setState('disabled', '실시간 연결을 사용할 수 없습니다 (서버 미연동 또는 KIS 점검). 화면을 다시 열면 재시도합니다.')
      return
    }

    const attempt = this.reconnectAttempts
    const backoff = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS)
    const jitter = Math.floor(Math.random() * 1000)
    const delay = backoff + jitter

    this._setState('reconnecting')
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.reconnectAttempts += 1
      this.connect()
    }, delay)
  }

  _handleMessage(raw) {
    let msg
    try {
      msg = typeof raw === 'string' ? JSON.parse(raw) : raw
    } catch {
      // 프레임 파싱 실패는 무시 (브리지는 JSON만 보냄).
      return
    }
    if (!msg || typeof msg !== 'object') return

    if (msg.type === 'status') {
      // 서버 측 degrade/reconnect 통지.
      this._setState(msg.state || this.state, msg.notice)
      return
    }

    // 체결통보(fill) 라우팅: symbol 가드보다 먼저 처리.
    // 체결통보 프레임은 종목(symbol)이 있더라도 계좌 단위 fills 콜백으로 보낸다.
    if (msg.type === 'fill') {
      for (const cb of this.fillsSub.callbacks) {
        try {
          cb(msg)
        } catch (e) {
          logger.warn('[realtime] fills callback error:', e)
        }
      }
      return
    }

    // 데이터 프레임 라우팅: type(quote→orderbook / tick) + symbol.
    // 서버 QuoteMessage.type 기본값은 이미 'orderbook'이라 그대로 매칭된다.
    // 'quote' 매핑은 구버전 서버 프레임을 위한 호환 처리.
    const subType = msg.type === 'quote' ? 'orderbook' : msg.type
    if (!subType || msg.symbol == null) return

    const market = msg.market || 'KR'
    const key = routeKey(market, msg.symbol, subType)
    const sub = this.subscriptions.get(key)
    if (!sub) return

    for (const cb of sub.callbacks) {
      try {
        cb(msg)
      } catch (e) {
        logger.warn('[realtime] subscriber callback error:', e)
      }
    }
  }

  /**
   * 구독. dedupe + refCount.
   * @param {string} market 'KR' | 'US'
   * @param {string} symbol 종목 코드 / 심볼
   * @param {string} type   'orderbook' | 'tick'
   * @param {string|null} exchange US 전용 거래소 코드 (KR이면 null)
   * @param {function} cb    데이터 프레임 콜백 (msg) => void
   * @returns {function} 해제 함수
   */
  subscribe(market, symbol, type, exchange, cb) {
    const key = subKey(market, symbol, type)
    let sub = this.subscriptions.get(key)

    if (!sub) {
      sub = {
        market,
        symbol,
        type,
        exchange: exchange || null,
        refCount: 0,
        callbacks: new Set()
      }
      this.subscriptions.set(key, sub)
    } else if (!sub.exchange && exchange) {
      // 먼저 구독한 쪽이 exchange 없이 붙었으면 뒤늦게 들어온 값으로 채운다
      // (재연결 시 재구독 프레임과 해제 프레임이 올바른 거래소를 싣도록).
      sub.exchange = exchange
    }

    if (typeof cb === 'function') {
      sub.callbacks.add(cb)
    }
    sub.refCount += 1

    // 첫 ref면 서버에 등록 프레임 전송 (이미 연결돼 있을 때).
    if (sub.refCount === 1) {
      this._sendSubscribe(sub)
    }

    // 사용자 액션 → 이전에 포기했으면 재시도. 연결이 없으면 시작.
    this._wakeUp()
    this.connect()

    return () => this.unsubscribe(market, symbol, type, cb)
  }

  /** 구독 해제. refCount 0 도달 시 서버에 해제 프레임 전송. */
  unsubscribe(market, symbol, type, cb) {
    const key = subKey(market, symbol, type)
    const sub = this.subscriptions.get(key)
    if (!sub) return

    if (typeof cb === 'function') {
      sub.callbacks.delete(cb)
    }
    sub.refCount = Math.max(0, sub.refCount - 1)

    if (sub.refCount === 0) {
      this._sendUnsubscribe(sub)
      this.subscriptions.delete(key)
    }
  }

  /**
   * 체결통보 구독. 계좌 단위 단일 스트림 → 고정 키 + refCount.
   * @param {function} cb (msg) => void 체결통보 프레임 콜백
   * @returns {function} 해제 함수
   */
  subscribeFills(cb) {
    if (typeof cb === 'function') {
      this.fillsSub.callbacks.add(cb)
    }
    this.fillsSub.refCount += 1

    // 첫 ref면 서버에 등록 프레임 전송 (이미 연결돼 있을 때).
    if (this.fillsSub.refCount === 1) {
      this._sendSubscribeFills()
    }

    // 사용자 액션 → 이전에 포기했으면 재시도. 연결이 없으면 시작.
    this._wakeUp()
    this.connect()

    return () => this.unsubscribeFills(cb)
  }

  /** 체결통보 해제. refCount 0 도달 시 서버에 해제 프레임 전송. */
  unsubscribeFills(cb) {
    if (typeof cb === 'function') {
      this.fillsSub.callbacks.delete(cb)
    }
    this.fillsSub.refCount = Math.max(0, this.fillsSub.refCount - 1)

    if (this.fillsSub.refCount === 0) {
      this._sendUnsubscribeFills()
    }
  }

  _sendSubscribeFills() {
    this._send({ action: 'subscribe', type: 'fills' })
  }

  _sendUnsubscribeFills() {
    this._send({ action: 'unsubscribe', type: 'fills' })
  }

  _sendSubscribe(sub) {
    this._send({
      action: 'subscribe',
      market: sub.market,
      symbol: sub.symbol,
      type: sub.type,
      exchange: sub.exchange
    })
  }

  _sendUnsubscribe(sub) {
    this._send({
      action: 'unsubscribe',
      market: sub.market,
      symbol: sub.symbol,
      type: sub.type,
      exchange: sub.exchange
    })
  }

  _send(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      // 아직 연결 전이면 onopen의 _resubscribeAll에서 일괄 전송됨.
      return
    }
    try {
      this.ws.send(JSON.stringify(obj))
    } catch (e) {
      logger.debug('[realtime] send 실패:', e)
    }
  }

  _resubscribeAll() {
    for (const sub of this.subscriptions.values()) {
      if (sub.refCount > 0) {
        this._sendSubscribe(sub)
      }
    }
    // 체결통보(fills)도 재연결 시 재구독.
    if (this.fillsSub.refCount > 0) {
      this._sendSubscribeFills()
    }
  }

  /** 전체 종료 (의도적). 모든 구독/타이머 정리. */
  disconnect() {
    this.intentionalClose = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.subscriptions.clear()
    this.fillsSub.refCount = 0
    this.fillsSub.callbacks.clear()
    if (this.ws) {
      try {
        this.ws.close()
      } catch {
        // ignore
      }
      this.ws = null
    }
    this._setState('closed')
  }
}

// 싱글톤
const realtimeClient = new RealtimeClient()

export default realtimeClient

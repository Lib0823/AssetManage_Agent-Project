import { webauthnApi } from '@/services/api'
import { useAuthStore } from '@/stores/auth'

// WebAuthn 생체 로그인(패스키/Face ID) 클라이언트 헬퍼.
//
// 서버(Yubico java-webauthn-server)는 options 를 표준 WebAuthn JSON 으로 내려주며,
// base64url 로 인코딩된 바이너리 필드(challenge, user.id, allowCredentials[].id,
// excludeCredentials[].id)를 navigator.credentials API 가 요구하는 ArrayBuffer 로
// 직접 변환한다. 응답(credential)은 rawId/response 의 ArrayBuffer 를 다시 base64url
// 문자열로 직렬화해 finish 로 전송한다. (@github/webauthn-json 등 추가 의존성 없이 수동 변환)

// ---------------------------------------------------------------------------
// base64url <-> ArrayBuffer 헬퍼
// ---------------------------------------------------------------------------

function base64urlToArrayBuffer(base64url) {
  // base64url -> base64
  let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/')
  // padding 복원
  const pad = base64.length % 4
  if (pad === 2) {
    base64 += '=='
  } else if (pad === 3) {
    base64 += '='
  }

  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes.buffer
}

function arrayBufferToBase64url(buffer) {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  const base64 = btoa(binary)
  // base64 -> base64url
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

// ---------------------------------------------------------------------------
// options(JSON) -> navigator.credentials API 입력 변환
// ---------------------------------------------------------------------------

function toCreationOptions(publicKey) {
  const converted = { ...publicKey }
  converted.challenge = base64urlToArrayBuffer(publicKey.challenge)
  converted.user = {
    ...publicKey.user,
    id: base64urlToArrayBuffer(publicKey.user.id)
  }
  if (Array.isArray(publicKey.excludeCredentials)) {
    converted.excludeCredentials = publicKey.excludeCredentials.map((cred) => ({
      ...cred,
      id: base64urlToArrayBuffer(cred.id)
    }))
  }
  return converted
}

function toRequestOptions(publicKey) {
  const converted = { ...publicKey }
  converted.challenge = base64urlToArrayBuffer(publicKey.challenge)
  if (Array.isArray(publicKey.allowCredentials)) {
    converted.allowCredentials = publicKey.allowCredentials.map((cred) => ({
      ...cred,
      id: base64urlToArrayBuffer(cred.id)
    }))
  }
  return converted
}

// ---------------------------------------------------------------------------
// credential(PublicKeyCredential) -> finish 전송용 JSON 직렬화
// ---------------------------------------------------------------------------

function serializeRegistrationCredential(credential) {
  const response = credential.response
  return {
    id: credential.id,
    rawId: arrayBufferToBase64url(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults
      ? credential.getClientExtensionResults()
      : {},
    response: {
      clientDataJSON: arrayBufferToBase64url(response.clientDataJSON),
      attestationObject: arrayBufferToBase64url(response.attestationObject),
      transports:
        typeof response.getTransports === 'function' ? response.getTransports() : []
    }
  }
}

function serializeAssertionCredential(credential) {
  const response = credential.response
  return {
    id: credential.id,
    rawId: arrayBufferToBase64url(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults
      ? credential.getClientExtensionResults()
      : {},
    response: {
      clientDataJSON: arrayBufferToBase64url(response.clientDataJSON),
      authenticatorData: arrayBufferToBase64url(response.authenticatorData),
      signature: arrayBufferToBase64url(response.signature),
      userHandle: response.userHandle ? arrayBufferToBase64url(response.userHandle) : null
    }
  }
}

// ---------------------------------------------------------------------------
// 공개 API
// ---------------------------------------------------------------------------

// 플랫폼 인증기(Face ID / 지문 / Windows Hello) 사용 가능 여부.
// WebAuthn 미지원 브라우저면 false 를 반환(throw 하지 않음).
export async function isPlatformAuthAvailable() {
  try {
    if (
      typeof window === 'undefined' ||
      typeof window.PublicKeyCredential === 'undefined' ||
      typeof window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable !==
        'function'
    ) {
      return false
    }
    return await window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()
  } catch (error) {
    console.error('isPlatformAuthAvailable check failed:', error)
    return false
  }
}

// 현재 로그인된 사용자 기준으로 이 기기에 생체 자격증명(패스키)을 등록.
// 호출 시점에 유효한 accessToken 이 있어야 한다(register/start 는 JWT 필요).
export async function registerBiometric() {
  // 1. 서버에서 생성 옵션 받기
  const startRes = await webauthnApi.registerStart()
  const flowId = startRes.flowId
  const options = startRes.options

  // 2. base64url 필드 -> ArrayBuffer 변환
  const publicKey = toCreationOptions(options.publicKey ? options.publicKey : options)

  // 3. 인증기로 자격증명 생성 (실제 Face ID/지문 프롬프트)
  const credential = await navigator.credentials.create({ publicKey })
  if (!credential) {
    throw new Error('생체 자격증명 생성에 실패했습니다')
  }

  // 4. 직렬화 후 서버 검증
  const serialized = serializeRegistrationCredential(credential)
  await webauthnApi.registerFinish({
    flowId,
    credential: JSON.stringify(serialized)
  })

  return true
}

// usernameless 패스키 로그인. 성공 시 authStore.setAuthData 로 토큰 저장.
export async function loginBiometric() {
  // 1. 서버에서 assertion 옵션 받기 (공개 엔드포인트)
  const startRes = await webauthnApi.loginStart()
  const flowId = startRes.flowId
  const options = startRes.options

  // 2. base64url 필드 -> ArrayBuffer 변환
  const publicKey = toRequestOptions(options.publicKey ? options.publicKey : options)

  // 3. 인증기로 assertion 생성 (실제 Face ID/지문 프롬프트)
  const credential = await navigator.credentials.get({ publicKey })
  if (!credential) {
    throw new Error('생체 인증에 실패했습니다')
  }

  // 4. 직렬화 후 서버 검증 -> LoginResponse(accessToken/refreshToken/user)
  const serialized = serializeAssertionCredential(credential)
  const loginResponse = await webauthnApi.loginFinish({
    flowId,
    credential: JSON.stringify(serialized)
  })

  // 5. 일반 로그인과 동일하게 토큰 저장
  const authStore = useAuthStore()
  authStore.setAuthData({
    accessToken: loginResponse.accessToken,
    refreshToken: loginResponse.refreshToken,
    user: loginResponse.user
  })

  return loginResponse
}

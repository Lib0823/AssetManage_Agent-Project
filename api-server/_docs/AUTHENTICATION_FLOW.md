# 인증 흐름 (JWT)

> Spring Boot API Server의 JWT 기반 인증 구조와 토큰 발급/검증/회전 흐름을 코드 기준으로 정리합니다. KIS API 인증과는 별개입니다.

## 목차
1. [구성 요소](#1-구성-요소)
2. [토큰 구조](#2-토큰-구조)
3. [RefreshToken 엔티티와 회전](#3-refreshtoken-엔티티와-회전)
4. [주요 인증 흐름](#4-주요-인증-흐름)
   - [login](#41-login)
   - [register](#42-register)
   - [refresh](#43-refresh)
   - [logout](#44-logout)
   - [reset-password](#45-reset-password)
5. [요청 인증 필터 (JwtAuthenticationFilter)](#5-요청-인증-필터-jwtauthenticationfilter)
6. [SecurityConfig 설정](#6-securityconfig-설정)
7. [앱 JWT vs KIS 토큰 헤더 차이](#7-앱-jwt-vs-kis-토큰-헤더-차이)
8. [인증 관련 ErrorCode](#8-인증-관련-errorcode)
9. [WebAuthn 생체/패스키 로그인](#9-webauthn-생체패스키-로그인)
10. [관련 문서](#10-관련-문서)

---

## 1. 구성 요소

| 항목 | 값 |
|------|-----|
| JWT 라이브러리 | jjwt 0.12.3 |
| 서명 알고리즘 | HMAC-SHA (`Keys.hmacShaKeyFor(secret)`) |
| secret 소스 | `jwt.secret` (env `JWT_SECRET`) |
| 비밀번호 인코더 | `BCryptPasswordEncoder` |
| 세션 정책 | STATELESS |
| 권한 | `CustomUserDetails`가 `ROLE_USER` 부여 |

`JwtTokenProvider`의 주요 메서드:

| 메서드 | 설명 |
|--------|------|
| `generateAccessToken(username, userId, kisAccountId)` | access token 발급 |
| `generateRefreshToken(username)` | refresh token 발급 |
| `validateToken(token)` | 서명/만료 검증 |
| `getUsernameFromToken(token)` | subject 추출 |
| `getUserIdFromToken(token)` | userId claim 추출 |
| `getKisAccountIdFromToken(token)` | kisAccountId claim 추출 |

---

## 2. 토큰 구조

| 토큰 | subject | 커스텀 claims | TTL |
|------|---------|---------------|-----|
| Access Token | `username` | `userId`, `kisAccountId`, `iat`, `exp` | `3600000ms` (1시간) |
| Refresh Token | `username` | 없음 (userId/kisAccountId 미포함) | `86400000ms` (24시간) |

> Refresh token에는 `userId` / `kisAccountId`가 들어가지 않습니다. 이 값들은 access token에만 존재합니다.

---

## 3. RefreshToken 엔티티와 회전

`RefreshToken` 엔티티 (테이블 `refresh_tokens`):

| 컬럼 | 설명 |
|------|------|
| `id` | PK |
| `user` | `@ManyToOne` 사용자 참조 |
| `token` | unique, 길이 500 |
| `expiresAt` | 만료 시각 |
| `createdAt` | 생성 시각 |
| `revokedAt` | 폐기 시각 (nullable) |

메서드: `isRevoked()`, `isExpired()`, `revoke()`.

**회전(rotation) 정책 — 사용자당 활성 토큰 1개:**
`AuthService.saveRefreshToken`은 로그인 시 `findByUserAndRevokedAtIsNull`로 해당 사용자의 폐기되지 않은 기존 토큰을 먼저 revoke한 뒤 새 토큰을 저장합니다.

---

## 4. 주요 인증 흐름

### 4.1 login

엔드포인트: `POST /auth/login` → `LoginResponse`. `AuthService.login` 순서:

| 단계 | 동작 | 실패 시 |
|------|------|---------|
| 1 | `findByUsername` | `BadCredentialsException` |
| 2 | `passwordEncoder.matches` (BCrypt) | `BadCredentialsException` |
| 3 | `kisAccountRepository.findByUser` | `KisAccountNotFoundException` (KIS 계정 필수) |
| 4 | access token(userId+kisAccountId) + refresh token 발급 | - |
| 5 | `saveRefreshToken` (기존 토큰 revoke 후 저장) | - |
| 6 | `LoginResponse` 반환 | - |

> **KIS 계정은 로그인 필수 조건입니다.** KIS 계정이 없으면 인증 정보가 맞아도 로그인할 수 없습니다.

### 4.2 register

엔드포인트: `POST /auth/register` → `RegisterResponse` (201). `AuthService.register` 순서:

| 단계 | 동작 | 실패 시 |
|------|------|---------|
| 1 | `passwordConfirm` 일치 확인 | `PASSWORD_MISMATCH` |
| 2 | `existsByUsername` | `USERNAME_DUPLICATE` |
| 3 | `findByEmail` | `EMAIL_DUPLICATE` |
| 4 | `User` 생성 (BCrypt password) | - |
| 5 | (선택) `UserKisAccount` 생성, `isVerified=false`, `accountMode`=요청 `mode`(REAL/그외 MOCK) | - |
| 6 | 기본 `UserTradeConfig` 생성 (`orderAmount=1000000`, `maxHoldings=10`, `orderType="market"`, `isActive=false`) | - |

> 가입 시 토큰을 발급하지 않습니다. 가입 후 별도 `login`이 필요합니다.

> **KIS 모의/실전 모드:** 가입 KIS 파트와 `POST /auth/validate-kis-account`, `PUT /users/kis-account`는 `mode`(MOCK/REAL)를 받는다. `validateKisAccount`는 `baseUrlFor(mode)` 도메인으로 OAuth 검증하고, 저장된 `account_mode`(v1.15, 기본 MOCK)가 이후 매매/조회/체결통보 도메인·TR 라우팅 기준이 된다. 상세는 [KIS_API_GUIDE.md](KIS_API_GUIDE.md) §2(A) 참고.

### 4.3 refresh

엔드포인트: `POST /auth/refresh` → `RefreshTokenResponse`.

| 단계 | 동작 |
|------|------|
| 1 | `validateToken(jwt)` |
| 2 | DB `findByToken` 조회 |
| 3 | `revokedAt == null` 확인 |
| 4 | 새 access token 발급 (refresh token은 동일 토큰 재사용) |

### 4.4 logout

엔드포인트: `POST /auth/logout` → `Void`.

| 단계 | 동작 |
|------|------|
| 1 | `findByToken` |
| 2 | `revoke()` |
| 3 | `save` |

### 4.5 reset-password

엔드포인트: `POST /auth/reset-password` → `Void`.

| 단계 | 동작 |
|------|------|
| 1 | `username`으로 사용자 조회 |
| 2 | `phone`이 저장된 phone과 일치 (불일치 시 `PHONE_MISMATCH`) |
| 3 | `newPassword` 정규식 검증 (영문+숫자+특수문자, min8) |
| 4 | BCrypt 비밀번호 업데이트 |

---

## 5. 요청 인증 필터 (JwtAuthenticationFilter)

`JwtAuthenticationFilter`는 `UsernamePasswordAuthenticationFilter` 앞에 등록됩니다.

| 단계 | 동작 |
|------|------|
| 1 | `Authorization` 헤더 읽기 |
| 2 | `"Bearer "` 접두사 제거 (substring 7) |
| 3 | `validateToken` |
| 4 | `CustomUserDetailsService.loadUserByUsername`로 사용자 로딩 |
| 5 | `SecurityContext`에 인증 설정 |

`CustomUserDetails`는 `ROLE_USER` 권한을 부여합니다.

---

## 6. SecurityConfig 설정

| 항목 | 값 |
|------|-----|
| permitAll 경로 | `/health`, `/health/**`, `/auth/**`, `/actuator/**`, `/test/**`, `/market/**`, `/company/**` |
| 그 외 경로 | 인증 필요 |
| 세션 | STATELESS |
| CSRF | 비활성화 |
| CORS | WebConfig (origins `localhost:5173`/`5174`/`3000`) |
| 비밀번호 | `BCryptPasswordEncoder` |
| 필터 순서 | `JwtAuthenticationFilter` → `UsernamePasswordAuthenticationFilter` 이전 |

---

## 7. 앱 JWT vs KIS 토큰 헤더 차이

앱 내부 인증과 KIS API 호출은 헤더 구성이 다릅니다.

| 구분 | 헤더 |
|------|------|
| 앱 내부 API | `Authorization: Bearer {JWT}` |
| KIS API 호출 | `authorization: Bearer {KIS_TOKEN}` + `appkey` + `appsecret` + `tr_id` + `custtype=P` |

KIS 토큰 발급/캐싱 상세는 [KIS_API_GUIDE.md](KIS_API_GUIDE.md)를 참고하세요.

---

## 8. 인증 관련 ErrorCode

| 대역 | 코드 |
|------|------|
| 2000s (auth) | `INVALID_TOKEN=2002`, `REFRESH_TOKEN_REVOKED=2004` |
| 3000s (user) | `USERNAME_DUPLICATE=3002`, `EMAIL_DUPLICATE=3003`, `PASSWORD_MISMATCH=3004`, `PHONE_MISMATCH=3005` |
| 4000s (KIS) | `KIS_ACCOUNT_NOT_FOUND=4000`, `KIS_OAUTH_FAILED=4004` |

---

## 9. WebAuthn 생체/패스키 로그인

비밀번호 로그인에 더해 WebAuthn(FIDO2) 기반 생체/패스키 로그인을 지원합니다. 단말의 플랫폼 인증기(Face ID/Touch ID/Windows Hello 등)에 저장된 패스키로 비밀번호 없이 로그인합니다.

### 9.1 자격증명 저장 (`webauthn_credentials`)

| 컬럼 | 설명 |
|------|------|
| `credential_id` | 인증기가 발급한 자격증명 ID (Base64URL), unique |
| `user_id` | 소유 사용자 FK→`users(id)` |
| `public_key` | 자격증명 공개키 (검증용) |
| `sign_count` | 서명 카운터 (복제 탐지) |
| `user_handle` | WebAuthn userHandle = `user_id` |
| `created_at` | 등록 시각 |

- **credential ↔ userId 매핑은 1:1**입니다. `webauthn_credentials`의 `user_handle`에 `user_id`를 그대로 저장하여, usernameless 로그인 시 단말이 보낸 자격증명으로 사용자를 역조회합니다.

### 9.2 enroll(등록) — 가입 이후, JWT 필요

회원가입(`POST /auth/register`)을 마친 뒤 **로그인된 상태(JWT)** 에서 패스키를 등록합니다.

| 단계 | 엔드포인트 | 접근 | 동작 |
|------|-----------|------|------|
| 1 | `POST /auth/webauthn/register/start` | 인증 필요 (JWT) | 등록용 challenge·RP 정보·user 정보 반환 (서버가 challenge 보관) |
| 2 | (단말) | - | 플랫폼 인증기로 자격증명 생성 (생체 인증 프롬프트) |
| 3 | `POST /auth/webauthn/register/finish` | 인증 필요 (JWT) | 클라이언트 응답(attestation) 검증 → `webauthn_credentials`에 `credential_id`/`public_key`/`user_handle=userId` 저장 |

### 9.3 login(로그인) — public, usernameless 패스키

| 단계 | 엔드포인트 | 접근 | 동작 |
|------|-----------|------|------|
| 1 | `POST /auth/webauthn/login/start` | public (permitAll) | 로그인용 challenge 반환 (usernameless — username 불필요) |
| 2 | (단말) | - | 단말에 저장된 패스키로 서명 (생체 인증 프롬프트) |
| 3 | `POST /auth/webauthn/login/finish` | public (permitAll) | assertion 서명·challenge·`sign_count` 검증 → `user_handle`(=userId)로 사용자 조회 → access/refresh token 발급 (기존 비밀번호 로그인과 동일 토큰 구조) |

> usernameless: 사용자가 아이디를 입력하지 않습니다. 단말이 반환한 자격증명의 `user_handle`(=userId)로 사용자를 식별합니다.

### 9.4 RP(Relying Party) 설정 · HTTPS 요구사항

| 항목 | 값 |
|------|-----|
| `rp-id` | WebAuthn Relying Party ID (서비스 도메인) |
| `origins` | 허용 origin 목록 (단말이 보낸 origin 검증) |

- **HTTPS 필수**: WebAuthn(`navigator.credentials`)은 보안 컨텍스트에서만 동작합니다. `localhost`는 예외로 허용되지만, 그 외 도메인은 **HTTPS**가 아니면 등록/로그인이 불가합니다. `rp-id`/`origins`는 실제 서비스 도메인과 일치해야 합니다.

---

## 10. 관련 문서

- [../README.md](../README.md) — 프로젝트 개요 및 실행 방법
- [API_DESIGN.md](API_DESIGN.md) — 전체 REST API 명세
- [KIS_API_GUIDE.md](KIS_API_GUIDE.md) — KIS Open API 연동 가이드
- [ARCHITECTURE.md](ARCHITECTURE.md) — 시스템 아키텍처
- [STATUS.md](STATUS.md) — 구현 현황

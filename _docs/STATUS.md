# 전체 개발 현황 (Development Status)

> 프로젝트 전체의 개발 현황을 추적하는 **허브 문서**입니다. MVP(대학원 최종 프로젝트, 단일 사용자·KIS 모의투자 전제)는 종료되었고, 현재 전체 기능 개발로 확장 중입니다. 모듈 내부의 상세 진행 상황은 각 모듈 `_docs/STATUS.md`를 참고하세요.

**범례**: ✅ 완료 · 🔄 진행 중 · 📅 계획 · ⏸️ 대기 · ⚠️ 부분/임시

---

## 1. 큰 기능 단위 현황

| 영역 | 상태 | 비고 |
|------|------|------|
| 🔐 로그인/인증 | ✅ | JWT + RefreshToken, 회원가입(2단계), 자동 로그인, 로그아웃, 비밀번호 재설정 |
| 👤 사용자/설정 | ✅ | 프로필 조회·수정, 설정(관심 자산 순위·알림·다크모드), 회원 탈퇴 |
| 🤖 투자 봇 (거래 설정·보유 종목) | ✅ | 거래 설정 CRUD, 봇 활성화 토글, KIS 보유 종목 조회 |
| 📊 거래내역 | ✅ | KIS 직접 조회(최근 3개월), DB 미저장(정합성 우선) |
| 💹 매매 실행 (주문) | 🔄 | api-server 주문 API 완료(수동 주문도 `trade_history` 기록), ai-agent Kafka 발행 경로 e2e 실계좌 검증 남음 |
| 🧠 AI 분석 파이프라인 | ✅ | ai-agent 6단계 파이프라인 구현 + DB 적재 + api-server 조회 API + web-app 화면 렌더까지 연동 완료 |
| 📈 AI 분석 화면 (web-app) | ✅ | `MarketAnalysisView`(히트맵·감성·수급·5일 전망)·`CompanyDetailView`(종목 상세) 실연동. matplotlib PNG 차트 생성만 미구현(클라이언트 렌더로 대체) |

---

## 2. 모듈 간 연동 매트릭스

| 연동 구간 | 상태 | 비고 |
|----------|------|------|
| web-app → api-server (인증) | ✅ | 로그인/회원가입/토큰 갱신/로그아웃, 401 자동 리프레시 |
| web-app → api-server (자산·거래내역·설정·봇) | ✅ | 보유 종목, 거래내역, 거래 설정, 사용자 설정 |
| web-app → api-server (시장 분석·종목 상세) | ✅ | `MarketAnalysisController`/`CompanyController` 구현 및 `MarketAnalysisView`/`CompanyDetailView`에서 실연동 |
| api-server → KIS API | ✅ | 주문/잔고/시세/체결내역 (모의투자) |
| api-server → DART API | ✅ | 기업 재무·공시 (`CompanyController`) |
| api-server ⇄ PostgreSQL | ✅ | Liquibase 스키마, JPA 연동 |
| ai-agent ⇄ PostgreSQL | ✅ | 분석 결과·예측·판단·안전망 필터 적재 |
| ai-agent → KIS / DART / News | ✅ | 분석용 원천 데이터 수집 |
| ai-agent → Gemini API | ✅ | 11 피처 기반 매수/매도 판단 |
| ai-agent → api-server (매매 실행) | 🔄 | Kafka `trade.order.requested`/`trade.order.result` 경로(REST 아님, 계약 검증 완료). 3개 서비스 동시 기동한 실계좌 e2e는 아직 미검증 |
| web-app(AI 분석 화면) ← ai-agent 결과 | ✅ | api-server 경유로 노출 완료 |

> ai-agent가 DB에 쓴 분석 결과를 web-app이 **api-server를 통해 조회**하는 구조입니다(직접 호출 아님). 이 경로의 화면 연동이 남은 핵심 통합 작업입니다.

---

## 3. 모듈별 상세 진행 (요약 + 링크)

### web-app (Vue 3)
인증·자산·거래내역·설정·봇(BotView)·AI 분석 화면 API 연동 완료.
→ 상세: [`web-app/_docs/STATUS.md`](../web-app/_docs/STATUS.md) · 화면 설계: [`web-app/_docs/SCREENS.md`](../web-app/_docs/SCREENS.md)

### api-server (Spring Boot)
인증(JWT + RefreshToken), 사용자/설정, 자산, 거래(KIS 직접 조회), 시장 분석/종목 상세 조회 API 구현. 예외 체계(`GlobalExceptionHandler`), CORS, Jasypt 암호화 적용.
→ 상세: [`api-server/_docs/STATUS.md`](../api-server/_docs/STATUS.md) · 인증 흐름: [`api-server/_docs/AUTHENTICATION_FLOW.md`](../api-server/_docs/AUTHENTICATION_FLOW.md)

### ai-agent (FastAPI)
6단계 파이프라인(휴장일 체크 → 필터링 → 3축 분석 → Gemini 판단 → 안전망 필터 → 매매 실행) 구현. 분석 결과 DB 적재.
→ 상세: [`ai-agent/_docs/STATUS.md`](../ai-agent/_docs/STATUS.md) · 기능 설계: [`ai-agent/_docs/PIPELINE_DESIGN.md`](../ai-agent/_docs/PIPELINE_DESIGN.md)

---

## 4. 기능별 구현 현황 (모듈 교차)

### 🔐 인증
| 기능 | api-server | web-app | 연동 |
|------|-----------|---------|------|
| 로그인 (`POST /auth/login`) | ✅ | `LoginView` | ✅ |
| 회원가입 2단계 (`POST /auth/register`) | ✅ | `RegisterView` + `RegisterFinanceView` | ✅ |
| 아이디/이메일 중복확인 | ✅ | ✅ | ✅ |
| 자동 로그인 / 토큰 갱신 (`POST /auth/refresh`) | ✅ | `stores/auth.js` + axios 인터셉터 | ✅ |
| 로그아웃 (`POST /auth/logout`) | ✅ | ✅ | ✅ |
| 비밀번호 재설정 (`POST /auth/reset-password`) | ✅ | `ResetPasswordView` | ✅ |
| 휴대폰 인증 | ⚠️ 미구현 | ⚠️ 미구현 | ⚠️ |

### 👤 사용자/설정
| 기능 | api-server | web-app | 연동 |
|------|-----------|---------|------|
| 프로필 조회/수정 (`/users/me`) | ✅ | `ProfileView` | ✅ |
| 설정 조회/수정 (`/users/settings`) | ✅ | `SettingsView` | ✅ |
| 관심 자산 순위(JSONB)·알림·다크모드 | ✅ | ✅ (드래그앤드롭) | ✅ |
| 회원 탈퇴 (`DELETE /users/me`) | ✅ | ✅ | ✅ |

### 🤖 투자 봇
| 기능 | api-server | web-app | 연동 |
|------|-----------|---------|------|
| 거래 설정 조회/수정 (`/users/trade-config`) | ✅ | `BotView` | ✅ |
| 봇 활성화 토글 (`is_active`) | ✅ | ✅ | ✅ |
| 보유 종목 조회 (`GET /trading/holdings`, KIS `VTTC8434R`) | ✅ | `BotView` 카드 | ✅ |
| 보유 종목 AI 분석 표시 | ✅ | ✅ (`BotView`) | ✅ |

### 📊 거래/매매
| 기능 | api-server | web-app | 연동 |
|------|-----------|---------|------|
| 거래내역 (`GET /trading/history`, KIS `VTTC0081R`) | ✅ | `TransactionsView` (기간필터 동작) | ✅ |
| 매수 (`POST /trading/buy`, KIS `VTTC0802U`) | ✅ | `TradingView` | ✅ |
| 매도 (`POST /trading/sell`, KIS `VTTC0801U`) | ✅ | `TradingView` | ✅ |
| 미체결 조회 (`GET /trading/pending-orders`, daily-ccld 필터) | ✅ | `TradingView`/`TransactionsView` | ✅ |
| 실시간 호가 (REST) (`GET /stocks/{code}/orderbook`, KIS `FHKST01010200`) | ✅ | `TradingView` | ✅ |
| 실시간 시세 WebSocket (호가/체결가, Phase 1) (`/ws/realtime?token={JWT}`) | ✅ | `TradingView`·`AssetDetailView`·`ProfileView`·`App.vue`(체결통보 전역 구독) | ✅ |
| 매수가능 조회 (`GET /trading/orderable`, KIS `VTTC8908R`) | ✅ | `TradingView` | ✅ |
| 종목 검색·시세 (`/stocks/search`, `/stocks/{code}/price`) | ✅ | `SearchView` | ✅ |
| 관심종목 (`/favorites` GET/POST/DELETE) | ✅ | `FavoritesView` | ✅ |
| 해외주식(US) 매매·표시·검색 (`/overseas/*`, `/stocks/search?market=US`) | ✅ | `TradingView`(US 지정가)·`AssetDetailView`(해외탭)·`SearchView`(해외) | ✅ |

> **국내 정규 매수/매도는 화면 라벨과 무관하게 항상 시장가로 체결됩니다**(`ORD_DVSN="01"`, 가격 입력값은 매수여력 검증에만 사용). 화면 라벨은 "정규장 (시장가)"로 표기(2026-08 QA에서 "지정가" 오표기 수정). 실제 지정가 주문을 지원하는 것은 예약주문 폼뿐입니다.
>
> 수동 웹 주문도 `trade_history`에 기록되어(2026-08 QA에서 추가) 홈 화면 "최근 거래"에 자동매매 주문과 함께 표시됩니다.
> 해외주식(US)은 모의 **지정가 전용**이며 잔고(`VTTS3012R`)·매수(`VTTT1002U`)·매도(`VTTT1006U`)·현재가(`HHDFS76200200`)를 사용. **해외 호가·실시간 시세·미국 외 타국가는 미지원**(현재가는 real quote 도메인). 코인은 비활성 유지.

> **실시간 시세 WebSocket (Phase 1)**: api-server가 KIS WebSocket 브리지 `/ws/realtime`를 제공(Browser ⇄ Spring ⇄ KIS upstream). 국내 `H0STASP0`(호가)/`H0STCNT0`(체결가), 미국 `HDFSASP0`/`HDFSCNT0`. **체결통보(`H0STCNI0`/`H0STCNI9`, 국내)는 Phase 2 구현**(플래그 `kis.realtime.fills.enabled` 뒤, HTS ID·AES·유저당 연결; 해외 `H0GSCNI0` 보류). **HARD LIMIT — 라이브 데이터는 실계좌 키 + 장중이 필요하며, 모의(mock) 키·장외 시간에는 스트림이 흐르지 않습니다. 체결통보는 추가로 HTS ID 설정 + 실제 체결 필요.** 상세: [`api-server/_docs/KIS_API_GUIDE.md`](../api-server/_docs/KIS_API_GUIDE.md) §5

> 거래내역은 데이터 정합성을 위해 **DB에 저장하지 않고 KIS API를 직접 조회**합니다. (TR_ID는 `VTTC0081R`이 올바른 값 — 구버전 `VTTC8001R`은 버그였고 수정됨. [`api-server/_docs/KIS_API_GUIDE.md`](../api-server/_docs/KIS_API_GUIDE.md))

### 🧠 AI 분석
| 기능 | ai-agent | api-server | web-app | 연동 |
|------|----------|-----------|---------|------|
| 6단계 분석 파이프라인 | ✅ | - | - | - |
| 분석 결과 DB 적재 | ✅ | - | - | - |
| 분석 결과 조회 API (`/market/*`) | - | ✅ | - | ✅ |
| AI 분석 화면(종합·정량·감성·시계열) | - | - | ✅ `MarketAnalysisView`/`CompanyDetailView` | ✅ |

> matplotlib PNG 차트 생성(`heatmap_today.png` 등)은 여전히 미구현. web-app은 DB 원시 데이터를 받아 클라이언트에서 직접 렌더한다.

### 📰 뉴스
| 기능 | api-server | web-app | 연동 |
|------|-----------|---------|------|
| 뉴스 목록/상세 (`GET /news`, `GET /news/{id}`) | ✅ | `NewsView`/`NewsDetailView` | ✅ |

---

## 5. 잔여 작업 / 로드맵

| 항목 | 상태 | 비고 |
|------|------|------|
| AI 분석 결과 차트 이미지 | 📅 | matplotlib PNG 생성 단계 미구현. 현재는 web-app이 DB 원시 데이터를 클라이언트에서 직접 렌더 |
| ai-agent → api-server 매매 실행 e2e | 🔄 | Stage 6은 Kafka `trade.order.requested` 발행 → api-server `TradeOrderConsumer` 소비 → `trade.order.result`로 회신하는 경로다(REST `/api/trading/execute` 등은 존재한 적 없음 — 과거 이 항목의 "경로 불일치" 서술은 2026-08 QA에서 오류로 확인되어 삭제). 메시지 계약(필드 8개 × 2)은 코드 대조로 검증됐으나, 3개 서비스를 동시 기동한 실계좌 e2e는 아직 수행되지 않았다 |
| 휴대폰 인증 실연동 | ⚠️ | web-app·api-server 모두 관련 코드 없음(과거 "임시 우회"가 아니라 **미구현** — 2026-08 QA에서 정정) |
| KIS 실계정 연동 | ⏸️ | 현재 모의투자, `user_kis_accounts`에 실키 입력 시 동작 |
| 멀티 유저 / 운영 배포 | 📅 | `product` Liquibase context로 확장 예정 |
| 실시간 공시 확장 · 다변량 시계열(LSTM) | 📅 | 향후 분석 고도화 |
| 국내 정규주문 지정가 지원 | 📅 | 현재 항상 시장가 체결(설계 의도로 문서화됨, `api-server/_docs/API_DESIGN.md`). 실제 지정가가 필요하면 `TradingService`의 `ORD_DVSN` 분기 추가 + KIS 모의계좌 실검증 필요 |
| `refresh_tokens` 유저당 활성 토큰 1개 정책 | ✅ | 2026-08 QA에서 부분 유니크 인덱스(v1.27)로 DB 레벨 강제 완료 |
| `stock_realtime_price` 테이블 미사용 | ⚠️ | 적재하는 코드가 없어 관련 컬럼이 항상 null. 현재 화면이 이 컬럼을 소비하지 않아 무해하나, 정리(DTO에서 제거) 또는 적재 주체 지정 중 택일 필요 |

---

## 6. 관련 문서

- 전체 문서 지도: [`README.md`](README.md)
- 시스템 아키텍처: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- 설치·실행: [`USAGE.md`](USAGE.md)
- DB 스키마: [`../database/README.md`](../database/README.md)

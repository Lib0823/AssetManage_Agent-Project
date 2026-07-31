---
name: vue-frontend-conventions
description: web-app(Vue3 SPA) 코드를 작성/수정할 때 반드시 사용. services/api.js 경유 원칙, ApiResponse 소비 패턴, mockData.js 목업 전략, graceful-degrade UI(KIS 점검 배너 등), 라우팅/PWA 설정을 다룬다. "화면 추가", "뷰 만들어줘", "API 연동", "목업 데이터" 같은 요청에서 트리거된다.
---

# web-app 컨벤션

## 왜 이 규칙들이 존재하는가
web-app은 ai-agent를 직접 호출하지 않고 api-server만 경유한다(BFF 패턴) — CORS 문제를 피하고 KIS/DART 자격증명을 브라우저에 노출하지 않기 위해서다. 이 경계를 우회하면 보안 가정이 깨진다.

## API 호출은 반드시 services/api.js를 통한다
- `services/api.js`가 axios 인스턴스에 요청/응답 인터셉터를 걸어 401 시 RefreshToken으로 자동 갱신한다. 컴포넌트에서 axios를 직접 import하면 이 갱신 로직을 우회하게 되어 토큰 만료 시 사용자가 갑자기 로그아웃된 것처럼 보인다.
- 모든 응답은 `ApiResponse<T>`(`{success, message, data}`)로 온다. `data`만 꺼내 쓰고, `success=false`일 때는 `message`를 사용자에게 보여준다.

## mockData.js 우선 전략
- 백엔드 API가 아직 없거나 계약이 확정 전이면 `services/mockData.js`에 화면별 목업을 추가하고 그것으로 먼저 UI를 완성한다 — API가 나올 때까지 기다리지 않는다.
- 실제 계약이 확정되면 목업 함수를 실제 API 호출로 교체하되, 함수 시그니처(반환 shape)는 목업과 동일하게 맞춰 컴포넌트 코드를 다시 쓰지 않도록 한다.

## Graceful degrade UI
- KIS 점검/장애, quote 비활성 등 외부 연동 실패는 크래시가 아니라 안내 배너로 처리한다(`KisMaintenanceNotice.vue`, `kisStatus.js`의 `isKisOutageError()` 패턴). 새 화면에서 KIS/DART 데이터를 다룰 때도 이 패턴을 따른다 — `notice` 필드가 응답에 있으면 그것을 배너로 보여주고 나머지 UI는 정상 렌더링한다.

## 실시간 데이터
- 실시간 호가/체결가는 `/ws/realtime?token={JWT}` WebSocket을 통해서만 받는다. `services/realtime.js`의 `RealtimeClient`를 재사용하고, 브라우저에서 KIS 소켓에 직접 연결하는 코드를 작성하지 않는다.

## 라우팅/상태
- 라우트는 lazy-loaded view + `meta.showBottomNav`로 하단 네비게이션 노출 여부를 제어한다.
- 전역 상태는 Pinia(`stores/auth.js` 등), UI 설정은 LocalStorage. 새 전역 상태가 필요하면 기존 스토어를 확장할지 새 스토어를 만들지는 관심사 분리 기준(인증 vs UI 설정 vs 실시간 연결)을 따른다.
- Dev 모드(`import.meta.env.DEV`)는 인증 체크를 건너뛴다 — 이 분기를 프로덕션 빌드에서 우회하는 코드를 추가하지 않는다.

## 검증
- 변경 후 `npm run lint`를 실행한다.
- UI 변경은 가능하면 `npm run dev`로 실제 화면을 확인한 뒤 완료로 보고한다 — 타입체크/린트 통과만으로 "동작 확인 완료"라고 말하지 않는다.

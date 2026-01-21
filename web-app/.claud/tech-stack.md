# Web App - Tech Stack

## 핵심 기술

| 항목 | 기술 | 버전 |
|------|------|------|
| Framework | Vue | 3.x (Composition API) |
| Build Tool | Vite | 6.x |
| Package Manager | npm | - |

## 주요 라이브러리

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| vue-router | 4.x | 라우팅 |
| pinia | 2.x | 상태 관리 |
| axios | 1.6.x | HTTP 클라이언트 |
| vant | 4.9.x | 모바일 UI 컴포넌트 |

## UI/스타일

| 항목 | 값 |
|------|-----|
| UI Library | Vant (모바일 최적화) |
| CSS | Scoped CSS |
| 아이콘 | Vant Icons |

### 색상 시스템

```css
--primary: #4318FF;    /* 보라 - 주요 액션 */
--secondary: #05CD99;  /* 민트 - 상승/긍정 */
--accent: #FFB547;     /* 주황 - 강조 */
--danger: #FF5252;     /* 빨강 - 하락/위험 */
--background: #F4F7FE; /* 배경 */
--text: #2B3674;       /* 텍스트 */
```

## 개발 도구

| 도구 | 용도 |
|------|------|
| ESLint | 코드 린트 |
| Prettier | 코드 포맷팅 |
| Vue DevTools | 디버깅 |

## 환경 변수

```bash
# .env.local
VITE_API_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws

# .env.production
VITE_API_URL=https://api.asset-agent.com
VITE_WS_URL=wss://api.asset-agent.com/ws
```

## 브라우저 지원

- Chrome (최신)
- Safari (iOS)
- Samsung Internet

> 모바일 우선 설계

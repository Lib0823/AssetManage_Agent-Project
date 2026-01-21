# Web App - CLI Commands

## 개발

```bash
# 의존성 설치
npm install

# 개발 서버 실행 (HMR)
npm run dev

# 개발 서버 실행 (특정 포트)
npm run dev -- --port 3000

# 개발 서버 실행 (네트워크 노출)
npm run dev -- --host
```

## 빌드

```bash
# 프로덕션 빌드
npm run build

# 빌드 미리보기
npm run preview

# 빌드 분석
npm run build -- --report
```

## 코드 품질

```bash
# ESLint 검사
npm run lint

# ESLint 자동 수정
npm run lint -- --fix

# Prettier 포맷팅
npm run format

# 타입 체크 (Vue 컴포넌트)
npm run type-check
```

## 테스트

```bash
# 단위 테스트 (Vitest)
npm run test

# 테스트 감시 모드
npm run test:watch

# 커버리지 리포트
npm run test:coverage

# E2E 테스트 (Playwright)
npm run test:e2e
```

## 유틸리티

```bash
# 의존성 업데이트 확인
npm outdated

# 보안 취약점 확인
npm audit

# 캐시 정리
npm cache clean --force

# node_modules 재설치
rm -rf node_modules && npm install
```

## 환경별 실행

```bash
# 로컬 환경 (기본)
npm run dev

# 개발 서버 연결
npm run dev -- --mode development

# 프로덕션 모드 로컬 테스트
npm run build && npm run preview
```

## Vite 관련

```bash
# Vite 설정 확인
npx vite --help

# 의존성 사전 번들링 강제
npx vite --force

# 빌드 시 소스맵 생성
npm run build -- --sourcemap
```

## 주요 개발 URL

| URL | 용도 |
|-----|------|
| http://localhost:3000 | 개발 서버 |
| http://localhost:3000/__inspect | Vue DevTools |

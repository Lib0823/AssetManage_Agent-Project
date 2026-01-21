# Architecture

## 시스템 구성

**3-Tier 구조:** Client (Vue 3) → API Server (Spring Boot) → Database (PostgreSQL)

### 서비스 목록

| 서비스 | 포트 | 역할 |
|--------|------|------|
| web-app | 3000 | Vue 3 프론트엔드 |
| api-server | 8080 | Spring Boot REST API + WebSocket |
| ai-agent | 5000 | FastAPI AI 서비스 |
| postgres | 5432 | 메인 데이터베이스 |
| redis | 6379 | 캐시 (시세, 세션) |
| elasticsearch | 9200 | 로그/검색 |
| prometheus | 9090 | 메트릭 수집 |
| grafana | 3001 | 모니터링 대시보드 |

## 주요 통신 흐름

### 1. 인증
- 로그인 → JWT 발급 (Access 1시간, Refresh 7일)
- HttpOnly Cookie 저장
- Refresh Token으로 갱신

### 2. 실시간 시세
- WebSocket 연결 → 종목 구독 → 한국투자증권 WebSocket → Redis 캐싱 (1초) → 클라이언트 전송

### 3. 주문 실행
- POST 요청 → 주문 검증 → 한국투자증권 API → PostgreSQL 저장 → Elasticsearch 로그

### 4. AI 자동매매
- Scheduler → AI Agent 분석 → PGMQ 메시지 → API Server → 주문 실행

## 캐싱 전략 (Redis)

| Key 패턴 | TTL | 용도 |
|----------|-----|------|
| stock:{symbol}:price | 1초 | 실시간 시세 |
| stock:{symbol}:orderbook | 1초 | 호가 정보 |
| session:{userId} | 1시간 | 사용자 세션 |
| news:list:{page} | 5분 | 뉴스 목록 |

## 로그 저장 (Elasticsearch)

| Index 패턴 | 내용 |
|------------|------|
| api-logs-{yyyy.MM.dd} | API 요청/응답 |
| trading-logs-{yyyy.MM.dd} | 거래 내역 |
| error-logs-{yyyy.MM.dd} | 에러 로그 |
| ai-logs-{yyyy.MM.dd} | AI 분석 결과 |

## 확장 계획

**현재:** Monolith with Domain (auth, trading, asset, news 패키지)
**향후:** MSA 전환 가능 (각 도메인을 독립 서비스로 분리)

**메시지 큐:**
- 현재: PGMQ (PostgreSQL 기반)
- 향후: Kafka 또는 RabbitMQ

## 성능/안정성

### Rate Limiting
- 일반 API: 100 req/min
- 주문 API: 10 req/min

### Circuit Breaker (Resilience4j)
- 외부 API 장애 시 캐시된 데이터 반환

### Health Check
- `/actuator/health` - 전체 상태
- `/actuator/health/redis`, `/actuator/health/db` - 개별 상태

## CI/CD

GitHub Actions: Push → Build & Test → Docker Build → Deploy → Health Check

## 백업

- PostgreSQL: 매일 자정 자동 백업 (일별 7일, 주별 4주, 월별 12개월 보관)
- Elasticsearch: 주 1회 스냅샷

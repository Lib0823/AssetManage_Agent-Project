# API Server - Tech Stack

## 핵심 기술

| 항목 | 기술 | 버전 |
|------|------|------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 4.1.0 |
| Build | Gradle | 8.x |

## 주요 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| Spring Security | 인증/인가 |
| Spring Data JPA | ORM |
| Spring WebSocket | 실시간 통신 |
| SpringDoc OpenAPI | Swagger API 문서 |
| Lombok | 보일러플레이트 제거 |
| MapStruct | DTO 매핑 |
| Resilience4j | Circuit Breaker |
| JUnit 5 + Mockito | 테스트 |

## 데이터베이스 연동

| DB | 용도 | 드라이버 |
|----|------|----------|
| PostgreSQL 16 | 메인 DB | postgresql |
| Redis 7 | 캐시, 세션 | lettuce |
| Elasticsearch 8 | 로그, 검색 | spring-data-elasticsearch |

## 외부 API 클라이언트

| API | 통신 방식 |
|-----|-----------|
| 한국투자증권 | REST + WebSocket |
| (향후) 업비트 | REST |

## 환경 변수

```properties
# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/assetdb
DATABASE_USERNAME=admin
DATABASE_PASSWORD=

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# JWT
JWT_SECRET=
JWT_ACCESS_EXPIRATION=3600
JWT_REFRESH_EXPIRATION=604800

# 한국투자증권 API
KIS_API_KEY=
KIS_API_SECRET=
KIS_ACCOUNT_NUMBER=
```

# API Server - CLI Commands

## 빌드 & 실행

```bash
# 빌드
./gradlew build

# 빌드 (테스트 제외)
./gradlew build -x test

# 실행 (기본 프로파일)
./gradlew bootRun

# 실행 (특정 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# JAR 실행
java -jar build/libs/api-server-0.0.1-SNAPSHOT.jar
```

## 테스트

```bash
# 전체 테스트
./gradlew test

# 특정 클래스 테스트
./gradlew test --tests "UserServiceTest"

# 특정 메서드 테스트
./gradlew test --tests "UserServiceTest.findById_Success"

# 테스트 리포트 생성
./gradlew jacocoTestReport
```

## 의존성

```bash
# 의존성 목록
./gradlew dependencies

# 의존성 업데이트 확인
./gradlew dependencyUpdates
```

## 코드 품질

```bash
# 린트 체크 (Checkstyle)
./gradlew checkstyleMain

# 정적 분석 (SpotBugs)
./gradlew spotbugsMain
```

## 데이터베이스

### DDL 관리 전략

**현재 (설계 단계)**
```
database/
├── database-erd.sql          # 전체 DDL (설계용)
├── database-erd-diagram.md   # ERD 다이어그램
└── database-erd-summary.md   # 요약 문서
```

**개발 시작 후 (Flyway 마이그레이션)**
```
api-server/src/main/resources/db/migration/
├── V1__create_schemas.sql
├── V2__create_auth_tables.sql
├── V3__create_asset_tables.sql
├── V4__create_stock_tables.sql
└── ...
```

**전환 방법**
1. `database/database-erd.sql`을 스키마별로 분리
2. Flyway 네이밍 규칙: `V{버전}__{설명}.sql`
3. 이후 변경사항은 새 마이그레이션 파일로 추가

### Flyway 명령어

```bash
# 마이그레이션 실행
./gradlew flywayMigrate

# 마이그레이션 상태 확인
./gradlew flywayInfo

# 마이그레이션 초기화 (주의: 모든 테이블 삭제)
./gradlew flywayClean
```

## 문서

```bash
# Swagger UI 접속 (실행 후)
# http://localhost:8080/swagger-ui.html

# OpenAPI JSON
# http://localhost:8080/v3/api-docs
```

## Docker

```bash
# 이미지 빌드
docker build -t api-server:latest .

# 컨테이너 실행
docker run -p 8080:8080 api-server:latest
```

## 유용한 엔드포인트

| 경로 | 용도 |
|------|------|
| /actuator/health | 헬스 체크 |
| /actuator/info | 앱 정보 |
| /actuator/metrics | 메트릭 |
| /swagger-ui.html | API 문서 |

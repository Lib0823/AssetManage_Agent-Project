# api-server 문서 인덱스

AI 주식 자동매매 시스템의 백엔드 모듈이다. Spring Boot(Java 21)로 사용자 인증, KIS 모의투자 매매 실행, 시장/AI 분석 데이터 조회 REST API를 제공한다.

이 디렉터리(`api-server/_docs/`)만 읽으면 (1) 전체 구조, (2) 진행 상황, (3) 기능 설계를 코드 기준으로 파악할 수 있도록 정리했다. 모든 명세는 `src/main/java/com/inbeom/apiserver/**` 실제 코드에 맞춰 작성되었다.

---

## 문서 지도

| 문서 | 설명 |
|------|------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 패키지 구조, 레이어 흐름, 도메인/JPA 매핑, 외부 연동(KIS·DART), 보안 구성. **개발 전 필독** |
| [STATUS.md](./STATUS.md) | 엔드포인트·기능별 구현 진행 상황(완료/진행중/미착수) 표 |
| [USAGE.md](./USAGE.md) | 설치·환경변수·실행·빌드·테스트·트러블슈팅 (사용 방법) |
| [API_DESIGN.md](./API_DESIGN.md) | 12개 컨트롤러 52개 REST 엔드포인트 전체 명세 (해외주식 `/overseas/*` 포함) |
| [AUTHENTICATION_FLOW.md](./AUTHENTICATION_FLOW.md) | JWT 발급·검증·리프레시·로그아웃, KIS 계정 연동 인증 흐름 |
| [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) | KIS Open API 연동(이중 자격증명 경로, TR_ID 매핑, 토큰 캐싱), DART 연동 |
| [archive/](./archive/) | 일회성 수정 이력 보관(현재 `TRADE_HISTORY_FIX_SUMMARY.md`) |

읽는 순서 권장: `ARCHITECTURE` → `API_DESIGN` → `AUTHENTICATION_FLOW` / `KIS_API_GUIDE` → 필요 시 `STATUS`.

---

## 모듈 요약

| 항목 | 값 |
|------|-----|
| 언어/런타임 | Java 21 (LTS) |
| 프레임워크 | Spring Boot 4.1.0-SNAPSHOT, Spring Data JPA, Spring Security, Spring Validation |
| 인증 | JWT (jjwt 0.12.3, HMAC-SHA), BCrypt 비밀번호 |
| 암호화 | Jasypt `PBEWITHHMACSHA512ANDAES_256` (KIS appKey/appSecret) |
| DB | PostgreSQL, Liquibase 마이그레이션 |
| 빌드 | Gradle |
| 서버 포트 | `7070`, context-path `/api` (full URL: `http://localhost:7070/api/...`) |
| 외부 연동 | KIS Open API(모의투자 매매 + 실전 시세/재무), DART(공시·재무) |

---

## 개발 명령어

```bash
cd api-server

./gradlew build      # 빌드
./gradlew test       # 테스트 (JUnit 5)
./gradlew bootRun    # 개발 서버 실행 (port 7070)
./gradlew clean      # 빌드 산출물 정리
```

로컬 실행 보조 스크립트는 `run-local.sh`를 참고한다.

---

## 빠른 시작

1. **DB 준비**: PostgreSQL에 `financemanage` 데이터베이스 생성. 접속 정보 기본값은 `application.yml`의 `spring.datasource`(`localhost:5432`, `admin`/`admin1234`). 스키마는 Liquibase가 부팅 시 `db/changelog/db.changelog-master.yaml`(context `mvp`)로 적용한다.
2. **환경 변수**: `.env.example`를 복사해 `.env` 작성. 최소 `JWT_SECRET`, `JASYPT_PASSWORD`가 필요하다. 시세/재무·DART 연동을 쓰려면 `KIS_QUOTE_APP_KEY`/`KIS_QUOTE_APP_SECRET`, `DART_API_KEY`를 채운다(비우면 해당 필드는 null로 graceful degrade). `.env`는 `DotenvEnvironmentPostProcessor`가 부팅 시 로드하며, OS 환경 변수가 우선한다.
3. **실행**: `./gradlew bootRun` 후 `GET http://localhost:7070/api/health`로 헬스 체크.
4. **사용자/KIS 계정**: 매매 흐름(`/assets`, `/trading`)은 로그인 시 KIS 계정 연동이 필수다. KIS appKey/appSecret는 `user_kis_accounts`에 Jasypt로 암호화 저장된다. 상세는 [KIS_API_GUIDE.md](./KIS_API_GUIDE.md) 참고.

---

## 보안 주의

- `.env`, KIS appKey/appSecret 평문, `JWT_SECRET`, `JASYPT_PASSWORD`는 절대 커밋하지 않는다(`.gitignore` 등록 필수).
- `users.password`는 BCrypt 단방향 해시, `user_kis_accounts.app_key/app_secret`는 Jasypt 양방향 암호화로 저장한다.
- 로깅 레벨을 `org.springframework.web=DEBUG`로 올리면 RestTemplate 요청 본문에 KIS 자격증명이 평문으로 남을 수 있다(`application.yml` 주석 참고). 디버깅 시에만 일시적으로 사용한다.

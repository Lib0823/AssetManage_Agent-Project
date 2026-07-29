# api-server

AI 주식 자동매매 시스템의 백엔드 모듈. Spring Boot(Java 21) 기반으로 사용자 인증(JWT), KIS 모의투자 매매 실행, 시장·AI 분석 데이터 조회 REST API를 제공한다.

## 문서

전체 구조·진행 상황·기능 설계는 **[`_docs/`](./_docs/README.md)** 를 참조하라.

| 문서 | 내용 |
|------|------|
| [_docs/README.md](./_docs/README.md) | 문서 인덱스, 모듈 요약, 개발 명령어, 빠른 시작 |
| [_docs/ARCHITECTURE.md](./_docs/ARCHITECTURE.md) | 패키지 구조·레이어·도메인/JPA·외부 연동·보안 |
| [_docs/STATUS.md](./_docs/STATUS.md) | 기능·엔드포인트별 구현 진행 상황 |
| [_docs/USAGE.md](./_docs/USAGE.md) | 설치·환경변수·실행·빌드·테스트·트러블슈팅 |
| [_docs/API_DESIGN.md](./_docs/API_DESIGN.md) | 12개 컨트롤러 REST 엔드포인트 명세 (해외주식 `/overseas/*` 포함) |
| [_docs/AUTHENTICATION_FLOW.md](./_docs/AUTHENTICATION_FLOW.md) | JWT 발급·검증·리프레시 인증 흐름 |
| [_docs/KIS_API_GUIDE.md](./_docs/KIS_API_GUIDE.md) | KIS·DART 연동 (TR_ID, 토큰 캐싱) |

## 개발 명령어

```bash
./gradlew build      # 빌드
./gradlew test       # 테스트
./gradlew bootRun    # 개발 서버 실행 (port 7070, context-path /api)
```

부팅 전 `.env.example`를 복사해 `.env`를 작성한다(최소 `JWT_SECRET`, `JASYPT_PASSWORD`). 자세한 설정은 [`_docs/README.md`](./_docs/README.md)의 빠른 시작을 참고한다.

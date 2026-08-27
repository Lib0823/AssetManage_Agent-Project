---
name: api-server-conventions
description: api-server(Spring Boot) 코드를 작성/수정할 때 반드시 사용. 컨트롤러·서비스·리포지토리 계층 구조, ApiResponse 응답 래핑, Liquibase changeset 작성, KIS TR_ID 변환, Jasypt 암호화, ErrorCode 대역 규칙을 다룬다. "컨트롤러 추가", "엔드포인트 만들어줘", "changelog 추가", "KIS 연동" 같은 요청에서 트리거된다.
---

# api-server 컨벤션

## 왜 이 규칙들이 존재하는가
api-server는 web-app과 ai-agent 양쪽이 의존하는 유일한 게이트웨이(BFF)다. 여기서 계약을 깨면 두 프론트가 동시에 영향받는다. 아래 규칙은 "형식을 맞추기 위해서"가 아니라 실제로 겪은 실패 모드를 막기 위한 것이다.

## 계층 구조
```
controller/  → service/  → repository/ (Spring Data JPA) | client/ (KIS·DART 외부 API)
```
- 컨트롤러는 얇게 유지한다: 요청 검증 + 서비스 호출 + `ApiResponse` 래핑만.
- `MarketAnalysisRepository`처럼 다중 JOIN이 필요한 조회는 `JpaRepository` 대신 `JdbcTemplate`을 쓰는 기존 패턴을 따른다(단순 CRUD가 아니라 리포팅성 쿼리이기 때문).

## ApiResponse — 절대 형태를 바꾸지 않는다
```json
{ "success": true, "message": "...", "data": { } }
```
`data`의 타입(T)만 엔드포인트마다 다르다. 이 래퍼를 벗기거나 필드명을 바꾸면 web-app의 axios 인터셉터와 모든 뷰가 동시에 깨진다. 새 엔드포인트를 추가할 때도 반드시 이 래퍼를 통과시킨다.

## ErrorCode 대역
| 대역 | 도메인 |
|------|--------|
| 1000s | 공통 |
| 2000s | 인증 |
| 3000s | 사용자 |
| 4000s | KIS |
| 5000s | 거래 |

새 에러를 추가할 때는 해당 대역 안에서 다음 빈 번호를 쓴다. `GlobalExceptionHandler`가 모든 예외를 `ApiResponse{success:false}`로 통일해 처리하므로, 컨트롤러에서 try/catch로 별도 응답을 만들지 않는다.

## Liquibase changelog 작성
- 스키마 변경은 **오직** `api-server/src/main/resources/db/changelog/mvp/vX.Y-설명.yaml`을 새로 추가하는 방식으로 한다 — 기존 changeset을 수정하지 않는다(이미 적용된 changeset을 고치면 다른 환경과 스키마가 어긋난다).
- `db.changelog-master.yaml`의 `include` 목록에 새 파일을 추가한다.
- `database/schema.sql`은 `pg_dump`로 생성되는 스냅샷이며 직접 편집 금지 — 손대야 한다면 `./database/generate-schema.sh`를 실행해 재생성한다.

## KIS 연동
- **실전투자 전용**이다(2026-08 QA에서 모의투자 지원 전체 제거 — `account_mode` 컬럼·`convertTrId`·모의 도메인 분기 모두 삭제됨). TR_ID는 국내 `TTTC*`, 해외 `TTTS*`/`TTTT*`를 직접 쓴다 — 잘못된 TR_ID를 하드코딩하지 않도록 주의(`api-server/_docs/archive/TRADE_HISTORY_FIX_SUMMARY.md`는 과거 이런 실수의 사례).
- 자격증명(`app_key`/`app_secret`)은 반드시 Jasypt(`PBEWITHHMACSHA512ANDAES_256`)로 암호화해서 저장한다.
- KIS/DART 응답 실패 시 크래시 대신 `notice` 필드가 있는 부분 응답(graceful degrade)을 반환하는 기존 패턴(`OverseasController`, `StockController`)을 따른다.

## 테스트
- JUnit 5 + Mockito. 변경 후 `./gradlew test`를 실행하고 결과를 그대로 보고한다. 실패가 알려진 이슈 때문인지 새 회귀인지 구분한다.

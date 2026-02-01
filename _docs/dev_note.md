# 개발 진행 사항 정리
### 비고
- 우선 주식(국내/해외)만 개발 진행.. 
- 추후 MSA 전환 고려하여 도메인 주도 개발(DDD)

### 진행 단계
1. **Frontend 개발 마무리 및 학습** 
2. API 설계서 작성
3. 한국투자증권 API 분리
4. 나머지 필요 데이터(ERD) 정리
5. NoSQL/RDB 데이터 분리
6. Table, Doc 구조 정리 및 생성 (Flyway 등 고려)
7. Backend 개발

---
### 학습 필요
- Super claude 사용 방법
- Frontend 사용 기술 (Vue3, vite, vant..)

---
### Backend
- frontend 개발이 끝나면 전체 화면 고려하여 API 설계서 작성
- 한국투자증권 API는 별도 분리
  - api 명세를 주고 가져올 수 있는 것들 확인 (주식, 계좌 정보 등..)
- API 설계서와 DB를 비교하여 데이터 검토 (필요 시 테이블 변경)
- 어떤 데이터를 만들어야 하나 정리.. 후 배치 모듈 검토

### Frontend
- ?

### Database
- 현재 ERD에서 NoSQL/RDB 분리
- 추후 Liquibase or flyway로 관리.. 또는 JPA?

### AI
- Backend 개발 시 AI쪽은 우선 Mock 데이터로 처리.. 
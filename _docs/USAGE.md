# 설치 및 실행 (Usage)

실행 방법은 두 가지입니다.
- **방법 A — Docker Compose 전체 실행**: Docker만 있으면 4개 서비스가 한 번에 뜸 (Java·Node·Python 불필요)
- **방법 B — 로컬 개발 실행**: DB만 도커, 세 앱은 로컬 (핫리로드·디버깅용, 런타임 사전 설치 필요)

모듈별 상세 사용법은 각 모듈 `_docs/USAGE.md`를 참고하세요.
- [`ai-agent/_docs/USAGE.md`](../ai-agent/_docs/USAGE.md) · [`api-server/_docs/USAGE.md`](../api-server/_docs/USAGE.md) · [`web-app/_docs/USAGE.md`](../web-app/_docs/USAGE.md)

---

## 방법 A — Docker Compose 전체 실행 (권장)

**사전 요구사항: Docker / Docker Compose 만 설치되어 있으면 됩니다.**

```bash
cp .env.example .env          # 키 입력 (아래 주의 참고)
docker compose up -d --build
```

> ⚠️ **ai-agent는 `KIS_APP_KEY`·`KIS_APP_SECRET`가 필수**입니다. 없으면 `KISClient`가 startup에서 `ValueError`를 던져 ai-agent 컨테이너만 재시작을 반복합니다(`docker compose logs ai-agent`로 확인). postgres·api-server·web-app은 키 없이도 정상 기동되며, Gemini·DART·KIS 시세 키는 비워도 기동됩니다(해당 외부 연동만 비활성).

기동되는 서비스:

| 서비스 | 접속 | 비고 |
|--------|------|------|
| postgres | localhost:5432 | DB: financemanage / admin / admin1234 |
| api-server | http://localhost:7070/api | 부팅 시 Liquibase가 스키마(17 테이블 + 4 뷰) 자동 마이그레이션 |
| ai-agent | http://localhost:8000 | 파이프라인 코어 |
| web-app | http://localhost:3000 | nginx가 `/api`를 api-server로 프록시 |

> ⚠️ **최초 빌드는 수~십 분** 걸립니다. ai-agent 이미지가 `torch`·`prophet`(C++ 컴파일)·`transformers`를 포함해 수 GB이며, KR-FinBERT 모델은 최초 실행 시 HuggingFace에서 다운로드되어 `hf-cache` 볼륨에 저장됩니다(이후 재사용).

```bash
docker compose logs -f          # 로그 확인
docker compose ps               # 상태 확인
docker compose down             # 중지 (volume 유지)
docker compose down -v          # 중지 + DB/모델 캐시 볼륨까지 삭제
```

---

## 방법 B — 로컬 개발 실행

DB만 도커로 띄우고 세 앱은 로컬에서 실행합니다. 다음 런타임이 **사전 설치**돼 있어야 합니다.

| 도구 | 버전 |
|------|------|
| Java | 21 (LTS) |
| Node.js | 20+ (`^20.19 || >=22.12`) |
| Python | 3.11+ |
| Docker | (DB 구동용) 또는 로컬 PostgreSQL 16 |

> **matplotlib 한글 폰트**: ai-agent 차트 생성 시 NanumGothic 폰트가 필요합니다(현재 차트 단계는 미구현). 미설치 시 한글이 깨질 수 있으나 분석 데이터에는 영향 없습니다.

### B-1. 데이터베이스 준비

```bash
docker compose up -d postgres   # PostgreSQL 16만 기동 (DB: financemanage / admin / admin1234)
```

스키마(17개 테이블 + 4개 뷰)는 **api-server 실행 시 Liquibase가 자동 마이그레이션**합니다(`api-server/src/main/resources/db/changelog/`). 수동 적용 참고용은 [`../database/schema.sql`](../database/schema.sql) ([테이블 목록](../database/README.md)).

직접 PostgreSQL을 설치해 쓸 경우 `financemanage` 데이터베이스를 만들고 각 모듈 설정에 접속 정보를 맞춰주세요.

### B-2. 환경 변수 설정

환경변수는 **저장소 최상위의 단일 `.env` 파일** 하나만 쓰면 됩니다(Docker·로컬 공통). **실제 `.env`는 커밋하지 않습니다.**

```bash
cp .env.example .env       # 루트에 한 번만 생성, 실제 키 입력
```

로컬 실행 시 자동으로 이 파일을 읽습니다:
- **api-server**: `DotenvEnvironmentPostProcessor`가 cwd에서 상위로 올라가며 `.env`를 탐색 → 루트 `.env` 자동 적용 (`run-local.sh`도 루트 `.env`를 우선 로드).
- **ai-agent**: `run_dev.sh`가 루트 `.env`를 로드하고, DB 접속값은 `POSTGRES_*` → `DB_*`(host=localhost)로 파생.

> 모듈별 `.env`(`ai-agent/.env`, `api-server/.env`)를 두면 루트 `.env`보다 우선 적용되는 선택적 override입니다. 보통은 루트 `.env` 하나로 충분합니다.

필요한 키와 발급처:

| 키 | 용도 | 발급처 |
|----|------|--------|
| `KIS_APP_KEY` / `KIS_APP_SECRET` / `KIS_ACCOUNT_NUMBER` | KIS 모의투자 (주문/잔고) | https://apiportal.koreainvestment.com |
| `KIS_QUOTE_APP_KEY` / `KIS_QUOTE_APP_SECRET` | KIS 시세/재무 조회 (api-server) | 위와 동일 |
| `GEMINI_API_KEY` | AI 매매 판단 (무료 티어) | https://aistudio.google.com/apikey |
| `DART_API_KEY` | 분기 재무 데이터 | https://opendart.fss.or.kr |
| `JWT_SECRET` | JWT 서명 키 (256bit 이상) | 임의 생성 |
| `JASYPT_PASSWORD` | KIS 자격증명 암호화 키 | 임의 생성 |

> **ai-agent는 `KIS_APP_KEY`/`KIS_APP_SECRET`가 없으면 기동되지 않습니다**(startup에서 `KISClient`가 `ValueError`). api-server·web-app은 키 없이도 기동되며 `GEMINI_API_KEY`·`DART_API_KEY`·`KIS_QUOTE_*`는 비어도 기동되고 해당 외부 연동만 graceful degrade됩니다.

---

### B-3. 모듈별 실행

세 모듈을 각각 별도 터미널에서 실행합니다.

### api-server (Spring Boot)
Gradle Wrapper가 포함되어 있어 별도 Gradle 설치가 필요 없습니다.

```bash
cd api-server
export JWT_SECRET="<256bit 이상 키>"
export JASYPT_PASSWORD="<암호화 키>"
./gradlew bootRun           # http://localhost:7070/api  (Liquibase 자동 마이그레이션)
# 빌드만: ./gradlew build   (산출물: build/libs/*.jar → java -jar 로 실행 가능)
```
헬스 체크: `GET http://localhost:7070/api/health`

### web-app (Vue 3)
```bash
cd web-app
npm install
npm run dev                 # http://localhost:5173
# 프로덕션 빌드: npm run build (정적 산출물: dist/)
```
API 베이스 URL은 `VITE_API_BASE_URL`(기본값 `http://localhost:7070/api`)로 지정합니다. 개발 모드에서는 라우터 인증 가드가 우회됩니다.

### ai-agent (FastAPI)
```bash
cd ai-agent
./run_dev.sh                # venv 자동 생성 + 의존성 설치 + http://localhost:8000
```

수동 실행:
```bash
cd ai-agent
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

> ⚠️ **반드시 venv 안에서 실행하세요.** 시스템 python3로 직접 실행하면 Prophet 의존성이 깨져 시계열 예측 결과(`prophet_forecast`)가 NULL로 저장됩니다.

스케줄러는 평일 08:50 KST에 **전체 파이프라인(Stage 1~6)**을 자동 실행합니다. 수동 트리거 `POST http://localhost:8000/api/pipeline/trigger`로도 실행할 수 있습니다.

---

## 개발용 테스트 계정 (공통)

`testuser` / `password123` (Liquibase `v1.4-test-data.yaml`로 시드)

---

## 트러블슈팅 (공통)

| 증상 | 원인 / 해결 |
|------|-------------|
| `docker compose up` 최초 빌드가 매우 느림 | 정상 — ai-agent가 torch/prophet 설치 + KR-FinBERT 다운로드. 최초 1회만. 진행은 `docker compose logs -f ai-agent` |
| ai-agent 컨테이너 DB 연결 실패 | postgres healthcheck 대기 후 기동됨. `docker compose ps`로 postgres `healthy` 확인 |
| 로컬 실행 시 `prophet_forecast`가 NULL | 시스템 python3로 실행. venv 활성화 후 재실행 (방법 B). 도커 실행 시엔 해당 없음 |
| api-server 부팅 실패 (`JWT_SECRET`/`JASYPT_PASSWORD`) | 두 환경 변수 설정 후 재실행 |
| web-app에서 API 401 반복 | 토큰 만료 → 자동 리프레시 실패. 재로그인 또는 `VITE_API_BASE_URL` 확인 |
| 차트 한글 깨짐 | NanumGothic 폰트 미설치 (분석 데이터에는 영향 없음) |
| KIS 시세/매매 미동작 | `.env`의 KIS 키 누락 또는 모의투자 계좌 연동 필요 |

모듈별 상세 트러블슈팅은 각 모듈 `_docs/USAGE.md`를 참고하세요.

# AI Agent - CLI Commands

> 상태: 개발 예정 (2~3단계)

## 환경 설정

```bash
# 가상환경 생성
python -m venv venv

# 가상환경 활성화 (macOS/Linux)
source venv/bin/activate

# 가상환경 활성화 (Windows)
.\venv\Scripts\activate

# Poetry 사용 시
poetry install
poetry shell
```

## 의존성

```bash
# pip 설치
pip install -r requirements.txt

# Poetry 설치
poetry install

# 개발 의존성 포함
poetry install --with dev

# 새 패키지 추가
poetry add fastapi
poetry add --group dev pytest
```

## 개발 서버

```bash
# Uvicorn 실행
uvicorn app.main:app --reload --port 5000

# 호스트 지정
uvicorn app.main:app --reload --host 0.0.0.0 --port 5000

# 워커 수 지정 (프로덕션)
uvicorn app.main:app --workers 4 --port 5000
```

## 테스트

```bash
# pytest 실행
pytest

# 특정 파일 테스트
pytest tests/unit/test_analyzer.py

# 커버리지 리포트
pytest --cov=app --cov-report=html

# 상세 출력
pytest -v
```

## 코드 품질

```bash
# Black 포맷팅
black app/

# Ruff 린트
ruff check app/

# Ruff 자동 수정
ruff check --fix app/

# mypy 타입 체크
mypy app/
```

## 데이터베이스

```bash
# Alembic 마이그레이션 생성
alembic revision --autogenerate -m "description"

# 마이그레이션 실행
alembic upgrade head

# 마이그레이션 롤백
alembic downgrade -1
```

## ML 관련

```bash
# 모델 학습
python scripts/train_model.py

# 백테스팅
python scripts/backtest.py --start 2025-01-01 --end 2025-12-31

# 모델 평가
python scripts/evaluate_model.py
```

## Docker

```bash
# 이미지 빌드
docker build -t ai-agent:latest .

# 컨테이너 실행
docker run -p 5000:5000 ai-agent:latest
```

## 유용한 엔드포인트

| 경로 | 용도 |
|------|------|
| /docs | Swagger UI |
| /redoc | ReDoc 문서 |
| /api/v1/health | 헬스 체크 |

"""Application settings loaded from environment variables."""
from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    """Application configuration settings."""

    # Python Environment
    pythonpath: str = "/app"

    # KIS API Configuration
    kis_mode: str = "VIRTUAL"
    kis_app_key: str
    kis_app_secret: str
    kis_account_no: Optional[str] = None  # Account number (optional for Stage 1)
    kis_base_url: str = "https://openapi.koreainvestment.com:9443"

    # Database Configuration
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "financemanage"
    db_user: str = "postgres"
    db_password: str

    # Scheduler Configuration
    # 요일 필드는 이름(mon-fri)으로 쓴다 — APScheduler CronTrigger 는 표준 crontab(0=일요일)과
    # 달리 Python weekday() 기준(0=월요일)이라, 숫자 '1-5'를 쓰면 월~금이 아니라 화~토가 된다.
    pipeline_cron: str = "50 8 * * mon-fri"  # Weekdays 08:50
    pipeline_timezone: str = "Asia/Seoul"
    pipeline_enabled: bool = True

    # Gemini AI Configuration
    gemini_api_key: Optional[str] = None  # Optional (not required for testing)

    # DART API Configuration
    dart_api_key: Optional[str] = None  # DART Open API key

    # API Server (Spring Boot) — 내부 서비스 호출 (멀티유저 파이프라인)
    api_server_url: str = "http://api-server:7070"  # base URL (컨텍스트패스 /api 제외)
    internal_api_key: Optional[str] = None  # X-Internal-Api-Key (api-server 와 공유)

    # Kafka — 매매 주문 발행(trade.order.requested) / 결과 수신(trade.order.result) /
    # 파이프라인 실행 직렬화(pipeline.run.requested)
    # 컨테이너 내부에서는 kafka:9092, 로컬 실행 시 localhost:29092(EXTERNAL 리스너 — 9092는 호스트 미노출)
    kafka_bootstrap_servers: str = "localhost:29092"
    kafka_client_id: str = "ai-agent"
    # 컨슈머가 poll 사이에 머물 수 있는 최대 시간. aiokafka 는 마지막 fetch 이후 흐른
    # 시간이 이 값을 넘으면 하트비트 성공 여부와 무관하게 그룹에서 이탈시킨다.
    # pipeline.run.requested 핸들러는 파이프라인 전체(수십 분)를 돌기 때문에,
    # 기본값 300초를 쓰면 5분만 넘겨도 이탈 → 재조정 → 같은 트리거 무한 재처리가 된다.
    kafka_max_poll_interval_ms: int = 3_600_000  # 1시간

    # Logging Configuration
    log_level: str = "INFO"
    log_file: str = "logs/pipeline.log"

    class Config:
        env_file = [".env", ".env.local"]  # Load from .env.local first, then .env
        env_file_encoding = "utf-8"
        case_sensitive = False
        # kis_client.py 가 os.getenv 로 직접 읽는 KIS_ACCOUNT_NUMBER/KIS_ACCOUNT_PRODUCT_CODE 등,
        # 이 클래스가 필드로 선언하지 않은 .env 키가 있을 수 있다. pydantic-settings 기본값인
        # extra="forbid" 를 쓰면 그런 키가 있는 .env.example 을 그대로 복사만 해도 기동이 실패한다.
        extra = "ignore"

    @property
    def database_url(self) -> str:
        """Generate database connection URL."""
        return f"postgresql://{self.db_user}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


# Global settings instance (created lazily)
_settings: Optional[Settings] = None


def get_settings() -> Settings:
    """Get or create global settings instance."""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


# For backwards compatibility
settings = get_settings()

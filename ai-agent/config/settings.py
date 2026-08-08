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

    # Logging Configuration
    log_level: str = "INFO"
    log_file: str = "logs/pipeline.log"

    class Config:
        env_file = [".env", ".env.local"]  # Load from .env.local first, then .env
        env_file_encoding = "utf-8"
        case_sensitive = False

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

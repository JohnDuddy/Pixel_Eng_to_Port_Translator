from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    openai_api_key: str = ""
    openai_realtime_model: str = "gpt-realtime"
    openai_text_model: str = "gpt-4.1-mini"
    allowed_app_token: str = "dev-local-token"
    token_ttl_seconds: int = 60

    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()

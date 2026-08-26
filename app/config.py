from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_env: str = "development"
    app_name: str = "AUTOSHOP MECH AI"
    ai_provider: str = "gemini"
    gemini_api_key: str = ""
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    model_name: str = ""
    nhtsa_base_url: str = "https://vpic.nhtsa.dot.gov/api"
    database_url: str = "sqlite:///./autoshop.db"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()

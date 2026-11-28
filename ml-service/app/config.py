from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GRIDIQ_ML_")

    service_name: str = "gridiq-ml"
    api_key: str = ""
    api_key_file: Path = Path("/run/gridiq/ml/ml-api-key")
    copilot_provider: str = "deterministic"
    llm_api_key: str = ""
    ollama_url: str = "http://ollama:11434"
    ollama_model: str = "llama3.2:3b"
    openai_model: str = "gpt-4o-mini"
    gemini_model: str = "gemini-2.0-flash"
    llm_timeout_seconds: float = 20.0

    def resolved_api_key(self) -> str:
        if self.api_key:
            return self.api_key
        try:
            return self.api_key_file.read_text(encoding="utf-8").strip()
        except OSError:
            return ""

from typing import Literal

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel

from app.config import Settings


class HealthStatus(BaseModel):
    status: Literal["UP"] = "UP"
    service: str


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    application = FastAPI(
        title="GridIQ ML", docs_url=None, redoc_url=None, openapi_url=None
    )

    @application.get("/health")
    def health() -> HealthStatus:
        return HealthStatus(service=settings.service_name)

    Instrumentator(
        should_ignore_untemplated=True,
        excluded_handlers=["/metrics"],
    ).instrument(application).expose(application, include_in_schema=False)
    return application


app = create_app()

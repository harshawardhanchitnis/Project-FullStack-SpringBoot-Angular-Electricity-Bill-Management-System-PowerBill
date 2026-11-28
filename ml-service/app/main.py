import logging
from typing import Literal

from fastapi import Depends, FastAPI
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel

from app.auth import require_internal_key
from app.config import Settings
from app.routers import anomalies, copilot, forecast
from app.services.rag_store import RagStore


class HealthStatus(BaseModel):
    status: Literal["UP"] = "UP"
    service: str


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    application = FastAPI(
        title="GridIQ ML", docs_url=None, redoc_url=None, openapi_url=None
    )
    application.state.settings = settings
    application.state.api_key = settings.resolved_api_key()
    application.state.rag = RagStore()

    @application.get("/health")
    def health() -> HealthStatus:
        return HealthStatus(service=settings.service_name)

    protected = [Depends(require_internal_key)]
    application.include_router(forecast.router, dependencies=protected)
    application.include_router(anomalies.router, dependencies=protected)
    application.include_router(copilot.router, dependencies=protected)

    Instrumentator(
        should_ignore_untemplated=True,
        excluded_handlers=["/metrics"],
    ).instrument(application).expose(application, include_in_schema=False)
    return application


app = create_app()

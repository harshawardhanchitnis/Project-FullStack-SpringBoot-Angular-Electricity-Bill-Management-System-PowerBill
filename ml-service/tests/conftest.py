import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

API_KEY = "test-internal-key"
# Prometheus collectors are process-wide, so every test shares one instrumented application.
APP = create_app(Settings(api_key=API_KEY))


@pytest.fixture
def client():
    with TestClient(APP) as test_client:
        test_client.headers.update({"X-Internal-Api-Key": API_KEY})
        yield test_client
    APP.state.settings = Settings(api_key=API_KEY)

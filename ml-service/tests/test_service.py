from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_health_endpoint_and_metrics(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "gridiq-ml"}
    metrics = client.get("/metrics")
    assert metrics.status_code == 200
    assert 'handler="/health"' in metrics.text


def test_unknown_routes_and_api_docs_are_unavailable(client):
    assert client.get("/api/v1/status").status_code == 404
    assert client.get("/docs").status_code == 404
    assert client.post("/health").status_code == 405


def test_ml_endpoints_require_the_internal_key(client):
    body = {"message": "hello", "context": {}}
    wrong = {"X-Internal-Api-Key": "wrong"}
    assert client.post("/ml/v1/copilot/respond", json=body, headers=wrong).status_code == 401
    settings = Settings(api_key="", api_key_file="/nonexistent/key")
    with TestClient(create_app(settings)) as unconfigured:
        assert unconfigured.post("/ml/v1/copilot/respond", json=body).status_code == 503

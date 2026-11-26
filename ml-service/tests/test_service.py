from fastapi.testclient import TestClient

from app.main import app


def test_health_endpoint_and_metrics():
    with TestClient(app) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json() == {"status": "UP", "service": "gridiq-ml"}

        metrics = client.get("/metrics")
        assert metrics.status_code == 200
        assert 'handler="/health"' in metrics.text


def test_unknown_routes_and_api_docs_are_unavailable():
    with TestClient(app) as client:
        assert client.get("/api/v1/status").status_code == 404
        assert client.get("/docs").status_code == 404
        assert client.post("/health").status_code == 405

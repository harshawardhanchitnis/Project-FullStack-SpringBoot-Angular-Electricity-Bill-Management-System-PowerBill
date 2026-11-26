from datetime import date, timedelta

import numpy as np

from app.services import forecaster


def _series(days: int, seed: int = 5):
    rng = np.random.default_rng(seed)
    start = date(2025, 10, 1)
    points = []
    for index in range(days):
        day = start + timedelta(days=index)
        seasonal = 4 * np.sin(2 * np.pi * index / 365)
        weekly = 3.0 if day.weekday() >= 5 else 0.0
        points.append((day, 22 + seasonal + weekly + rng.normal(0, 1.0)))
    return points


def test_forecast_tracks_a_seasonal_benchmark_within_15_percent_mape():
    history = _series(400)
    as_of = history[-1][0] + timedelta(days=1)
    result = forecaster.forecast(history, as_of, as_of + timedelta(days=20))
    assert result.backtest_mape_pct is not None
    assert result.backtest_mape_pct < 15
    assert "gradient-boosting" in result.model
    assert len(result.bands) == 20
    assert all(b.p10 <= b.p50 <= b.p90 for b in result.bands)


def test_short_histories_fall_back_to_holt_winters():
    history = _series(21)
    as_of = history[-1][0] + timedelta(days=1)
    result = forecaster.forecast(history, as_of, as_of + timedelta(days=10))
    assert result.model.startswith("holt-winters")
    assert all(b.p50 >= 0 for b in result.bands)


def test_the_forecast_endpoint_returns_ordered_bands(client):
    history = _series(120)
    as_of = history[-1][0] + timedelta(days=1)
    response = client.post("/ml/v1/forecast", json={
        "meterId": "m-1", "asOf": as_of.isoformat(), "cycleEnd": (as_of + timedelta(days=12)).isoformat(),
        "history": [{"day": d.isoformat(), "importKwh": round(v, 3)} for d, v in history]})
    assert response.status_code == 200
    body = response.json()
    assert body["horizonDays"] == 12
    assert body["predictedKwhP10"] <= body["predictedKwhP50"] <= body["predictedKwhP90"]


def test_the_forecast_rejects_invalid_horizons(client):
    response = client.post("/ml/v1/forecast", json={
        "meterId": "m-1", "asOf": "2026-11-20", "cycleEnd": "2026-11-10",
        "history": [{"day": f"2026-11-{d:02d}", "importKwh": 10} for d in range(1, 15)]})
    assert response.status_code == 422

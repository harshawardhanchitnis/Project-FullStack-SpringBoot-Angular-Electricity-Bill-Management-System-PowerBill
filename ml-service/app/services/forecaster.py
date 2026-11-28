"""Daily consumption forecasting for the current billing cycle.

Two models are combined:

* an additive Holt-Winters model with weekly seasonality (works from 14 days of history), and
* three quantile HistGradientBoosting models (P10/P50/P90) on calendar and lag features (60+ days).

The gradient-boosted median is blended with Holt-Winters, and both quantile bands are widened so they
always contain the blended median. A 14-day holdout gives an honest MAPE for the selected setup.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, timedelta

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

QUANTILES = (0.1, 0.5, 0.9)
MIN_GBM_DAYS = 60
SEASON = 7


@dataclass(frozen=True)
class Band:
    day: date
    p10: float
    p50: float
    p90: float


@dataclass(frozen=True)
class Forecast:
    bands: list[Band]
    trend_pct: float | None
    model: str
    backtest_mape_pct: float | None


def fill_series(points: list[tuple[date, float]]) -> tuple[date, np.ndarray]:
    """Returns a gap-free daily series, linearly interpolating missing days."""
    ordered = sorted(points)
    start, end = ordered[0][0], ordered[-1][0]
    length = (end - start).days + 1
    values = np.full(length, np.nan)
    for day, value in ordered:
        values[(day - start).days] = value
    missing = np.isnan(values)
    if missing.any():
        index = np.arange(length)
        values[missing] = np.interp(index[missing], index[~missing], values[~missing])
    return start, values


def holt_winters(
    y: np.ndarray, horizon: int, alpha: float = 0.3, beta: float = 0.03, gamma: float = 0.25
) -> tuple[np.ndarray, float]:
    """Additive Holt-Winters with a weekly season. Returns the forecast and residual std."""
    m = SEASON
    if len(y) < 2 * m:
        level = float(np.mean(y))
        residual = float(np.std(y)) if len(y) > 1 else 0.0
        return np.full(horizon, level), residual
    level = float(np.mean(y[:m]))
    trend = float((np.mean(y[m : 2 * m]) - np.mean(y[:m])) / m)
    season = list(y[:m] - level)
    fitted = []
    for t, value in enumerate(y):
        s = season[t % m]
        fitted.append(level + trend + s)
        previous_level = level
        level = alpha * (value - s) + (1 - alpha) * (level + trend)
        trend = beta * (level - previous_level) + (1 - beta) * trend
        season[t % m] = gamma * (value - level) + (1 - gamma) * s
    residual = float(np.std(np.asarray(fitted[m:]) - y[m:])) if len(y) > m + 1 else 0.0
    n = len(y)
    forecast = np.array([level + (h + 1) * trend + season[(n + h) % m] for h in range(horizon)])
    return np.maximum(forecast, 0.0), residual


def _features(values: np.ndarray, start: date, index: int) -> list[float]:
    day = start + timedelta(days=index)
    lag1 = values[index - 1]
    lag7 = values[index - 7]
    mean7 = float(np.mean(values[index - 7 : index]))
    mean28 = float(np.mean(values[max(0, index - 28) : index]))
    return [day.weekday(), day.month, day.day, lag1, lag7, mean7, mean28, mean7 - mean28]


def _fit_gbm(values: np.ndarray, start: date) -> list[HistGradientBoostingRegressor]:
    rows = [_features(values, start, i) for i in range(28, len(values))]
    targets = values[28:]
    models = []
    for quantile in QUANTILES:
        model = HistGradientBoostingRegressor(
            loss="quantile",
            quantile=quantile,
            max_iter=150,
            learning_rate=0.06,
            max_leaf_nodes=15,
            min_samples_leaf=8,
            random_state=7,
        )
        model.fit(np.asarray(rows), targets)
        models.append(model)
    return models


def _recursive(
    models: list[HistGradientBoostingRegressor], values: np.ndarray, start: date, horizon: int
) -> np.ndarray:
    """Predicts day by day, feeding the median back in as the lag for later days."""
    path = list(values)
    output = np.zeros((horizon, 3))
    for step in range(horizon):
        index = len(path)
        row = np.asarray([_features(np.asarray(path), start, index)])
        predictions = [float(model.predict(row)[0]) for model in models]
        output[step] = predictions
        path.append(max(predictions[1], 0.0))
    return output


def _predict(values: np.ndarray, start: date, horizon: int) -> tuple[np.ndarray, str]:
    hw, residual = holt_winters(values, horizon)
    if len(values) < MIN_GBM_DAYS:
        widths = np.array([1.2816 * residual * math.sqrt(1 + h / 7) for h in range(horizon)])
        bands = np.column_stack([np.maximum(hw - widths, 0), hw, hw + widths])
        return bands, "holt-winters (weekly)"
    gbm = _recursive(_fit_gbm(values, start), values, start, horizon)
    median = 0.6 * gbm[:, 1] + 0.4 * hw
    low = np.minimum(gbm[:, 0], median)
    high = np.maximum(gbm[:, 2], median)
    return np.column_stack([np.maximum(low, 0), np.maximum(median, 0), high]), (
        "quantile hist-gradient-boosting + holt-winters"
    )


def forecast(history: list[tuple[date, float]], as_of: date, cycle_end: date) -> Forecast:
    start, values = fill_series(history)
    last = start + timedelta(days=len(values) - 1)
    # Days between the last observation and as_of are forecast too, then discarded.
    gap = max((as_of - last).days - 1, 0)
    horizon = (cycle_end - as_of).days
    bands, model = _predict(values, start, gap + horizon)
    bands = bands[gap:]
    output = [
        Band(as_of + timedelta(days=i), round(b[0], 4), round(b[1], 4), round(b[2], 4))
        for i, b in enumerate(bands)
    ]

    trend = None
    if len(values) >= 35:
        recent = float(np.mean(values[-7:]))
        baseline = float(np.mean(values[-35:-7]))
        if baseline > 0:
            trend = round((recent - baseline) / baseline * 100, 1)

    mape = None
    if len(values) >= 42:
        train, test = values[:-14], values[-14:]
        predicted, _ = _predict(train, start, 14)
        mask = test > 0.05
        if mask.any():
            mape = round(float(np.mean(np.abs(predicted[mask, 1] - test[mask]) / test[mask]) * 100), 2)
    return Forecast(output, trend, model, mape)

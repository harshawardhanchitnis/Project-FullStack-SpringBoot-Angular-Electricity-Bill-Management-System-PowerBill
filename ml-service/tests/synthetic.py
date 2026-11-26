"""Synthetic hourly telemetry that mirrors the Core API's AMI simulator."""

from datetime import datetime, timedelta, timezone

import numpy as np

IST = timezone(timedelta(hours=5, minutes=30))


def hourly(days: int = 45, kw: float = 5.0, seed: int = 3, end: datetime | None = None):
    rng = np.random.default_rng(seed)
    end = end or datetime(2026, 11, 20, tzinfo=IST)
    start = end - timedelta(days=days)
    readings = []
    for index in range(days * 24):
        moment = start + timedelta(hours=index)
        hour = moment.hour
        shape = 0.23 if 18 <= hour <= 22 else 0.14 if 7 <= hour <= 9 else 0.06
        weekend = 0.03 if moment.weekday() >= 5 else 0
        demand = kw * (0.08 + shape + weekend + rng.uniform(0, 0.07))
        readings.append([moment, round(demand, 4), 0.0])
    return readings


def inject_tamper(readings, start_day: int, hours: int = 72):
    for reading in readings[start_day * 24 : start_day * 24 + hours]:
        reading[1] = round(reading[1] * 0.03, 4)


def inject_zero(readings, start_day: int, hours: int = 48):
    for reading in readings[start_day * 24 : start_day * 24 + hours]:
        reading[1] = 0.0


def inject_night_jump(readings, last_days: int = 6, extra: float = 0.9):
    for reading in readings[-last_days * 24 :]:
        if reading[0].hour < 6:
            reading[1] = round(reading[1] + extra, 4)


def as_payload(readings):
    return [{"start": r[0].isoformat(), "importKwh": r[1], "exportKwh": r[2]} for r in readings]

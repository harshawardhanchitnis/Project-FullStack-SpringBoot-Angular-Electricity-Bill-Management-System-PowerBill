"""Explainable anomaly and tamper detection on hourly smart meter data.

Rules catch the patterns field engineers care about and explain them in plain language:

* ZERO_READ: no import and no export for at least 24 consecutive hours (stuck meter or bypass).
* TAMPER_SUSPECTED: at least 36 hours below 15% of the meter's own 28-day hourly median, while not zero.
* SPIKE: night baseload (00:00 to 05:00) above 1.8 times its 30-day median for 3 or more consecutive days.

An Isolation Forest over daily load-shape features then flags remaining unusual days in the last 30 days.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import numpy as np
from sklearn.ensemble import IsolationForest

IST = timezone(timedelta(hours=5, minutes=30))
MODEL_NAME = "rules + isolation-forest (daily load shape)"


@dataclass
class Finding:
    start: datetime
    end: datetime
    anomaly_type: str
    severity: str
    score: float
    reasons: list[dict[str, str]] = field(default_factory=list)


def _hourly_grid(readings: list[tuple[datetime, float, float]]):
    ordered = sorted(readings)
    first = ordered[0][0].astimezone(IST)
    start = first.replace(minute=0, second=0, microsecond=0)
    last = ordered[-1][0].astimezone(IST)
    hours = int((last - start).total_seconds() // 3600) + 1
    imports = np.full(hours, np.nan)
    exports = np.full(hours, np.nan)
    for moment, imported, exported in ordered:
        index = int((moment.astimezone(IST) - start).total_seconds() // 3600)
        imports[index] = imported
        exports[index] = exported
    return start, imports, exports


def _runs(mask: np.ndarray) -> list[tuple[int, int]]:
    """Returns [start, end) index pairs of consecutive True values."""
    runs, begin = [], None
    for index, value in enumerate(mask):
        if value and begin is None:
            begin = index
        elif not value and begin is not None:
            runs.append((begin, index))
            begin = None
    if begin is not None:
        runs.append((begin, len(mask)))
    return runs


def _zero_reads(start, imports, exports) -> list[Finding]:
    findings = []
    zero = np.nan_to_num(imports, nan=1) == 0
    zero &= np.nan_to_num(exports, nan=1) == 0
    for begin, end in _runs(zero):
        hours = end - begin
        if hours >= 24:
            findings.append(Finding(
                start + timedelta(hours=begin), start + timedelta(hours=end), "ZERO_READ",
                "CRITICAL" if hours >= 48 else "HIGH", min(100.0, 70 + hours / 2),
                [{"code": "FLATLINE_ZERO_READ",
                  "detail": f"No import or export recorded for {hours} consecutive hours"}]))
    return findings


def _expected_by_hour(imports: np.ndarray, start: datetime, index: int) -> float | None:
    """Median of the same local hour over the trailing 28 days, excluding the current day."""
    same_hour = [index - 24 * day for day in range(1, 29) if index - 24 * day >= 0]
    values = imports[same_hour]
    values = values[~np.isnan(values)]
    return float(np.median(values)) if len(values) >= 7 else None


def _near_zero(start, imports, exports, solar: bool, zero_spans) -> list[Finding]:
    ratio = np.full(len(imports), np.nan)
    for index in range(len(imports)):
        expected = _expected_by_hour(imports, start, index)
        if expected and expected > 0.05 and not np.isnan(imports[index]):
            ratio[index] = imports[index] / expected
    smoothed = np.full(len(imports), np.nan)
    for index in range(12, len(imports) - 12):
        window = ratio[index - 12 : index + 12]
        if np.count_nonzero(~np.isnan(window)) >= 18:
            smoothed[index] = np.nanmean(window)
    low = np.nan_to_num(smoothed, nan=1) < 0.15
    findings = []
    for begin, end in _runs(low):
        begin, end = max(begin - 12, 0), min(end + 12, len(imports))
        if end - begin < 36 or any(begin < e and end > b for b, e in zero_spans):
            continue
        actual = float(np.nansum(imports[begin:end]))
        expected = sum(_expected_by_hour(imports, start, i) or 0 for i in range(begin, end))
        drop = 100 * (1 - actual / expected) if expected else 100
        hours = end - begin
        local = (start + timedelta(hours=begin))
        detail = (f"Sudden {hours}h near-zero draw ({drop:.0f}% below the meter's 28-day hourly median)"
                  f" starting {local:%d %b %H:%M} IST")
        reasons = [{"code": "SUSTAINED_LOW_DRAW", "detail": detail}]
        if solar:
            reasons.append({"code": "CHECK_SOLAR_EXPORT",
                            "detail": "Solar meter: confirm export did not rise to explain the drop"})
        findings.append(Finding(start + timedelta(hours=begin), start + timedelta(hours=end),
                                "TAMPER_SUSPECTED", "CRITICAL" if hours >= 72 else "HIGH",
                                min(100.0, 60 + drop * 0.35), reasons))
    return findings


def _night_baseload(start, imports) -> list[Finding]:
    days = len(imports) // 24
    if days < 21:
        return []
    night = np.array([np.nanmean(imports[d * 24 : d * 24 + 5]) for d in range(days)])
    flags = np.zeros(days, dtype=bool)
    baseline = np.full(days, np.nan)
    for day in range(14, days):
        history = night[max(0, day - 30) : day - 3]
        history = history[~np.isnan(history)]
        if len(history) >= 10:
            baseline[day] = float(np.median(history))
            flags[day] = night[day] > 1.8 * baseline[day] and night[day] - baseline[day] > 0.3
    findings = []
    for begin, end in _runs(flags):
        if end - begin < 3:
            continue
        reference = float(np.nanmean(baseline[begin:end]))
        level = float(np.nanmean(night[begin:end]))
        jump = 100 * (level - reference) / reference if reference else 0
        findings.append(Finding(
            start + timedelta(days=begin), start + timedelta(days=end), "SPIKE",
            "HIGH" if jump >= 150 else "MEDIUM", min(100.0, 50 + jump / 5),
            [{"code": "NIGHT_BASELOAD_JUMP",
              "detail": f"Nighttime baseload jumped +{jump:.0f}% vs 30-day median for {end - begin} nights"},
             {"code": "LIKELY_CAUSE",
              "detail": "Typical causes: a faulty compressor, a heater left on, or a new always-on load"}]))
    return findings


def _isolation_forest(start, imports, explained: list[Finding]) -> list[Finding]:
    days = len(imports) // 24
    if days < 21:
        return []
    rows, keep = [], []
    for day in range(days):
        hours = imports[day * 24 : (day + 1) * 24]
        if np.count_nonzero(np.isnan(hours)) > 4:
            continue
        total = float(np.nansum(hours))
        rows.append([total, float(np.nanmean(hours[0:5])), float(np.nansum(hours[18:22])) / (total or 1),
                     float(np.nanmax(hours)), float(np.nanstd(hours))])
        keep.append(day)
    if len(rows) < 21:
        return []
    matrix = np.asarray(rows)
    scaled = (matrix - matrix.mean(axis=0)) / (matrix.std(axis=0) + 1e-9)
    model = IsolationForest(n_estimators=200, contamination=0.03, random_state=11).fit(scaled)
    scores = model.decision_function(scaled)
    labels = model.predict(scaled)
    median_total = float(np.median(matrix[:, 0]))
    findings = []
    for position, day in enumerate(keep):
        if labels[position] != -1 or day < days - 30:
            continue
        begin = start + timedelta(days=day)
        if any(item.start <= begin < item.end or item.start < begin + timedelta(days=1) <= item.end
               for item in explained):
            continue
        total = matrix[position, 0]
        ratio = total / median_total if median_total else 1
        if ratio >= 1.5:
            kind, detail = "SPIKE", f"Daily consumption {ratio:.1f}x the {len(rows)}-day median"
        elif ratio <= 0.5:
            kind, detail = "TAMPER_SUSPECTED", f"Daily consumption {100 * (1 - ratio):.0f}% below the median"
        else:
            continue
        depth = min(1.0, abs(float(scores[position])) * 4)
        findings.append(Finding(begin, begin + timedelta(days=1), kind, "MEDIUM" if depth < 0.6 else "HIGH",
                                round(50 + 40 * depth, 1),
                                [{"code": "ISOLATION_FOREST_OUTLIER", "detail": detail}]))
    return findings


def detect(readings: list[tuple[datetime, float, float]], solar: bool) -> list[Finding]:
    start, imports, exports = _hourly_grid(readings)
    zero = _zero_reads(start, imports, exports)
    zero_spans = [(int((f.start - start).total_seconds() // 3600), int((f.end - start).total_seconds() // 3600))
                  for f in zero]
    findings = zero + _near_zero(start, imports, exports, solar, zero_spans) + _night_baseload(start, imports)
    findings += _isolation_forest(start, imports, findings)
    for finding in findings:
        finding.score = round(float(finding.score), 1)
    return sorted(findings, key=lambda item: item.start)

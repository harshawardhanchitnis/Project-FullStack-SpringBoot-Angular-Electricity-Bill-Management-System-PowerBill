import warnings
from datetime import datetime, timedelta

from app.services import anomaly_detector
from tests import synthetic

MID_AFTERNOON = datetime(2026, 11, 20, 13, tzinfo=synthetic.IST)


def _assert_within_readings(findings, readings):
    first, last = readings[0][0], readings[-1][0] + timedelta(hours=1)
    for finding in findings:
        assert first <= finding.start < finding.end <= last


def test_clean_telemetry_raises_no_high_severity_anomalies():
    findings = anomaly_detector.detect([tuple(r) for r in synthetic.hourly()], solar=False)
    assert not [f for f in findings if f.severity in ("HIGH", "CRITICAL")]


def test_detects_each_injected_scenario_with_explanations():
    tamper = synthetic.hourly(seed=4)
    synthetic.inject_tamper(tamper, start_day=30)
    zero = synthetic.hourly(seed=5)
    synthetic.inject_zero(zero, start_day=25)
    night = synthetic.hourly(seed=6)
    synthetic.inject_night_jump(night)

    cases = {"TAMPER_SUSPECTED": tamper, "ZERO_READ": zero, "SPIKE": night}
    recalled = 0
    for expected, readings in cases.items():
        findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
        matches = [f for f in findings
                   if f.anomaly_type == expected and f.severity in ("MEDIUM", "HIGH", "CRITICAL")]
        if matches:
            recalled += 1
            assert matches[0].reasons and matches[0].reasons[0]["detail"]
            assert 0 <= matches[0].score <= 100
    assert recalled == len(cases)


def test_tamper_reason_quantifies_the_drop():
    readings = synthetic.hourly(seed=8)
    synthetic.inject_tamper(readings, start_day=28)
    findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
    tamper = next(f for f in findings if f.anomaly_type == "TAMPER_SUSPECTED")
    assert "near-zero draw" in tamper.reasons[0]["detail"]
    assert tamper.severity == "CRITICAL"


def test_the_detect_endpoint_serializes_findings(client):
    readings = synthetic.hourly(seed=9)
    synthetic.inject_zero(readings, start_day=20)
    response = client.post("/ml/v1/anomalies/detect", json={
        "meterId": "m-2", "readings": synthetic.as_payload(readings)})
    assert response.status_code == 200
    assert any(a["anomalyType"] == "ZERO_READ" for a in response.json()["anomalies"])


def test_a_zero_read_flatline_is_not_reported_again_as_tamper():
    readings = synthetic.hourly(seed=12)
    synthetic.inject_zero(readings, start_day=22)
    findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
    assert [f.anomaly_type for f in findings if f.severity in ("HIGH", "CRITICAL")] == ["ZERO_READ"]


def test_a_solar_meter_flatline_is_a_zero_read_not_tamper():
    readings = synthetic.hourly(seed=14, kw=1.6, solar_kw=3.0)
    assert any(r[2] > 0 for r in readings), "the solar meter should export at midday"
    synthetic.inject_zero(readings, start_day=22)
    findings = anomaly_detector.detect([tuple(r) for r in readings], solar=True)
    assert [f.anomaly_type for f in findings if f.severity in ("HIGH", "CRITICAL")] == ["ZERO_READ"]


def test_detects_a_night_baseload_jump_on_a_large_commercial_load():
    readings = synthetic.hourly(seed=13, kw=10.0)
    synthetic.inject_night_jump(readings, extra=2.5)
    findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
    spike = next(f for f in findings if f.anomaly_type == "SPIKE")
    assert "Nighttime baseload jumped" in spike.reasons[0]["detail"]


def test_a_night_jump_is_found_when_readings_start_mid_afternoon():
    # The first reading is 13:00 IST, so a grid anchored on it would read 13:00-18:00 as the "night" hours.
    readings = synthetic.hourly(seed=6, end=MID_AFTERNOON)
    assert readings[0][0].hour == 13
    synthetic.inject_night_jump(readings)
    findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
    spike = next(f for f in findings if f.anomaly_type == "SPIKE")
    assert spike.severity in ("MEDIUM", "HIGH")
    assert "Nighttime baseload jumped" in spike.reasons[0]["detail"]
    # The jump is injected into the last 144 readings, whose first night is 15 Nov 00:00-06:00 IST.
    assert spike.start == datetime(2026, 11, 15, tzinfo=synthetic.IST)
    assert spike.end == MID_AFTERNOON
    _assert_within_readings(findings, readings)


def test_gaps_and_partial_days_never_emit_runtime_warnings():
    readings = synthetic.hourly(seed=6, end=MID_AFTERNOON)
    synthetic.inject_night_jump(readings)
    del readings[20 * 24 : 21 * 24 + 6]  # a 30-hour outage in the feed swallows a whole night
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        findings = anomaly_detector.detect([tuple(r) for r in readings], solar=False)
    assert any(f.anomaly_type == "SPIKE" for f in findings)
    assert all(0 <= f.score <= 100 for f in findings)
    _assert_within_readings(findings, readings)

from app.services import llm

CONTEXT = {
    "today": "2026-11-26",
    "account": {"name": "Demo Mumbai 01", "consumerNumber": "PB-DEMO-01", "type": "RESIDENTIAL",
                "monthlyBudgetInr": 1050},
    "meter": {"serial": "M-DEMO-01-1", "connectionType": "DOMESTIC", "sanctionedLoadKw": 4,
              "solarNetMetering": False, "premise": "Primary service, Mumbai"},
    "bills": [
        {"billNumber": "INV-202610-A", "period": "2026-10", "importKwh": 412.5, "exportKwh": 0,
         "totalInr": 2604.18, "outstandingInr": 2604.18, "status": "OVERDUE", "dueDate": "2026-11-17",
         "lineItems": [
             {"category": "SLAB", "description": "Energy slab 1", "units": 100, "rate": 3.25, "amount": 325.0},
             {"category": "SLAB", "description": "Energy slab 2", "units": 200, "rate": 5.25, "amount": 1050.0},
             {"category": "SLAB", "description": "Energy slab 3", "units": 112.5, "rate": 7.25, "amount": 815.63},
             {"category": "FIXED", "description": "Fixed charge per kW", "units": 4, "rate": 85, "amount": 340.0},
             {"category": "DUTY", "description": "Electricity duty", "units": None, "rate": 10,
              "amount": 73.55}]},
        {"billNumber": "INV-202609-B", "period": "2026-09", "importKwh": 288.0, "exportKwh": 0,
         "totalInr": 1661.0, "outstandingInr": 0, "status": "PAID", "dueDate": "2026-10-17",
         "lineItems": [
             {"category": "SLAB", "description": "Energy slab 1", "units": 100, "rate": 3.25, "amount": 325.0},
             {"category": "SLAB", "description": "Energy slab 2", "units": 188, "rate": 5.25, "amount": 987.0},
             {"category": "FIXED", "description": "Fixed charge per kW", "units": 4, "rate": 85, "amount": 340.0},
             {"category": "DUTY", "description": "Electricity duty", "units": None, "rate": 10, "amount": 9.0}]}],
    "receivables": {"openBills": 1, "overdueBills": 1, "principalDue": 2604.18, "lateFeesDue": 11.72,
                    "nextDueDate": "2026-11-17"},
    "tariff": {"name": "Illustrative DOMESTIC tariff",
               "slabs": [{"fromKwh": 0, "untilKwh": 100, "ratePerKwh": 3.25},
                         {"fromKwh": 100, "untilKwh": 300, "ratePerKwh": 5.25},
                         {"fromKwh": 300, "untilKwh": 500, "ratePerKwh": 7.25},
                         {"fromKwh": 500, "untilKwh": None, "ratePerKwh": 9.25}],
               "timeWindows": [{"name": "NORMAL", "startHour": 0, "endHour": 9, "rateMultiplier": 1},
                               {"name": "SOLAR_OFF_PEAK", "startHour": 9, "endHour": 17, "rateMultiplier": 0.8},
                               {"name": "NORMAL", "startHour": 17, "endHour": 18, "rateMultiplier": 1},
                               {"name": "EVENING_PEAK", "startHour": 18, "endHour": 22, "rateMultiplier": 1.1},
                               {"name": "NORMAL", "startHour": 22, "endHour": 24, "rateMultiplier": 1}],
               "solarPolicy": "CARRY_FORWARD"},
    "forecast": {"cycle": "2026-11", "importKwhToDate": 251.4,
                 "projectedKwh": {"p10": 280.1, "p50": 301.9, "p90": 330.2},
                 "projectedBillInr": {"p10": 1702.4, "p50": 1844.9, "p90": 2051.3},
                 "daysToNextSlab": 4, "nextSlabAtKwh": 300, "model": "quantile hist-gradient-boosting"},
    "last30DaysTodKwh": {"NORMAL": 140.2, "SOLAR_OFF_PEAK": 61.3, "EVENING_PEAK": 120.4},
    "openComplaints": 0, "activeOutages": [], "meterAnomalies": [],
}


def ask(client, message):
    response = client.post("/ml/v1/copilot/respond", json={"message": message, "context": CONTEXT})
    assert response.status_code == 200
    return response.json()


def test_explains_a_bill_increase_with_exact_ledger_figures(client):
    body = ask(client, "Why did my bill rise so much this month?")
    assert body["intent"] == "bill_change"
    assert body["providerUsed"] == "deterministic"
    for figure in ("₹2,604.18", "₹1,661.00", "288 kWh", "412.50 kWh", "slab 3", "56.8%"):
        assert figure in body["answer"]


def test_forecasts_and_compares_with_the_budget(client):
    body = ask(client, "What will my next bill be?")
    assert body["intent"] == "forecast"
    assert "₹1,844.90" in body["answer"]
    assert "above your monthly budget of ₹1,050.00" in body["answer"]
    assert "about 4 days" in body["answer"]


def test_savings_are_derived_from_the_customer_tariff(client):
    body = ask(client, "How can I save money at peak time?")
    # A quarter of 120.4 kWh x Rs 5.25 (current slab) x (1.1 - 0.8) = Rs 47.41
    assert "₹47.41" in body["answer"]
    assert body["citations"], "the time-of-day guide should be cited"


def test_rejects_llm_answers_with_ungrounded_numbers(client, monkeypatch):
    client.app.state.settings.copilot_provider = "ollama"
    monkeypatch.setattr(llm, "rewrite", lambda *args: "Your bill is ₹9,999.00 because of a 47% tariff hike.")
    body = ask(client, "Why did my bill rise?")
    assert "rejected" in body["providerUsed"]
    assert "₹9,999.00" not in body["answer"]
    monkeypatch.setattr(llm, "rewrite", lambda *args: "Your October bill was ₹2,604.18, up from ₹1,661.00.")
    assert ask(client, "Why did my bill rise?")["providerUsed"] == "ollama"


def test_falls_back_when_the_provider_is_unreachable(client):
    settings = client.app.state.settings
    settings.copilot_provider = "ollama"
    settings.ollama_url = "http://127.0.0.1:9"
    settings.llm_timeout_seconds = 1
    body = ask(client, "Explain my bill")
    assert body["providerUsed"] == "deterministic (ollama unavailable)"
    assert "INV-202610-A" in body["answer"]


def test_triage_explains_billing_complaints_with_evidence(client):
    response = client.post("/ml/v1/copilot/triage", json={
        "complaint": {"ticketNumber": "TKT-1", "complaintType": "BILLING", "priority": "MEDIUM",
                      "subject": "Bill too high", "slaBreached": True},
        "context": CONTEXT})
    assert response.status_code == 200
    body = response.json()
    assert "43.2%" in body["likelyRootCause"]
    assert "412.50 kWh" in body["suggestedResponse"]
    assert body["checks"][0].startswith("SLA already breached")


OUTAGE = {"title": "Feeder trip at Camp substation", "status": "RESTORING",
          "estimatedRestorationAt": "2026-11-26T15:21:57.284129Z"}


def test_outage_answers_give_restoration_times_in_ist(client):
    response = client.post("/ml/v1/copilot/respond", json={
        "message": "Is there a power cut in my area?", "context": {**CONTEXT, "activeOutages": [OUTAGE]}})
    assert response.status_code == 200
    answer = response.json()["answer"]
    assert "26 Nov, 8:51 pm IST" in answer
    assert "2026-11-26T" not in answer


def test_triage_formats_the_outage_restoration_estimate(client):
    def triage(outages):
        response = client.post("/ml/v1/copilot/triage", json={
            "complaint": {"ticketNumber": "TKT-2", "complaintType": "POWER_OUTAGE", "priority": "CRITICAL",
                          "subject": "No supply", "slaBreached": False},
            "context": {**CONTEXT, "activeOutages": outages}})
        assert response.status_code == 200
        return response.json()

    body = triage([OUTAGE])
    assert "Feeder trip" in body["likelyRootCause"]
    assert any("26 Nov, 8:51 pm IST" in item for item in body["evidence"])
    assert not any("2026-11-26T" in item for item in body["evidence"])
    unknown = triage([{"title": "Cable fault", "status": "INVESTIGATING"}])
    assert any("not yet available" in item for item in unknown["evidence"])

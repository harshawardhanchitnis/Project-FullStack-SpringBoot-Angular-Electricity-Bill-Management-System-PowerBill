import copy
import json

import pytest

from app.config import Settings
from app.services import copilot_engine, llm

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


@pytest.mark.parametrize(("message", "intent"), [
    ("Why do I have to pay more than last month?", "bill_change"),
    ("Why is my projected bill higher?", "forecast"),
    ("Why did my bill rise so much this month?", "bill_change"),
    ("What will my next bill be?", "forecast"),
    ("What will this month's bill be?", "forecast"),
    ("How can I lower my next bill?", "save"),
    ("How can I save money at peak time?", "save"),
    ("Explain time-of-day pricing", "save"),
    ("How do I pay my bill?", "payment"),
    ("What is the late payment surcharge?", "payment"),
    ("What is due today?", "payment"),
    ("Is there a power cut in my area? Why?", "outage"),
    ("Explain my slabs", "slab"),
    ("How does solar net metering work?", "solar"),
    ("Report a meter fault", "anomaly"),
    ("Explain my last bill", "explain_bill"),
    ("Why did my last bill change?", "bill_change"),
    ("I want to raise a complaint about my bill", "complaint"),
    ("Hello", "overview"),
])
def test_intent_classification_weighs_every_keyword(message, intent):
    assert copilot_engine.classify(message) == intent


def test_bill_changes_include_the_gst_line():
    context = copy.deepcopy(CONTEXT)
    for bill, gst in zip(context["bills"], (130.21, 83.05), strict=True):
        bill["lineItems"].append({"category": "GST", "description": "GST", "units": None, "rate": 5,
                                  "amount": gst})
    answer = copilot_engine.Answer("bill_change")
    copilot_engine.bill_change(context, answer)
    assert "- GST: ₹83.05 → ₹130.21." in answer.lines


HISTORY = [{"role": "user", "content": "Why did my bill rise so much?"},
           {"role": "assistant", "content": "Your October 2026 bill is ₹2,604.18, ₹943.18 higher."}]


def test_follow_up_questions_stay_on_the_previous_topic(client):
    def chat(message, history):
        response = client.post("/ml/v1/copilot/respond", json={
            "message": message, "history": history, "context": CONTEXT})
        assert response.status_code == 200
        return response.json()

    follow_up = chat("And the month before?", HISTORY)
    assert follow_up["intent"] == "bill_change"
    assert "₹2,604.18" in follow_up["answer"]
    assert chat("And the month before?", [])["intent"] == "overview"
    assert chat("Thanks!", HISTORY)["intent"] == "overview"
    # A message with its own topic is never overridden by the history.
    assert chat("Is there a power cut?", HISTORY)["intent"] == "outage"


def test_the_llm_rewrite_is_given_the_recent_conversation(client, monkeypatch):
    sent = []

    def fake_post(url, payload, headers, timeout):
        sent.append(payload)
        return {"message": {"content": "Your October bill was ₹2,604.18, up from ₹1,661.00."}}

    monkeypatch.setattr(llm, "_post", fake_post)
    client.app.state.settings.copilot_provider = "ollama"
    response = client.post("/ml/v1/copilot/respond", json={
        "message": "And the month before?", "history": HISTORY, "context": CONTEXT})
    assert response.json()["providerUsed"] == "ollama"
    prompt = sent[0]["messages"][-1]["content"]
    assert "user: Why did my bill rise so much?" in prompt
    assert "assistant: Your October 2026 bill is" in prompt
    assert "QUESTION: And the month before?" in prompt


@pytest.mark.parametrize(("provider", "reply"), [
    ("openai", {"choices": [{"message": {"content": "rewritten"}}]}),
    ("gemini", {"candidates": [{"content": {"parts": [{"text": "rewritten"}]}}]}),
])
def test_hosted_providers_receive_a_trimmed_history(monkeypatch, provider, reply):
    sent = []
    monkeypatch.setattr(llm, "_post", lambda url, payload, headers, timeout: sent.append(payload) or reply)
    settings = Settings(api_key="k", copilot_provider=provider, llm_api_key="provider-test-key")
    history = [{"role": "user", "content": f"turn {index} " + "x" * 900} for index in range(10)]
    assert llm.rewrite(settings, "draft", {}, "question", history) == "rewritten"
    text = json.dumps(sent[0])
    assert "turn 9" in text and "turn 4" in text and "turn 3" not in text
    assert "x" * 600 not in text
    assert llm.rewrite(Settings(api_key="k"), "draft", {}, "question", history) is None


@pytest.mark.parametrize("answer", [
    "You owe ₹25 more this month.",
    "You owe ₹ 25 more this month.",
    "A charge of Rs. 30 was added.",
    "A charge of Rs 30 was added.",
    "A charge of INR 12 was added.",
    "Your usage rose 30% this month.",
    "Your usage rose 30 percent this month.",
    "You used 18 kWh more than usual.",
    "Your sanctioned load is 5 kW.",
    "The supply dipped to 9 V.",
    "You used 12 units more.",
    "You will reach the next slab in 3 days.",
    "Peak hours cost 2x more.",
    "You were billed 4,000 units.",
])
def test_the_grounding_guard_rejects_invented_money_percentages_and_quantities(answer):
    assert llm.ungrounded_numbers(answer, set())


@pytest.mark.parametrize("answer", [
    "Your bill is due on 27 Nov.",
    "1. Run the washing machine at midday.\n2. Set the AC to 24 degrees.",
    "Usage reached slab 3 this time.",
    "Your October bill was ₹2,604.18 for 412.50 kWh, up 56.8%.",
    "You will reach the next slab in about 4 days.",
])
def test_the_grounding_guard_allows_grounded_figures_and_small_bare_numerals(answer):
    allowed = llm.allowed_numbers(CONTEXT, "up 56.8% in about 4 days")
    assert llm.ungrounded_numbers(answer, allowed) == []


def _triage(client, complaint_type, context, linked=None):
    body = {"complaint": {"ticketNumber": "TKT-3", "complaintType": complaint_type, "priority": "MEDIUM",
                          "subject": "Disputed bill", "slaBreached": False}, "context": context}
    if linked is not None:
        body["linkedBill"] = linked
    response = client.post("/ml/v1/copilot/triage", json=body)
    assert response.status_code == 200
    return response.json()


AUGUST = {"billNumber": "INV-202608-C", "period": "2026-08", "importKwh": 250.0, "exportKwh": 0,
          "totalInr": 1402.5, "outstandingInr": 0, "status": "PAID", "dueDate": "2026-09-17",
          "lineItems": [{"category": "SLAB", "description": "Energy slab 1", "units": 100, "rate": 3.25,
                         "amount": 325.0}]}


def _linked(bill):
    """The Core API sends the bill header record and its line items."""
    header = {"id": "4b0c1e0e-0000-0000-0000-000000000001", "billNumber": bill["billNumber"],
              "period": bill["period"], "importKwh": bill["importKwh"], "exportKwh": bill["exportKwh"],
              "totalAmount": bill["totalInr"], "outstanding": bill["outstandingInr"], "status": bill["status"],
              "dueDate": bill["dueDate"]}
    return {"bill": header, "lineItems": bill["lineItems"]}


def test_triage_analyses_the_linked_bill_against_the_one_before_it(client):
    context = {**CONTEXT, "bills": [*CONTEXT["bills"], AUGUST]}
    body = _triage(client, "BILLING", context, _linked(CONTEXT["bills"][1]))
    evidence = " ".join(body["evidence"])
    for figure in ("September 2026", "288 kWh", "₹1,661.00", "August 2026", "250 kWh", "₹1,402.50"):
        assert figure in evidence
    assert "412.50 kWh" not in evidence and "₹2,604.18" not in evidence
    assert "15.2%" in body["likelyRootCause"]
    assert "September 2026" in body["suggestedResponse"] and "288 kWh" in body["suggestedResponse"]
    assert "412.50 kWh" not in body["suggestedResponse"]
    assert any("INV-202609-B" in check and "INV-202608-C" in check for check in body["checks"])


def test_triage_of_the_oldest_linked_bill_describes_that_bill(client):
    body = _triage(client, "BILLING", CONTEXT, _linked(CONTEXT["bills"][1]))
    evidence = " ".join(body["evidence"])
    assert "September 2026" in evidence and "288 kWh" in evidence and "₹1,661.00" in evidence
    assert "412.50 kWh" not in evidence and "October 2026" not in evidence
    assert "INV-202609-B" in body["likelyRootCause"]


def test_triage_formats_anomaly_windows_in_ist(client):
    anomaly = {"anomalyType": "ZERO_READ", "severity": "HIGH", "status": "OPEN", "score": 94,
               "windowStart": "2026-11-20T18:30:00Z", "windowEnd": "2026-11-22T18:30:00Z"}
    body = _triage(client, "METER_FAULT", {**CONTEXT, "meterAnomalies": [anomaly]})
    assert "Zero Read anomaly (high) from 21 Nov, 12:00 am IST." in body["evidence"]
    assert not any("2026-11-20T" in item for item in body["evidence"])


@pytest.mark.parametrize(("days", "phrase"), [
    (0, "at 300 kWh today."), (1, "at 300 kWh in about 1 day."), (7, "at 300 kWh in about 7 days.")])
def test_days_to_the_next_slab_reads_naturally(days, phrase):
    context = copy.deepcopy(CONTEXT)
    context["forecast"]["daysToNextSlab"] = days
    answer = copilot_engine.Answer("forecast")
    copilot_engine.forecast(context, answer)
    text = answer.markdown()
    assert phrase in text
    assert "0 days" not in text and "1 days" not in text


def test_triage_of_a_solar_complaint_uses_the_linked_bill(client):
    september = {**CONTEXT["bills"][1], "exportKwh": 64.0}
    body = _triage(client, "SOLAR_NET_METERING", CONTEXT, _linked(september))
    assert "September 2026" in body["evidence"][0] and "64 kWh" in body["evidence"][0]
    assert "64 kWh" in body["suggestedResponse"]

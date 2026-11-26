"""Deterministic, grounded energy and billing explanations.

Every figure in an answer is read from the context assembled by the Core API (ledger, tariff engine, meter data
and forecast) or derived from it with simple arithmetic shown in the answer. Nothing is guessed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.services.rag_store import RagStore

INTENTS: list[tuple[str, tuple[str, ...]]] = [
    ("outage", ("outage", "power cut", "no power", "no electricity", "restor", "blackout")),
    ("payment", ("pay", "due", "outstanding", "late fee", "surcharge", "overdue", "receipt")),
    ("bill_change", ("why", "increase", "higher", "rise", "rose", "jump", "spike", "more than last", "compare")),
    ("forecast", ("forecast", "next bill", "predict", "estimate", "projected", "end of month", "this month")),
    ("save", ("save", "reduce", "lower", "tip", "cheaper", "peak", "time of day", "tod", "shift")),
    ("solar", ("solar", "net meter", "export", "rooftop", "prosumer")),
    ("slab", ("slab", "tier", "telescopic", "rate per unit", "per kwh")),
    ("anomaly", ("anomal", "tamper", "unusual", "meter fault", "strange")),
    ("complaint", ("complaint", "ticket", "support", "issue")),
    ("explain_bill", ("bill", "charge", "invoice", "breakdown", "fixed charge", "duty", "fac")),
]
SUGGESTIONS = {
    "bill_change": ["How can I lower my next bill?", "What will this month's bill be?", "Explain my slabs"],
    "forecast": ["Why did my last bill change?", "How do I avoid the next slab?", "Show ways to save"],
    "save": ["What will this month's bill be?", "Explain time-of-day pricing", "How does solar net metering work?"],
    "slab": ["What will this month's bill be?", "How can I lower my next bill?", "Why did my bill rise?"],
    "solar": ["What will this month's bill be?", "Explain my last bill", "How can I lower my next bill?"],
    "payment": ["Explain my last bill", "What is the late payment surcharge?", "What will this month's bill be?"],
    "outage": ["How do I report a power cut?", "Explain my last bill", "What will this month's bill be?"],
    "complaint": ["Why did my bill rise?", "Is there an outage in my area?", "Explain my last bill"],
    "anomaly": ["Explain my last bill", "How can I lower my next bill?", "Report a meter fault"],
    "explain_bill": ["Why did my bill change?", "What will this month's bill be?", "How can I save?"],
    "overview": ["Why did my bill change?", "What will this month's bill be?", "How can I lower my next bill?"],
}


def inr(value: Any) -> str:
    amount = float(value)
    sign = "-" if amount < 0 else ""
    whole, fraction = f"{abs(amount):.2f}".split(".")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        whole = ",".join(groups + [tail])
    return f"{sign}₹{whole}.{fraction}"


MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]


def period(value: str) -> str:
    """Formats a YYYY-MM billing period as 'October 2026'."""
    try:
        year, month = value.split("-")
        return f"{MONTHS[int(month) - 1]} {year}"
    except (ValueError, IndexError):
        return value


def kwh(value: Any) -> str:
    return f"{float(value):,.2f} kWh".replace(".00 kWh", " kWh")


IST = timezone(timedelta(hours=5, minutes=30), "IST")


def moment(value: Any, missing: str = "being assessed") -> str:
    """Formats an ISO-8601 instant as '28 Nov, 12:51 am IST'; India has no daylight saving, so the offset is fixed."""
    if not value:
        return missing
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return missing
    local = (parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)).astimezone(IST)
    hour = local.hour % 12 or 12
    meridiem = "am" if local.hour < 12 else "pm"
    return f"{local.day} {MONTHS[local.month - 1][:3]}, {hour}:{local.minute:02d} {meridiem} IST"


def classify(message: str) -> str:
    text = message.lower()
    for intent, keywords in INTENTS:
        if any(keyword in text for keyword in keywords):
            return intent
    return "overview"


@dataclass
class Answer:
    intent: str
    lines: list[str] = field(default_factory=list)
    citations: list[dict[str, str]] = field(default_factory=list)

    def markdown(self) -> str:
        return "\n".join(self.lines).strip()


def _categories(bill: dict[str, Any]) -> dict[str, float]:
    totals: dict[str, float] = {}
    for line in bill.get("lineItems", []):
        category = line.get("category", "OTHER")
        totals[category] = totals.get(category, 0.0) + float(line.get("amount", 0))
    return totals


def _slab_count(bill: dict[str, Any]) -> int:
    return sum(1 for line in bill.get("lineItems", []) if line.get("category") == "SLAB")


def _window(context: dict[str, Any], name: str) -> dict[str, Any] | None:
    for window in context.get("tariff", {}).get("timeWindows", []):
        if window.get("name") == name:
            return window
    return None


def _current_rate(context: dict[str, Any], used: float) -> float | None:
    for slab in context.get("tariff", {}).get("slabs", []):
        until = slab.get("untilKwh")
        if until is None or used < float(until):
            return float(slab.get("ratePerKwh"))
    return None


def bill_change(context: dict[str, Any], answer: Answer) -> None:
    bills = context.get("bills", [])
    if len(bills) < 2:
        explain_bill(context, answer)
        return
    latest, previous = bills[0], bills[1]
    delta_kwh = float(latest["importKwh"]) - float(previous["importKwh"])
    delta_total = float(latest["totalInr"]) - float(previous["totalInr"])
    change = delta_total / float(previous["totalInr"]) * 100 if float(previous["totalInr"]) else 0
    direction = "higher" if delta_total >= 0 else "lower"
    answer.lines.append(
        f"Your **{period(latest['period'])}** bill ({latest['billNumber']}) is **{inr(latest['totalInr'])}**, "
        f"{inr(abs(delta_total))} {direction} than {period(previous['period'])} ({inr(previous['totalInr'])}), "
        f"a change of {abs(change):.1f}%.")
    answer.lines.append("")
    answer.lines.append(f"- Imported energy went from {kwh(previous['importKwh'])} to {kwh(latest['importKwh'])} "
                        f"({'+' if delta_kwh >= 0 else '-'}{kwh(abs(delta_kwh))}).")
    now, before = _categories(latest), _categories(previous)
    labels = {"SLAB": "Energy (slab) charges", "TOD": "Time-of-day adjustment", "FIXED": "Fixed charge",
              "FAC": "Fuel adjustment charge", "DUTY": "Electricity duty", "SOLAR_CREDIT": "Solar credit"}
    for category, label in labels.items():
        if category in now or category in before:
            a, b = before.get(category, 0.0), now.get(category, 0.0)
            if abs(b - a) >= 0.01:
                answer.lines.append(f"- {label}: {inr(a)} → {inr(b)}.")
    slabs_now, slabs_before = _slab_count(latest), _slab_count(previous)
    if slabs_now > slabs_before:
        answer.lines.append(f"- Usage reached **slab {slabs_now}** this time (slab {slabs_before} before), so the "
                            "extra units were charged at a higher rate.")
    answer.lines.append("")
    if delta_kwh > 0:
        answer.lines.append("The change is driven by higher consumption. Check seasonal loads such as air "
                            "conditioning and appliances running in the evening peak.")
    else:
        answer.lines.append("Consumption did not rise, so the difference comes from the charges listed above.")


def explain_bill(context: dict[str, Any], answer: Answer) -> None:
    bills = context.get("bills", [])
    if not bills:
        answer.lines.append("You have no bills yet for this meter. Your first bill is issued after a complete "
                            "billing month of validated readings.")
        return
    bill = bills[0]
    answer.lines.append(f"Your **{period(bill['period'])}** bill ({bill['billNumber']}) totals "
                        f"**{inr(bill['totalInr'])}** "
                        f"for {kwh(bill['importKwh'])} imported. Status: {bill['status'].replace('_', ' ').lower()}"
                        f", due {bill['dueDate']}.")
    answer.lines.append("")
    for line in bill.get("lineItems", []):
        units = f" — {float(line['units']):g} × ₹{float(line['rate']):g}" if line.get("units") and line.get("rate") \
            else ""
        answer.lines.append(f"- {line['description']}{units}: {inr(line['amount'])}")


def forecast(context: dict[str, Any], answer: Answer) -> None:
    data = context.get("forecast")
    if not data:
        answer.lines.append("A forecast is not available yet. It needs at least two weeks of readings.")
        return
    bill, energy = data["projectedBillInr"], data["projectedKwh"]
    answer.lines.append(f"For **{period(data['cycle'])}**, you have used {kwh(data['importKwhToDate'])} so far. The "
                        f"projected bill is **{inr(bill['p50'])}** (likely range {inr(bill['p10'])} to "
                        f"{inr(bill['p90'])}) for about {kwh(energy['p50'])}.")
    if data.get("daysToNextSlab") not in (None, "none"):
        answer.lines.append(f"- At the current pace you will enter the next slab at {kwh(data['nextSlabAtKwh'])} "
                            f"in about {data['daysToNextSlab']} days.")
    budget = context.get("account", {}).get("monthlyBudgetInr")
    if isinstance(budget, (int, float)) and budget:
        status = "above" if float(bill["p50"]) > float(budget) else "within"
        answer.lines.append(f"- That is {status} your monthly budget of {inr(budget)}.")
    answer.lines.append(f"- Forecast model: {data.get('model', 'run-rate')}.")


def save(context: dict[str, Any], answer: Answer) -> None:
    tod = context.get("last30DaysTodKwh", {})
    peak = float(tod.get("EVENING_PEAK", 0))
    peak_window, solar_window = _window(context, "EVENING_PEAK"), _window(context, "SOLAR_OFF_PEAK")
    used = float(context.get("forecast", {}).get("importKwhToDate", 0) or 0)
    rate = _current_rate(context, used)
    answer.lines.append("Here is how to lower your bill using your own usage and tariff:")
    answer.lines.append("")
    if peak and peak_window and solar_window and rate:
        multiplier_gap = float(peak_window["rateMultiplier"]) - float(solar_window["rateMultiplier"])
        shifted = peak * 0.25
        saving = shifted * rate * multiplier_gap
        answer.lines.append(f"- In the last 30 days you used {kwh(peak)} in the evening peak "
                            f"({peak_window['startHour']}:00–{peak_window['endHour']}:00), priced at "
                            f"{float(peak_window['rateMultiplier']):g}× the slab rate. Solar hours "
                            f"({solar_window['startHour']}:00–{solar_window['endHour']}:00) cost "
                            f"{float(solar_window['rateMultiplier']):g}×.")
        answer.lines.append(f"- Moving a quarter of that ({kwh(shifted)}) to solar hours would save about "
                            f"**{inr(saving)} a month** at your current rate of ₹{rate:g}/kWh.")
    answer.lines.append("- Run washing machines, water heaters, pumps and EV charging between 9 am and 5 pm.")
    answer.lines.append("- Set air conditioners to 24–26 °C; each degree lower adds noticeably to consumption.")
    answer.lines.append("- Try the What-If simulator to test load shifting and rooftop solar on your real readings.")


def slab(context: dict[str, Any], answer: Answer) -> None:
    slabs = context.get("tariff", {}).get("slabs", [])
    used = float(context.get("forecast", {}).get("importKwhToDate", 0) or 0)
    answer.lines.append(f"Your tariff ({context.get('tariff', {}).get('name', 'current plan')}) uses telescopic "
                        "slabs — only the units inside each slab are charged at that slab's rate:")
    answer.lines.append("")
    for index, item in enumerate(slabs, start=1):
        upper = f"{float(item['untilKwh']):g}" if item.get("untilKwh") is not None else "and above"
        answer.lines.append(f"- Slab {index}: {float(item['fromKwh']):g}–{upper} kWh at ₹{float(item['ratePerKwh']):g}"
                            "/kWh")
    if used:
        answer.lines.append("")
        answer.lines.append(f"This month you have used {kwh(used)} so far.")


def solar(context: dict[str, Any], answer: Answer) -> None:
    meter = context.get("meter", {})
    policy = context.get("tariff", {}).get("solarPolicy", "CARRY_FORWARD")
    if not meter.get("solarNetMetering"):
        answer.lines.append("This meter is not enrolled in net metering. With rooftop solar, exported units offset "
                            "your imports and any surplus is carried forward. Use the What-If simulator to estimate "
                            "savings and payback for a rooftop system on your real readings.")
        return
    bills = context.get("bills", [])
    if bills:
        bill = bills[0]
        answer.lines.append(f"In {period(bill['period'])} you imported {kwh(bill['importKwh'])} and exported "
                            f"{kwh(bill['exportKwh'])} from rooftop solar.")
    answer.lines.append("Exports first offset imports in the same cycle. " + (
        "Any surplus is carried forward as a kWh credit to the next bill." if policy == "CARRY_FORWARD"
        else "Any surplus is paid as a cash credit at the solar export rate."))


def payment(context: dict[str, Any], answer: Answer) -> None:
    due = context.get("receivables", {})
    principal, fees = float(due.get("principalDue", 0)), float(due.get("lateFeesDue", 0))
    if not due.get("openBills"):
        answer.lines.append("You have no unpaid bills. Thank you for paying on time.")
        return
    answer.lines.append(f"You have **{due['openBills']} unpaid bill(s)** totalling **{inr(principal + fees)}**"
                        + (f", including {inr(fees)} late payment surcharge" if fees else "") + ".")
    if due.get("overdueBills"):
        answer.lines.append(f"- {due['overdueBills']} bill(s) are overdue; the surcharge grows daily until paid.")
    if due.get("nextDueDate"):
        answer.lines.append(f"- Earliest due date: {due['nextDueDate']}.")
    answer.lines.append("- Select several bills on the Bills page to pay them together in one secure checkout.")


def outage(context: dict[str, Any], answer: Answer) -> None:
    outages = context.get("activeOutages", [])
    if not outages:
        answer.lines.append("There is no declared outage in your area right now. If you have no supply, raise a "
                            "Power outage complaint; it is handled as critical with a 4-hour service level.")
        return
    for item in outages:
        answer.lines.append(f"- **{item['title']}** — status: {item['status'].replace('_', ' ').lower()}; "
                            f"estimated restoration: {moment(item.get('estimatedRestorationAt'))}.")


def complaint(context: dict[str, Any], answer: Answer) -> None:
    count = context.get("openComplaints", 0)
    answer.lines.append(f"You have {count} open complaint(s). " if count else "You have no open complaints. ")
    answer.lines.append("Raise a new one from Support, linked to the meter or bill concerned. Power outages are "
                        "resolved within 4 hours, meter and voltage faults within 24 hours and billing questions "
                        "within 48 hours.")


def anomaly(context: dict[str, Any], answer: Answer) -> None:
    items = context.get("meterAnomalies", [])
    if not items:
        answer.lines.append("No unusual consumption patterns have been flagged on this meter.")
        return
    answer.lines.append("Our checks flagged these patterns on your meter:")
    for item in items:
        answer.lines.append(f"- {item['anomalyType'].replace('_', ' ').title()} ({item['severity'].lower()}), "
                            f"status {item['status'].replace('_', ' ').lower()}.")
    answer.lines.append("A field engineer reviews each case. Nothing is added to your bill until it is confirmed.")


def overview(context: dict[str, Any], answer: Answer) -> None:
    name = context.get("account", {}).get("name", "there")
    answer.lines.append(f"Hello {name.split()[0] if name else 'there'}! I can explain your bills, forecast this "
                        "month and suggest savings, using your own meter data.")
    answer.lines.append("")
    if context.get("bills"):
        bill = context["bills"][0]
        answer.lines.append(f"- Latest bill: {period(bill['period'])}, {inr(bill['totalInr'])} "
                            f"({bill['status'].lower()}).")
    if context.get("forecast"):
        answer.lines.append(f"- This month so far: {kwh(context['forecast']['importKwhToDate'])}; projected bill "
                            f"{inr(context['forecast']['projectedBillInr']['p50'])}.")


HANDLERS = {"bill_change": bill_change, "explain_bill": explain_bill, "forecast": forecast, "save": save,
            "slab": slab, "solar": solar, "payment": payment, "outage": outage, "complaint": complaint,
            "anomaly": anomaly, "overview": overview}


def respond(message: str, context: dict[str, Any], rag: RagStore) -> Answer:
    intent = classify(message)
    answer = Answer(intent)
    HANDLERS[intent](context, answer)
    for passage, _ in rag.search(message, limit=1):
        sentences = re.split(r"(?<=\.)\s+", passage.text)
        answer.lines.append("")
        answer.lines.append(f"> **{passage.title}:** {' '.join(sentences[:2])}")
        answer.citations.append({"id": passage.doc_id, "title": passage.title})
    return answer


def suggestions(intent: str) -> list[str]:
    return SUGGESTIONS.get(intent, SUGGESTIONS["overview"])


def triage(complaint_data: dict[str, Any], context: dict[str, Any], linked: dict[str, Any] | None) -> dict[str, Any]:
    kind = complaint_data.get("complaintType", "OTHER")
    evidence: list[str] = []
    checks: list[str] = []
    priority = complaint_data.get("priority", "MEDIUM")
    bills = context.get("bills", [])
    anomalies = context.get("meterAnomalies", [])
    outages = context.get("activeOutages", [])
    cause = "Insufficient data to determine a cause automatically."
    response = "Thank you for contacting us. We are reviewing your request and will update you shortly."

    if kind == "BILLING" and len(bills) >= 2:
        latest, previous = bills[0], bills[1]
        delta = float(latest["importKwh"]) - float(previous["importKwh"])
        pct = delta / float(previous["importKwh"]) * 100 if float(previous["importKwh"]) else 0
        evidence.append(f"{period(latest['period'])}: {kwh(latest['importKwh'])}, {inr(latest['totalInr'])}; "
                        f"{period(previous['period'])}: {kwh(previous['importKwh'])}, {inr(previous['totalInr'])}.")
        if _slab_count(latest) > _slab_count(previous):
            evidence.append(f"Usage reached slab {_slab_count(latest)} (slab {_slab_count(previous)} before).")
        if pct >= 10:
            cause = f"Consumption rose {pct:.1f}%, which explains the higher bill; tariff charges were applied " \
                    "correctly."
            response = (f"We reviewed your {period(latest['period'])} bill. Your meter recorded "
                        f"{kwh(latest['importKwh'])} "
                        f"compared with {kwh(previous['importKwh'])} the month before, so more units were charged"
                        + (f", including units in slab {_slab_count(latest)}" if _slab_count(latest) >
                           _slab_count(previous) else "") + ". The bill is correct. Shifting evening usage to "
                        "daytime solar hours can lower future bills.")
        else:
            cause = "Consumption is broadly stable; review line items and any adjustments for the difference."
        checks += ["Compare the line items of the last two invoices.", "Confirm no VEE-flagged hours were billed."]
    elif kind == "POWER_OUTAGE":
        priority = "CRITICAL"
        if outages:
            item = outages[0]
            cause = f"Known outage in the area: {item['title']} ({item['status'].replace('_', ' ').lower()})."
            evidence.append(f"Active incident: {item['title']}, restoration estimate "
                            f"{moment(item.get('estimatedRestorationAt'), 'not yet available')}.")
            response = (f"Your area is affected by a known outage: {item['title']}. Our crews are working on it and "
                        "we will notify you as soon as supply is restored.")
        else:
            cause = "No declared outage covers this premise; likely a service-line, fuse or meter fault."
            checks.append("Dispatch a field engineer to inspect the service line and meter.")
    elif kind in ("METER_FAULT", "VOLTAGE_FLUCTUATION"):
        priority = "HIGH"
        related = [a for a in anomalies if a.get("anomalyType") in ("ZERO_READ", "VOLTAGE_FAULT",
                                                                     "POWER_FACTOR_DROP", "TAMPER_SUSPECTED")]
        for item in related:
            evidence.append(f"{item['anomalyType'].replace('_', ' ').title()} anomaly ({item['severity'].lower()}) "
                            f"from {item['windowStart']}.")
        cause = ("Meter data shows matching anomalies; a field inspection is needed." if related
                 else "No matching anomaly in recent telemetry; inspect the installation on site.")
        checks.append("Schedule a field visit and record a check-meter reading.")
        if kind == "VOLTAGE_FLUCTUATION":
            checks.append("Check the service cable, neutral connection and distribution transformer loading.")
            response = ("We have scheduled a field engineer to check the voltage at your premises and the local "
                        "transformer. Until then, please switch sensitive equipment to a stabiliser.")
        else:
            response = ("We have scheduled a field engineer to inspect your meter. Your supply is not affected, and "
                        "no bill is issued from readings that fail our checks until the meter is verified.")
    elif kind == "SOLAR_NET_METERING" and bills:
        latest = bills[0]
        evidence.append(f"{period(latest['period'])}: imported {kwh(latest['importKwh'])}, exported "
                        f"{kwh(latest['exportKwh'])}.")
        cause = "Exports are netted against imports; surplus is carried forward as kWh credit per tariff policy."
        response = (f"In {period(latest['period'])} your meter recorded {kwh(latest['exportKwh'])} of solar "
                    "export, which "
                    "was offset against your imports. Any surplus is carried forward to your next bill.")
    for item in anomalies[:2]:
        if not any(item["anomalyType"].replace("_", " ").title() in line for line in evidence):
            evidence.append(f"Open anomaly on the meter: {item['anomalyType'].replace('_', ' ').lower()} "
                            f"({item['severity'].lower()}).")
    if complaint_data.get("slaBreached"):
        checks.insert(0, "SLA already breached: respond to the customer immediately.")
    summary = (f"{kind.replace('_', ' ').title()} complaint {complaint_data.get('ticketNumber', '')}: "
               f"{complaint_data.get('subject', '').strip()}")
    return {"summary": summary, "likelyRootCause": cause, "suggestedPriority": priority,
            "suggestedResponse": response, "checks": checks or ["Review the customer's recent activity."],
            "evidence": evidence}

"""Optional LLM rewriting with a strict numeric grounding guard.

The deterministic explainer always produces the answer first. A configured provider may rephrase it, but the
rephrased text is rejected when it contains any number that does not appear in the facts it was given.
"""

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation
from typing import Any

from app.config import Settings

LOG = logging.getLogger(__name__)
NUMBER = re.compile(r"(?<![\w.])\d[\d,]*(?:\.\d+)?")
# Small whole numbers such as the day in "27 Nov", list numbering or "slab 3" may appear without being in the
# facts, but never when they carry money, a percentage, an energy or electrical unit or a duration.
SMALL_NUMERAL = 31
CURRENCY_BEFORE = re.compile(r"(?:₹|\binr|\brs\.?)\s*$", re.IGNORECASE)
UNIT_AFTER = re.compile(
    r"\s*(?:%|per\s?cent\b|rupees?\b|paise\b|kwh\b|kw\b|kva\b|watts?\b|w\b|volts?\b|v\b|units?\b|×|x\b"
    r"|hours?\b|hrs?\b|h\b|days?\b|weeks?\b|months?\b|years?\b)",
    re.IGNORECASE)
HISTORY_TURNS = 6
HISTORY_CHARS = 500
SYSTEM_PROMPT = (
    "You are the PowerBill GridIQ energy assistant for an Indian electricity utility. Rewrite the DRAFT answer "
    "so it reads naturally and stays concise. Use only facts from DRAFT and CONTEXT. Do not introduce any new "
    "number, amount, date or percentage. Keep the rupee symbol and units. RECENT CONVERSATION, when present, only "
    "shows what the customer is following up on; never take figures from it. Answer in markdown."
)


def _normalise(token: str) -> str | None:
    try:
        value = Decimal(token.replace(",", ""))
    except InvalidOperation:
        return None
    return format(value.normalize(), "f")


def allowed_numbers(*sources: Any) -> set[str]:
    allowed: set[str] = set()

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
        elif isinstance(value, bool) or value is None:
            return
        elif isinstance(value, (int, float)):
            for digits in (0, 1, 2):
                normalised = _normalise(f"{round(float(value), digits):.{digits}f}")
                if normalised:
                    allowed.add(normalised)
        else:
            for token in NUMBER.findall(str(value)):
                normalised = _normalise(token)
                if normalised:
                    allowed.add(normalised)

    for source in sources:
        walk(source)
    return allowed


def _is_quantity(text: str, match: re.Match[str]) -> bool:
    """True when the number is money (₹, INR, Rs), a percentage, carries a unit or is a duration."""
    before = text[max(0, match.start() - 6) : match.start()]
    return bool(CURRENCY_BEFORE.search(before) or UNIT_AFTER.match(text, match.end()))


def ungrounded_numbers(answer: str, allowed: set[str]) -> list[str]:
    missing = []
    for match in NUMBER.finditer(answer):
        token = match.group()
        normalised = _normalise(token)
        if normalised is None or normalised in allowed:
            continue
        if "." not in normalised and Decimal(normalised) <= SMALL_NUMERAL and not _is_quantity(answer, match):
            continue
        missing.append(token)
    return missing


def _conversation(history: list[dict[str, str]] | None) -> str:
    """The last few turns, each trimmed, so a provider can tell what a follow-up question refers to."""
    turns = "\n".join(f"{turn.get('role', 'user')}: {' '.join(str(turn.get('content', '')).split())[:HISTORY_CHARS]}"
                      for turn in (history or [])[-HISTORY_TURNS:])
    return f"RECENT CONVERSATION (oldest first):\n{turns}\n\n" if turns else ""


def _post(url: str, payload: dict[str, Any], headers: dict[str, str], timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - configured provider URL
        return json.loads(response.read().decode())


def rewrite(settings: Settings, draft: str, context: dict[str, Any], question: str,
            history: list[dict[str, str]] | None = None) -> str | None:
    provider = settings.copilot_provider.lower()
    if provider == "deterministic":
        return None
    user = (f"{_conversation(history)}QUESTION: {question}\n\nDRAFT:\n{draft}\n\nCONTEXT (JSON):\n"
            f"{json.dumps(context, default=str)[:12000]}")
    try:
        if provider == "ollama":
            body = _post(f"{settings.ollama_url.rstrip('/')}/api/chat", {
                "model": settings.ollama_model, "stream": False, "options": {"temperature": 0.2},
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user}],
            }, {}, settings.llm_timeout_seconds)
            return body["message"]["content"]
        if provider == "openai" and settings.llm_api_key:
            body = _post("https://api.openai.com/v1/chat/completions", {
                "model": settings.openai_model, "temperature": 0.2,
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user}],
            }, {"Authorization": f"Bearer {settings.llm_api_key}"}, settings.llm_timeout_seconds)
            return body["choices"][0]["message"]["content"]
        if provider == "gemini" and settings.llm_api_key:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{settings.gemini_model}:generateContent"
            body = _post(url, {
                "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                "contents": [{"role": "user", "parts": [{"text": user}]}],
                "generationConfig": {"temperature": 0.2},
            }, {"x-goog-api-key": settings.llm_api_key}, settings.llm_timeout_seconds)
            return body["candidates"][0]["content"]["parts"][0]["text"]
    except (urllib.error.URLError, TimeoutError, KeyError, IndexError, ValueError) as error:
        LOG.warning("LLM provider %s failed (%s); using the deterministic answer", provider, type(error).__name__)
    return None

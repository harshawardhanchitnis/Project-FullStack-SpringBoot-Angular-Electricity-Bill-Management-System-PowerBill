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
SYSTEM_PROMPT = (
    "You are the PowerBill GridIQ energy assistant for an Indian electricity utility. Rewrite the DRAFT answer "
    "so it reads naturally and stays concise. Use only facts from DRAFT and CONTEXT. Do not introduce any new "
    "number, amount, date or percentage. Keep the rupee symbol and units. Answer in markdown."
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


def ungrounded_numbers(answer: str, allowed: set[str]) -> list[str]:
    missing = []
    for token in NUMBER.findall(answer):
        normalised = _normalise(token)
        if normalised is None:
            continue
        if normalised in allowed or (Decimal(normalised) <= 31 and "." not in normalised):
            continue
        missing.append(token)
    return missing


def _post(url: str, payload: dict[str, Any], headers: dict[str, str], timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - configured provider URL
        return json.loads(response.read().decode())


def rewrite(settings: Settings, draft: str, context: dict[str, Any], question: str) -> str | None:
    provider = settings.copilot_provider.lower()
    if provider == "deterministic":
        return None
    user = (f"QUESTION: {question}\n\nDRAFT:\n{draft}\n\nCONTEXT (JSON):\n"
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

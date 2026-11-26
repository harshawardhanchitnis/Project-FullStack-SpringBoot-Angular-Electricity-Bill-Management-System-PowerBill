from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


class DailyUsage(BaseModel):
    day: date
    importKwh: float = Field(ge=0)
    exportKwh: float = Field(default=0, ge=0)


class ForecastRequest(BaseModel):
    meterId: str = Field(max_length=64)
    asOf: date
    cycleEnd: date
    history: list[DailyUsage] = Field(min_length=7, max_length=800)

    @field_validator("cycleEnd")
    @classmethod
    def cycle_after_as_of(cls, value: date, info: Any) -> date:
        as_of = info.data.get("asOf")
        if as_of and value <= as_of:
            raise ValueError("cycleEnd must be after asOf")
        if as_of and (value - as_of).days > 62:
            raise ValueError("The forecast horizon is limited to 62 days")
        return value


class DailyBand(BaseModel):
    day: date
    p10: float
    p50: float
    p90: float


class ForecastResponse(BaseModel):
    meterId: str
    horizonDays: int
    predictedKwhP10: float
    predictedKwhP50: float
    predictedKwhP90: float
    daily: list[DailyBand]
    trendPct: float | None
    model: str
    backtestMapePct: float | None


class HourlyReading(BaseModel):
    start: datetime
    importKwh: float = Field(ge=0)
    exportKwh: float = Field(default=0, ge=0)


class AnomalyRequest(BaseModel):
    meterId: str = Field(max_length=64)
    solarNetMetering: bool = False
    readings: list[HourlyReading] = Field(min_length=24, max_length=24 * 120)


class Reason(BaseModel):
    code: str
    detail: str


class Anomaly(BaseModel):
    windowStart: datetime
    windowEnd: datetime
    anomalyType: Literal["SPIKE", "ZERO_READ", "TAMPER_SUSPECTED", "VOLTAGE_FAULT", "POWER_FACTOR_DROP"]
    severity: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    score: float = Field(ge=0, le=100)
    reasons: list[Reason]


class AnomalyResponse(BaseModel):
    meterId: str
    anomalies: list[Anomaly]
    model: str


class ChatTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=4000)


class CopilotRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)
    audience: Literal["CUSTOMER", "AGENT"] = "CUSTOMER"
    history: list[ChatTurn] = Field(default_factory=list, max_length=20)
    context: dict[str, Any] = Field(default_factory=dict)


class Citation(BaseModel):
    id: str
    title: str


class CopilotResponse(BaseModel):
    answer: str
    intent: str
    citations: list[Citation]
    suggestions: list[str]
    providerUsed: str
    grounded: bool


class TriageRequest(BaseModel):
    complaint: dict[str, Any]
    context: dict[str, Any] = Field(default_factory=dict)
    linkedBill: dict[str, Any] | None = None


class TriageResponse(BaseModel):
    summary: str
    likelyRootCause: str
    suggestedPriority: Literal["CRITICAL", "HIGH", "MEDIUM", "LOW"]
    suggestedResponse: str
    checks: list[str]
    evidence: list[str]
    providerUsed: str

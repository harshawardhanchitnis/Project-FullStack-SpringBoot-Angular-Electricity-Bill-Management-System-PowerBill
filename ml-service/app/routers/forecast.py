from fastapi import APIRouter

from app.schemas import DailyBand, ForecastRequest, ForecastResponse
from app.services import forecaster

router = APIRouter(prefix="/ml/v1", tags=["forecast"])


@router.post("/forecast")
def forecast(request: ForecastRequest) -> ForecastResponse:
    history = [(point.day, point.importKwh) for point in request.history if point.day < request.asOf]
    result = forecaster.forecast(history, request.asOf, request.cycleEnd)
    return ForecastResponse(
        meterId=request.meterId,
        horizonDays=len(result.bands),
        predictedKwhP10=round(sum(b.p10 for b in result.bands), 2),
        predictedKwhP50=round(sum(b.p50 for b in result.bands), 2),
        predictedKwhP90=round(sum(b.p90 for b in result.bands), 2),
        daily=[DailyBand(day=b.day, p10=b.p10, p50=b.p50, p90=b.p90) for b in result.bands],
        trendPct=result.trend_pct,
        model=result.model,
        backtestMapePct=result.backtest_mape_pct,
    )

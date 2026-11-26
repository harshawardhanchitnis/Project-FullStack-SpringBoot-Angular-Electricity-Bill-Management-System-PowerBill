from fastapi import APIRouter

from app.schemas import Anomaly, AnomalyRequest, AnomalyResponse, Reason
from app.services import anomaly_detector

router = APIRouter(prefix="/ml/v1", tags=["anomalies"])


@router.post("/anomalies/detect")
def detect(request: AnomalyRequest) -> AnomalyResponse:
    readings = [(r.start, r.importKwh, r.exportKwh) for r in request.readings]
    findings = anomaly_detector.detect(readings, request.solarNetMetering)
    return AnomalyResponse(
        meterId=request.meterId,
        model=anomaly_detector.MODEL_NAME,
        anomalies=[
            Anomaly(windowStart=f.start, windowEnd=f.end, anomalyType=f.anomaly_type, severity=f.severity,
                    score=f.score, reasons=[Reason(**reason) for reason in f.reasons])
            for f in findings
        ],
    )

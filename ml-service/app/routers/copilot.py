from fastapi import APIRouter, Request

from app.schemas import Citation, CopilotRequest, CopilotResponse, TriageRequest, TriageResponse
from app.services import copilot_engine, llm

router = APIRouter(prefix="/ml/v1/copilot", tags=["copilot"])


@router.post("/respond")
def respond(body: CopilotRequest, request: Request) -> CopilotResponse:
    settings = request.app.state.settings
    # Prior turns from the Core API: they keep follow-ups on topic and give an LLM rewrite its context. Their
    # figures are never added to the grounding allow-list, because the client supplies them.
    history = [turn.model_dump() for turn in body.history]
    answer = copilot_engine.respond(body.message, body.context, request.app.state.rag, history)
    draft = answer.markdown()
    text, provider = draft, "deterministic"
    rewritten = llm.rewrite(settings, draft, body.context, body.message, history)
    if rewritten:
        allowed = llm.allowed_numbers(body.context, draft, body.message)
        if llm.ungrounded_numbers(rewritten, allowed):
            provider = f"deterministic (rejected {settings.copilot_provider} answer with ungrounded figures)"
        else:
            text, provider = rewritten.strip(), settings.copilot_provider
    elif settings.copilot_provider != "deterministic":
        provider = f"deterministic ({settings.copilot_provider} unavailable)"
    return CopilotResponse(
        answer=text, intent=answer.intent, citations=[Citation(**c) for c in answer.citations],
        suggestions=copilot_engine.suggestions(answer.intent), providerUsed=provider, grounded=True)


@router.post("/triage")
def triage(body: TriageRequest) -> TriageResponse:
    result = copilot_engine.triage(body.complaint, body.context, body.linkedBill)
    return TriageResponse(**result, providerUsed="deterministic")

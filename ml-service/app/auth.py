import hmac

from fastapi import Header, HTTPException, Request, status


def require_internal_key(
    request: Request, x_internal_api_key: str | None = Header(default=None)
) -> None:
    """Allow only the Core API, which shares the generated internal key."""
    expected: str = request.app.state.api_key
    if not expected:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "The internal API key is not configured."
        )
    supplied = x_internal_api_key or ""
    if not hmac.compare_digest(supplied.encode(), expected.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid internal API key.")

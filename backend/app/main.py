import secrets

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from fastapi.middleware.cors import CORSMiddleware

from .config import Settings, get_settings
from .models import (
    BackendSessionTokenResponse,
    RealtimeClientSecretRequest,
    RealtimeClientSecretResponse,
    SdpOfferRequest,
    TextTranslationRequest,
    TextTranslationResponse,
)
from .openai_realtime import create_client_secret, create_realtime_call_answer
from .openai_text import create_text_translation
from .session_auth import create_session_token, verify_session_token

app = FastAPI(title="Duddy Translator Backend", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:5190", "http://localhost:5190"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type", "X-Duddy-App-Token"],
)


def verify_app_token(
    x_duddy_app_token: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.allowed_app_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Backend app token is not configured.",
        )
    # Constant-time comparison avoids leaking the token through response timing.
    if x_duddy_app_token is None or not secrets.compare_digest(
        x_duddy_app_token, settings.allowed_app_token
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid app token.",
        )


def verify_backend_session_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()

    if not token or not verify_session_token(token, settings):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired session token.",
        )


@app.get("/health")
def health(settings: Settings = Depends(get_settings)) -> dict:
    return {
        "ok": True,
        "configured": bool(settings.openai_api_key),
        "model": settings.openai_realtime_model,
        "text_model": settings.openai_text_model,
    }


@app.post(
    "/api/auth/session-token",
    response_model=BackendSessionTokenResponse,
    dependencies=[Depends(verify_app_token)],
)
def auth_session_token(settings: Settings = Depends(get_settings)) -> BackendSessionTokenResponse:
    token, expires_at = create_session_token(settings)
    return BackendSessionTokenResponse(session_token=token, expires_at=expires_at)


@app.post(
    "/api/realtime/client-secret",
    response_model=RealtimeClientSecretResponse,
    dependencies=[Depends(verify_backend_session_token)],
)
async def realtime_client_secret(
    request: RealtimeClientSecretRequest,
    settings: Settings = Depends(get_settings),
) -> RealtimeClientSecretResponse:
    try:
        payload = await create_client_secret(request, settings)
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
    except Exception as error:
        raise HTTPException(status_code=502, detail="OpenAI realtime session creation failed.") from error

    client_secret = payload.get("value") or payload.get("client_secret", {}).get("value")
    expires_at = payload.get("expires_at") or payload.get("client_secret", {}).get("expires_at") or 0
    if not client_secret:
        raise HTTPException(status_code=502, detail="OpenAI did not return a client secret.")

    return RealtimeClientSecretResponse(
        client_secret=client_secret,
        expires_at=expires_at,
        model=settings.openai_realtime_model,
    )


@app.post(
    "/api/realtime/call",
    dependencies=[Depends(verify_backend_session_token)],
)
async def realtime_call(
    request: SdpOfferRequest,
    settings: Settings = Depends(get_settings),
) -> Response:
    try:
        answer_sdp = await create_realtime_call_answer(request, settings)
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
    except Exception as error:
        raise HTTPException(status_code=502, detail="OpenAI realtime call creation failed.") from error

    return Response(content=answer_sdp, media_type="application/sdp")


@app.post(
    "/api/translate/text",
    response_model=TextTranslationResponse,
    dependencies=[Depends(verify_backend_session_token)],
)
async def translate_text(
    request: TextTranslationRequest,
    settings: Settings = Depends(get_settings),
) -> TextTranslationResponse:
    try:
        payload = await create_text_translation(request, settings)
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
    except Exception as error:
        raise HTTPException(status_code=502, detail="OpenAI text translation failed.") from error

    return TextTranslationResponse(**payload)

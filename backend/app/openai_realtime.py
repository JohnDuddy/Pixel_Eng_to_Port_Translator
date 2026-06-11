import json

import httpx

from .config import Settings
from .interpreter_prompt import INTERPRETER_CORE_INSTRUCTIONS
from .models import RealtimeClientSecretRequest, SdpOfferRequest, TranslatorMode


def build_interpreter_instructions(request: RealtimeClientSecretRequest | SdpOfferRequest) -> str:
    base = [
        INTERPRETER_CORE_INSTRUCTIONS,
        "Keep latency low and avoid long commentary unless the selected mode needs precision.",
        "Speak the translated audio naturally in the target language.",
        f"Current direction: {request.source_language} to {request.target_language}.",
        f"Translation style: {request.translation_style.value}.",
    ]

    if request.mode == TranslatorMode.TRAVEL:
        base.append("Travel mode: use short, clear, practical phrasing for restaurants, hotels, airports, shops, and transport.")
    elif request.mode == TranslatorMode.MEDICAL:
        base.append(
            "Medical precision mode: return original transcript, literal translation, and polished clinical translation. "
            "Prefer accuracy over speed and keep symptoms precise."
        )
    elif request.mode == TranslatorMode.CONTINUOUS:
        base.append("Continuous mode: use turn detection and translate each speaker turn without requiring button presses.")
    else:
        base.append("Push-to-talk mode: translate each completed speaker turn.")

    return " ".join(base)


def build_session_config(
    request: RealtimeClientSecretRequest | SdpOfferRequest,
    settings: Settings,
) -> dict:
    return {
        "session": {
            "type": "realtime",
            "model": settings.openai_realtime_model,
            "instructions": build_interpreter_instructions(request),
            "audio": {
                "input": {
                    # PCM16 mono at 24 kHz, matching the Android AudioRecord capture.
                    "format": {"type": "audio/pcm", "rate": 24000},
                    # Enable transcription of the speaker's audio so the app can show the
                    # original text alongside the translated voice.
                    "transcription": {"model": "gpt-4o-mini-transcribe"},
                    # Android streaming uses client-side AudioRecord VAD and manually
                    # commits input_audio_buffer when a turn is ready.
                    "turn_detection": None,
                },
                "output": {
                    "format": {"type": "audio/pcm"},
                    "voice": request.voice,
                },
            },
        },
    }


async def create_client_secret(
    request: RealtimeClientSecretRequest,
    settings: Settings,
) -> dict:
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is not configured on the backend.")

    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.post(
            "https://api.openai.com/v1/realtime/client_secrets",
            headers={
                "Authorization": f"Bearer {settings.openai_api_key}",
                "Content-Type": "application/json",
            },
            json=build_session_config(request, settings),
        )
        response.raise_for_status()
        return response.json()


async def create_realtime_call_answer(
    request: SdpOfferRequest,
    settings: Settings,
) -> str:
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is not configured on the backend.")

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            "https://api.openai.com/v1/realtime/calls",
            headers={
                "Authorization": f"Bearer {settings.openai_api_key}",
            },
            files={
                "sdp": ("offer.sdp", request.sdp, "application/sdp"),
                "session": (None, json.dumps(build_session_config(request, settings)), "application/json"),
            },
        )
        response.raise_for_status()
        return response.text

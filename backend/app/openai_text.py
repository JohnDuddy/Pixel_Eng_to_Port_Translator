import json
import re

import httpx

from .config import Settings
from .models import TextTranslationRequest, TranslatorMode

# JSON schema handed to the Responses API so the model is constrained to return exactly
# these keys. This replaces relying on prompt wording alone; the plain-text parsing in
# parse_translation_json is kept only as a defensive fallback.
TRANSLATION_SCHEMA = {
    "type": "object",
    "properties": {
        "original_text": {"type": "string"},
        "literal_translation": {"type": "string"},
        "polished_translation": {"type": "string"},
    },
    "required": ["original_text", "literal_translation", "polished_translation"],
    "additionalProperties": False,
}


def build_text_translation_prompt(request: TextTranslationRequest) -> str:
    mode_note = {
        TranslatorMode.TRAVEL: "Use short, practical travel phrasing.",
        TranslatorMode.MEDICAL: "Preserve medical details and include precise clinical wording.",
        TranslatorMode.CONTINUOUS: "Translate the speaker turn naturally and concisely.",
        TranslatorMode.PUSH_TO_TALK: "Translate the completed speaker turn.",
    }[request.mode]

    return (
        "You are Duddy Translator, a professional interpreter between English (US) "
        "and Brazilian Portuguese. Return JSON only, with no markdown code fence, using these exact keys: "
        "original_text, literal_translation, polished_translation. "
        f"Direction: {request.source_language} to {request.target_language}. "
        f"Style: {request.translation_style.value}. {mode_note}\n\n"
        f"Text:\n{request.text}"
    )


def extract_response_text(payload: dict) -> str:
    direct = payload.get("output_text")
    if isinstance(direct, str) and direct.strip():
        return direct.strip()

    for item in payload.get("output", []):
        if not isinstance(item, dict):
            continue
        for content in item.get("content", []):
            if not isinstance(content, dict):
                continue
            text = content.get("text")
            if isinstance(text, str) and text.strip():
                return text.strip()

    return ""


def parse_translation_json(text: str, request: TextTranslationRequest, model: str) -> dict:
    text = strip_code_fence(text)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        payload = {
            "original_text": request.text,
            "literal_translation": text,
            "polished_translation": text,
        }

    literal = str(payload.get("literal_translation") or payload.get("translation") or "").strip()
    polished = str(payload.get("polished_translation") or literal).strip()

    return {
        "original_text": str(payload.get("original_text") or request.text).strip(),
        "literal_translation": literal,
        "polished_translation": polished,
        "model": model,
    }


def strip_code_fence(text: str) -> str:
    stripped = text.strip()
    match = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", stripped, flags=re.DOTALL | re.IGNORECASE)
    if match:
        return match.group(1).strip()
    return stripped


async def create_text_translation(
    request: TextTranslationRequest,
    settings: Settings,
) -> dict:
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is not configured on the backend.")

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            "https://api.openai.com/v1/responses",
            headers={
                "Authorization": f"Bearer {settings.openai_api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": settings.openai_text_model,
                "input": build_text_translation_prompt(request),
                "text": {
                    "format": {
                        "type": "json_schema",
                        "name": "translation",
                        "strict": True,
                        "schema": TRANSLATION_SCHEMA,
                    },
                },
            },
        )
        response.raise_for_status()

    output_text = extract_response_text(response.json())
    if not output_text:
        raise RuntimeError("OpenAI did not return translation text.")

    return parse_translation_json(output_text, request, settings.openai_text_model)

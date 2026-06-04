import httpx
import pytest
import respx

from app.config import Settings
from app.models import TextTranslationRequest
from app.openai_text import create_text_translation

RESPONSES_URL = "https://api.openai.com/v1/responses"


def _responses_payload(text: str) -> dict:
    return {
        "output": [
            {
                "type": "message",
                "content": [{"type": "output_text", "text": text}],
            },
        ],
    }


@respx.mock
async def test_create_text_translation_parses_structured_output():
    settings = Settings(openai_api_key="sk-test", openai_text_model="gpt-4.1-mini")
    route = respx.post(RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            json=_responses_payload(
                '{"original_text":"Hello",'
                '"literal_translation":"Ola",'
                '"polished_translation":"Ola!"}'
            ),
        )
    )

    result = await create_text_translation(
        TextTranslationRequest(text="Hello"), settings
    )

    assert route.called
    sent = route.calls.last.request
    assert sent.headers["Authorization"] == "Bearer sk-test"
    body = sent.content.decode()
    # The request must pin the response shape with a json_schema format.
    assert "json_schema" in body
    assert result["original_text"] == "Hello"
    assert result["literal_translation"] == "Ola"
    assert result["polished_translation"] == "Ola!"
    assert result["model"] == "gpt-4.1-mini"


@respx.mock
async def test_create_text_translation_raises_when_output_empty():
    settings = Settings(openai_api_key="sk-test")
    respx.post(RESPONSES_URL).mock(
        return_value=httpx.Response(200, json={"output": []})
    )

    with pytest.raises(RuntimeError):
        await create_text_translation(TextTranslationRequest(text="Hello"), settings)


async def test_create_text_translation_requires_api_key():
    settings = Settings(openai_api_key="")

    with pytest.raises(RuntimeError):
        await create_text_translation(TextTranslationRequest(text="Hello"), settings)


@respx.mock
async def test_create_text_translation_propagates_http_error():
    settings = Settings(openai_api_key="sk-test")
    respx.post(RESPONSES_URL).mock(
        return_value=httpx.Response(500, json={"error": "boom"})
    )

    with pytest.raises(httpx.HTTPStatusError):
        await create_text_translation(TextTranslationRequest(text="Hello"), settings)

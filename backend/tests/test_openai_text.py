from app.config import Settings
from app.models import TextTranslationRequest, TranslationStyle, TranslatorMode
from app.openai_text import build_text_translation_prompt, extract_response_text, parse_translation_json


def test_text_prompt_requests_json_and_direction():
    request = TextTranslationRequest(
        text="Where is the pharmacy?",
        mode=TranslatorMode.TRAVEL,
        source_language="en-US",
        target_language="pt-BR",
        translation_style=TranslationStyle.NATURAL,
    )

    prompt = build_text_translation_prompt(request)

    assert "Return JSON only" in prompt
    assert "en-US to pt-BR" in prompt
    assert "Translate meaning, not word-for-word" in prompt
    assert "Brazilian Portuguese" in prompt
    assert "context from prior turns" in prompt
    assert "spoken aloud immediately" in prompt
    assert "Where is the pharmacy?" in prompt
    assert "travel" in prompt.lower()
    assert "Squamous cell carcinoma = Carcinoma espinocelular" in prompt
    assert "Mohs surgery = Cirurgia de Mohs" in prompt


def test_extract_response_text_from_responses_payload():
    payload = {
        "output": [
            {
                "type": "message",
                "content": [
                    {
                        "type": "output_text",
                        "text": '{"original_text":"Hello","literal_translation":"Ola","polished_translation":"Ola!"}',
                    },
                ],
            },
        ],
    }

    assert extract_response_text(payload).startswith('{"original_text"')


def test_parse_translation_json_falls_back_to_plain_text():
    request = TextTranslationRequest(text="Hello")
    settings = Settings(openai_text_model="gpt-4.1-mini")

    parsed = parse_translation_json("Ola", request, settings.openai_text_model)

    assert parsed["original_text"] == "Hello"
    assert parsed["literal_translation"] == "Ola"
    assert parsed["polished_translation"] == "Ola"
    assert parsed["model"] == "gpt-4.1-mini"


def test_parse_translation_json_strips_markdown_fence():
    request = TextTranslationRequest(text="Hello")

    parsed = parse_translation_json(
        '```json\n{"original_text":"Hello","literal_translation":"Ola","polished_translation":"Ola!"}\n```',
        request,
        "gpt-4.1-mini",
    )

    assert parsed["literal_translation"] == "Ola"
    assert parsed["polished_translation"] == "Ola!"

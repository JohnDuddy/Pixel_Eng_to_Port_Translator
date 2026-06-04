from app.config import Settings
from app.models import RealtimeClientSecretRequest, TranslationStyle, TranslatorMode
from app.openai_realtime import build_interpreter_instructions, build_session_config


def test_medical_mode_instructions_include_precision():
    request = RealtimeClientSecretRequest(
        mode=TranslatorMode.MEDICAL,
        source_language="pt-BR",
        target_language="en-US",
        translation_style=TranslationStyle.LITERAL,
        voice="marin",
    )

    instructions = build_interpreter_instructions(request)

    assert "Medical precision mode" in instructions
    assert "pt-BR to en-US" in instructions
    assert "LITERAL" in instructions


def test_session_config_uses_realtime_model_and_voice():
    request = RealtimeClientSecretRequest(voice="cedar")
    settings = Settings(openai_api_key="test", openai_realtime_model="gpt-realtime")

    config = build_session_config(request, settings)

    assert config["session"]["type"] == "realtime"
    assert config["session"]["model"] == "gpt-realtime"
    assert config["session"]["audio"]["output"]["voice"] == "cedar"
    assert config["session"]["audio"]["input"]["turn_detection"]["type"] == "server_vad"

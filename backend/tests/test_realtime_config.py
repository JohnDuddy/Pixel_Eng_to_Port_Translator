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
    assert "Translate meaning, not word-for-word" in instructions
    assert "literal plus polished clinical translation" in instructions
    assert "spoken aloud immediately" in instructions


def test_session_config_uses_realtime_model_and_voice():
    request = RealtimeClientSecretRequest(voice="cedar")
    settings = Settings(openai_api_key="test", openai_realtime_model="gpt-realtime")

    config = build_session_config(request, settings)

    assert config["session"]["type"] == "realtime"
    assert config["session"]["model"] == "gpt-realtime"
    assert config["session"]["audio"]["output"]["voice"] == "cedar"
    assert config["session"]["audio"]["input"]["turn_detection"] is None


def test_session_config_pins_pcm_audio_and_enables_transcription():
    request = RealtimeClientSecretRequest()
    settings = Settings(openai_api_key="test")

    audio = build_session_config(request, settings)["session"]["audio"]

    assert audio["input"]["format"] == {"type": "audio/pcm", "rate": 24000}
    assert audio["output"]["format"] == {"type": "audio/pcm"}
    assert audio["input"]["transcription"]["model"]

from enum import StrEnum

from pydantic import BaseModel, Field


class TranslatorMode(StrEnum):
    PUSH_TO_TALK = "PUSH_TO_TALK"
    CONTINUOUS = "CONTINUOUS"
    TRAVEL = "TRAVEL"
    MEDICAL = "MEDICAL"


class TranslationStyle(StrEnum):
    LITERAL = "LITERAL"
    BALANCED = "BALANCED"
    NATURAL = "NATURAL"


class RealtimeClientSecretRequest(BaseModel):
    mode: TranslatorMode = TranslatorMode.PUSH_TO_TALK
    source_language: str = Field(default="en-US", pattern="^(en-US|pt-BR)$")
    target_language: str = Field(default="pt-BR", pattern="^(en-US|pt-BR)$")
    translation_style: TranslationStyle = TranslationStyle.NATURAL
    voice: str = Field(default="marin", max_length=32)


class RealtimeClientSecretResponse(BaseModel):
    client_secret: str
    expires_at: int = 0
    model: str


class SdpOfferRequest(BaseModel):
    sdp: str = Field(min_length=8)
    mode: TranslatorMode = TranslatorMode.PUSH_TO_TALK
    source_language: str = Field(default="en-US", pattern="^(en-US|pt-BR)$")
    target_language: str = Field(default="pt-BR", pattern="^(en-US|pt-BR)$")
    translation_style: TranslationStyle = TranslationStyle.NATURAL
    voice: str = Field(default="marin", max_length=32)


class TextTranslationRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    mode: TranslatorMode = TranslatorMode.PUSH_TO_TALK
    source_language: str = Field(default="en-US", pattern="^(en-US|pt-BR)$")
    target_language: str = Field(default="pt-BR", pattern="^(en-US|pt-BR)$")
    translation_style: TranslationStyle = TranslationStyle.NATURAL


class TextTranslationResponse(BaseModel):
    original_text: str
    literal_translation: str
    polished_translation: str
    model: str

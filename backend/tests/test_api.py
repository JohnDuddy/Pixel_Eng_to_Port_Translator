import httpx
import respx
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


def test_health_returns_model_and_configured_flag():
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert "configured" in payload
    assert payload["model"]


def test_client_secret_requires_app_token():
    client = TestClient(app)

    response = client.post(
        "/api/realtime/client-secret",
        json={
            "mode": "PUSH_TO_TALK",
            "source_language": "en-US",
            "target_language": "pt-BR",
            "translation_style": "NATURAL",
            "voice": "marin",
        },
    )

    assert response.status_code == 401


def test_text_translation_rejects_wrong_token():
    client = TestClient(app)

    response = client.post(
        "/api/translate/text",
        headers={"X-Duddy-App-Token": "not-the-token"},
        json={
            "text": "Hello",
            "mode": "PUSH_TO_TALK",
            "source_language": "en-US",
            "target_language": "pt-BR",
            "translation_style": "NATURAL",
        },
    )

    assert response.status_code == 401


@respx.mock
def test_text_translation_succeeds_with_valid_token():
    app.dependency_overrides[get_settings] = lambda: Settings(
        openai_api_key="sk-test",
        allowed_app_token="dev-local-token",
    )
    respx.post("https://api.openai.com/v1/responses").mock(
        return_value=httpx.Response(
            200,
            json={
                "output": [
                    {
                        "type": "message",
                        "content": [
                            {
                                "type": "output_text",
                                "text": '{"original_text":"Hello",'
                                '"literal_translation":"Ola",'
                                '"polished_translation":"Ola!"}',
                            },
                        ],
                    },
                ],
            },
        )
    )
    try:
        client = TestClient(app)
        response = client.post(
            "/api/translate/text",
            headers={"X-Duddy-App-Token": "dev-local-token"},
            json={
                "text": "Hello",
                "mode": "PUSH_TO_TALK",
                "source_language": "en-US",
                "target_language": "pt-BR",
                "translation_style": "NATURAL",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    payload = response.json()
    assert payload["polished_translation"] == "Ola!"
    assert payload["original_text"] == "Hello"


def test_text_translation_requires_app_token():
    client = TestClient(app)

    response = client.post(
        "/api/translate/text",
        json={
            "text": "Hello",
            "mode": "PUSH_TO_TALK",
            "source_language": "en-US",
            "target_language": "pt-BR",
            "translation_style": "NATURAL",
        },
    )

    assert response.status_code == 401

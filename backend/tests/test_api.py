from fastapi.testclient import TestClient

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

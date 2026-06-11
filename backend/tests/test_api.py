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
        json={"mode": "PUSH_TO_TALK"},
    )

    assert response.status_code == 401


def test_session_token_requires_app_token():
    client = TestClient(app)

    response = client.post("/api/auth/session-token")

    assert response.status_code == 401


def test_session_token_fails_closed_when_app_token_is_not_configured():
    app.dependency_overrides[get_settings] = lambda: Settings(
        allowed_app_token="",
        session_token_secret="session-secret",
    )
    try:
        client = TestClient(app)
        response = client.post("/api/auth/session-token")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503


def test_session_token_succeeds_with_valid_app_token():
    app.dependency_overrides[get_settings] = lambda: Settings(
        openai_api_key="sk-test",
        allowed_app_token="dev-local-token",
        token_ttl_seconds=60,
    )
    try:
        client = TestClient(app)
        response = client.post(
            "/api/auth/session-token",
            headers={"X-Duddy-App-Token": "dev-local-token"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    payload = response.json()
    assert payload["session_token"]
    assert payload["expires_at"] > 0


@respx.mock
def test_text_translation_succeeds_with_valid_token():
    app.dependency_overrides[get_settings] = lambda: Settings(
        openai_api_key="sk-test",
        allowed_app_token="dev-local-token",
        token_ttl_seconds=60,
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
        token_response = client.post(
            "/api/auth/session-token",
            headers={"X-Duddy-App-Token": "dev-local-token"},
        )
        token = token_response.json()["session_token"]
        response = client.post(
            "/api/translate/text",
            headers={"Authorization": f"Bearer {token}"},
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


def test_text_translation_rejects_bootstrap_app_token_directly():
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

    assert response.status_code == 401

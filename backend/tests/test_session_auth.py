from app.config import Settings
from app.session_auth import create_session_token, verify_session_token


def test_session_token_verifies_before_expiry():
    settings = Settings(
        allowed_app_token="bootstrap-secret",
        session_token_secret="session-secret",
        token_ttl_seconds=60,
    )

    token, expires_at = create_session_token(settings, now=1_000)

    assert expires_at == 1_060
    assert verify_session_token(token, settings, now=1_030)


def test_session_token_rejects_after_expiry():
    settings = Settings(
        allowed_app_token="bootstrap-secret",
        session_token_secret="session-secret",
        token_ttl_seconds=60,
    )

    token, _ = create_session_token(settings, now=1_000)

    assert not verify_session_token(token, settings, now=1_061)


def test_session_token_rejects_tampering():
    settings = Settings(
        allowed_app_token="bootstrap-secret",
        session_token_secret="session-secret",
        token_ttl_seconds=60,
    )

    token, _ = create_session_token(settings, now=1_000)

    assert not verify_session_token(f"{token}x", settings, now=1_030)

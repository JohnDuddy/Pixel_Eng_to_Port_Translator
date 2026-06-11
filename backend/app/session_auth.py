import base64
import hashlib
import hmac
import json
import time

from .config import Settings


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def _sign(payload: str, secret: str) -> str:
    return _b64url_encode(
        hmac.new(secret.encode("utf-8"), payload.encode("ascii"), hashlib.sha256).digest()
    )


def session_secret(settings: Settings) -> str:
    return settings.session_token_secret or settings.allowed_app_token


def create_session_token(settings: Settings, now: int | None = None) -> tuple[str, int]:
    issued_at = int(now if now is not None else time.time())
    expires_at = issued_at + settings.token_ttl_seconds
    payload = _b64url_encode(
        json.dumps(
            {
                "iat": issued_at,
                "exp": expires_at,
                "scope": "translate",
            },
            separators=(",", ":"),
        ).encode("utf-8")
    )
    return f"{payload}.{_sign(payload, session_secret(settings))}", expires_at


def verify_session_token(token: str, settings: Settings, now: int | None = None) -> bool:
    try:
        payload, signature = token.split(".", 1)
        expected = _sign(payload, session_secret(settings))
        if not hmac.compare_digest(signature, expected):
            return False

        data = json.loads(_b64url_decode(payload))
        expires_at = int(data.get("exp", 0))
        scope = data.get("scope")
        current_time = int(now if now is not None else time.time())
        return scope == "translate" and expires_at > current_time
    except Exception:
        return False

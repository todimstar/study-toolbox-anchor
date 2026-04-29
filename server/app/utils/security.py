"""Auth utilities: password hashing, JWT encoding/decoding, device PSK verification."""

from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt
from passlib.context import CryptContext

from app.config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


# ── Password hashing ──────────────────────────────────────────────

def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


# ── JWT (web panel user) ──────────────────────────────────────────

def create_access_token(user_id: int, username: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    payload = {"sub": str(user_id), "username": username, "exp": expire}
    return jwt.encode(payload, settings.SECRET_KEY, algorithm="HS256")


def decode_access_token(token: str) -> dict | None:
    try:
        return jwt.decode(token, settings.SECRET_KEY, algorithms=["HS256"])
    except JWTError:
        return None


# ── Device PSK verification ───────────────────────────────────────

def verify_device_psk(provided_psk: str) -> bool:
    """Simple shared-key check for device authentication."""
    return pwd_context.verify(provided_psk, hash_password(settings.DEVICE_PSK))


def get_device_psk_hash() -> str:
    """Return the hashed PSK for storage in the device record."""
    return hash_password(settings.DEVICE_PSK)

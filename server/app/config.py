"""Application configuration loaded from environment variables."""

import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


class Settings:
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite+aiosqlite:///./silent_installer.db")
    SECRET_KEY: str = os.getenv("SECRET_KEY", "change-me")
    UPLOAD_DIR: Path = BASE_DIR / os.getenv("UPLOAD_DIR", "uploads")
    DEVICE_PSK: str = os.getenv("DEVICE_PSK", "device-preshared-key")

    # JWT
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24  # 24 hours

    # Ensure upload directory exists
    def __init__(self) -> None:
        self.UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


settings = Settings()

"""Application configuration loaded from environment variables."""

import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


class Settings:
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite+aiosqlite:///./toolbox.db")
    PARENT_CHAT_KEY: str = os.getenv("PARENT_CHAT_KEY", "parent-chat-key-change-me")
    CHILD_CHAT_KEY: str = os.getenv("CHILD_CHAT_KEY", "child-chat-key-change-me")
    UPLOAD_DIR: str = os.getenv("UPLOAD_DIR", "./uploads")
    MAX_UPLOAD_BYTES: int = int(os.getenv("MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))


settings = Settings()

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
    # 孩子端 APK 首启验证码。空字符串 = 配对关闭（fail closed）。不要写进 APK / HTML。
    CHILD_PAIR_CODE: str = os.getenv("CHILD_PAIR_CODE", "")
    UPLOAD_DIR: str = os.getenv("UPLOAD_DIR", "./uploads")
    MAX_UPLOAD_BYTES: int = int(os.getenv("MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))
    # 图集单条消息总大小上限：须低于 nginx client_max_body_size(25m)，留出 multipart 边界开销
    MAX_GALLERY_BYTES: int = int(os.getenv("MAX_GALLERY_BYTES", str(24 * 1024 * 1024)))


settings = Settings()

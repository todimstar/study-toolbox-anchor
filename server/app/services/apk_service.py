"""APK file storage service."""

import hashlib
import uuid
from pathlib import Path

from fastapi import UploadFile

from app.config import settings


async def save_apk(file: UploadFile) -> tuple[str, str, int]:
    """Save an uploaded APK to disk.

    Returns (original_filename, stored_filename, file_size_bytes).
    """
    # Generate a unique stored name (UUID + original extension)
    extension = Path(file.filename or "app.apk").suffix or ".apk"
    stored_name = f"{uuid.uuid4().hex}{extension}"
    dest = settings.UPLOAD_DIR / stored_name

    size = 0
    sha256 = hashlib.sha256()
    with open(dest, "wb") as f:
        while chunk := await file.read(1024 * 1024):  # 1 MiB chunks
            f.write(chunk)
            sha256.update(chunk)
            size += len(chunk)

    return file.filename or "unknown.apk", stored_name, size


def get_apk_path(stored_filename: str) -> Path:
    return settings.UPLOAD_DIR / stored_filename


def delete_apk(stored_filename: str) -> None:
    path = get_apk_path(stored_filename)
    if path.exists():
        path.unlink()

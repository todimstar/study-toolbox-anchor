"""Sticker/emoji resources and custom sticker management for micro chat.

内置贴纸直接给前端返回公共 CDN 的 URL 清单（Fluent Emoji + Twemoji），
无需后端存储；自定义表情是孩子上传的本地图片，落盘 uploads/stickers/。
"""

import io
import json
import mimetypes
import secrets
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.sticker import Sticker

router = APIRouter(prefix="/api/stickers", tags=["stickers"])

# 内置贴纸：两套公开表情库，风格互补、授权清晰。
# Noto Emoji（Google，Apache 2.0，3D 写实 PNG）；Twemoji（Twitter，CC-BY 4.0，扁平 2D PNG）。
# 图源已自托管到 uploads/stickers/builtin/（jsdelivr 国内不稳，WebView 常空白），按码点拼路径。
NOTO_EMOJI = [
    "1f600", "1f603", "1f604", "1f601", "1f606", "1f605", "1f923", "1f602",
    "1f970", "1f60d", "1f60a", "1f60b", "1f60e", "1f60f", "1f618", "1f61c",
    "1f92a", "1f924", "1f92d", "1f973", "1f620", "1f621", "1f628", "1f622",
    "1f62d", "1f637", "1f912", "1f915", "1fae3", "1fae4", "1f624", "1f631",
    "1f633", "1f632", "1f92e", "1f92f", "1f383", "1f384", "1f31f", "1f389",
    "1f44d", "1f44e", "1f44f", "1f64c", "1f64f", "1f91d", "1f4aa", "1f4a4",
    "2764", "1f494", "1f495", "1f49c", "1f49b", "1f497", "1f9e1", "1f525",
]
# 分批去重（Batch 1-10）：Twemoji 部分码点与 Noto 完全同源，为避免 2D 经典 tab 出现空白格，逐一取 Noto 中确实存在的码点。
TWEMOJI_CODEPOINTS = [
    "1f600", "1f602", "1f60d", "1f60e", "1f622", "1f62d", "1f628", "1f621",
    "1f44d", "1f44f", "1f64c", "1f4aa", "1f525", "1f31f", "1f383", "1f384",
    "1f49c", "1f497", "2764", "1f91d", "1f64f", "1f4a4", "1f389",
]

NOTO_URL = "/api/uploads/stickers/builtin/noto/{cp}.png"
TWEMOJI_URL = "/api/uploads/stickers/builtin/twemoji/{cp}.png"


class BuiltinSticker(BaseModel):
    url: str
    alt: str


class BuiltinStickersResponse(BaseModel):
    noto: list[BuiltinSticker]
    twemoji: list[BuiltinSticker]


class StickerOut(BaseModel):
    id: int
    url: str
    thumbnail_url: str | None
    name: str
    created_at: str


class StickerListResponse(BaseModel):
    items: list[StickerOut]


def _require_sticker_key(role: str, x_chat_key: str | None) -> None:
    expected = settings.CHILD_CHAT_KEY if role == "child" else settings.PARENT_CHAT_KEY
    if not expected or x_chat_key != expected:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid chat key")


def _safe_name(suffix: str) -> str:
    if len(suffix) > 12:
        suffix = ""
    return f"{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}_{secrets.token_hex(8)}{suffix}"


def _thumb_name(original_name: str) -> str:
    stem, _, _suffix = original_name.rpartition(".")
    return f"{stem}.thumb.jpg"


def _make_thumbnail(data: bytes) -> bytes | None:
    try:
        from PIL import Image, ImageOps

        image = Image.open(io.BytesIO(data))
        image = ImageOps.exif_transpose(image)
        width, height = image.size
        longest = max(width, height)
        if longest > 240:
            scale = 240 / longest
            image = image.resize(
                (max(1, int(width * scale)), max(1, int(height * scale))),
                Image.Resampling.LANCZOS,
            )
        if image.mode not in ("RGB", "L"):
            image = image.convert("RGB")
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=80, optimize=True)
        return buffer.getvalue()
    except Exception:
        return None


def _looks_like_image(data: bytes) -> bool:
    if len(data) < 12:
        return False
    if data.startswith(b"\x89PNG\r\n\x1a\n") or data.startswith(b"\xff\xd8\xff") or data.startswith(b"GIF8"):
        return True
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return True
    return data[4:8] == b"ftyp"


def _sticker_out(sticker: Sticker) -> StickerOut:
    return StickerOut(
        id=sticker.id,
        url=sticker.url,
        thumbnail_url=sticker.thumbnail_url,
        name=sticker.name,
        created_at=_iso(sticker.created_at),
    )


def _iso(value: datetime | None) -> str:
    if not isinstance(value, datetime):
        return ""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


@router.get("/builtin", response_model=BuiltinStickersResponse)
async def builtin_stickers():
    noto = [
        BuiltinSticker(url=NOTO_URL.format(cp=cp), alt=chr(int(cp, 16)))
        for cp in NOTO_EMOJI
    ]
    twemoji = [
        BuiltinSticker(url=TWEMOJI_URL.format(cp=cp), alt=chr(int(cp, 16)))
        for cp in TWEMOJI_CODEPOINTS
    ]
    return BuiltinStickersResponse(noto=noto, twemoji=twemoji)


@router.post("", response_model=list[StickerOut], status_code=status.HTTP_201_CREATED)
async def upload_stickers(
    sender_role: str = Form(...),
    x_chat_key: str | None = Header(default=None),
    uploads: list[UploadFile] = File(...),
    db: AsyncSession = Depends(get_db),
):
    """孩子/家长上传本机图片，转成自定义表情，一次最多 9 张。"""
    _require_sticker_key(sender_role, x_chat_key)
    if not 1 <= len(uploads) <= 9:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="请选择 1-9 张图片")

    upload_dir = Path(settings.UPLOAD_DIR) / "stickers"
    upload_dir.mkdir(parents=True, exist_ok=True)

    created = []
    for upload in uploads:
        data = await upload.read()
        if not data:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty file")
        if len(data) > settings.MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="单张图片过大")
        original_name = Path(upload.filename or "image").name
        content_type = upload.content_type or mimetypes.guess_type(original_name)[0] or ""
        if not content_type.startswith("image/"):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="仅支持图片文件")
        if not _looks_like_image(data):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="文件内容不是有效图片")

        suffix = Path(original_name).suffix.lower()
        stored_name = _safe_name(suffix)
        (upload_dir / stored_name).write_bytes(data)
        thumbnail = _make_thumbnail(data)
        thumb_stored = ""
        if thumbnail:
            thumb_stored = _thumb_name(stored_name)
            (upload_dir / thumb_stored).write_bytes(thumbnail)

        sticker = Sticker(
            owner_role=sender_role,
            url=f"/api/uploads/stickers/{stored_name}",
            thumbnail_url=(f"/api/uploads/stickers/{thumb_stored}" if thumb_stored else None),
            name="",
        )
        db.add(sticker)
        await db.flush()
        created.append(_sticker_out(sticker))

    await db.commit()
    return created


@router.get("", response_model=StickerListResponse)
async def list_stickers(
    owner_role: str,
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_sticker_key(owner_role, x_chat_key)
    result = await db.execute(
        select(Sticker).where(Sticker.owner_role == owner_role).order_by(Sticker.id.desc()).limit(200)
    )
    return StickerListResponse(items=[_sticker_out(s) for s in result.scalars().all()])


@router.delete("/{sticker_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_sticker(
    sticker_id: int,
    owner_role: str,
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_sticker_key(owner_role, x_chat_key)
    result = await db.execute(
        select(Sticker).where(Sticker.id == sticker_id, Sticker.owner_role == owner_role)
    )
    sticker = result.scalar_one_or_none()
    if sticker is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Sticker not found")
    # 仅删数据库记录，文件保留（历史消息可能仍引用）
    await db.delete(sticker)
    await db.commit()
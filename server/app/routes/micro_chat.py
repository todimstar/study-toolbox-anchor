"""Micro chat message routes for the study toolbox."""

import io
import json
import mimetypes
import secrets
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from time import monotonic
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Query, Request, UploadFile, status
from pydantic import BaseModel, Field
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.micro_chat import MicroChatMessage
from app.jpush_util import send_jpush_notification

router = APIRouter(prefix="/api/micro-chat", tags=["micro-chat"])

SenderRole = Literal["parent", "child"]
MessageType = Literal["text", "image", "audio", "file", "gallery", "sticker"]


class CreateMessageRequest(BaseModel):
    sender_role: SenderRole
    sender_name: str = Field(min_length=1, max_length=32)
    sender_avatar: str = Field(default="", max_length=64)
    body: str = Field(default="", max_length=2000)
    message_type: MessageType = "text"
    attachment_url: str | None = None
    attachment_name: str | None = None
    attachment_mime: str | None = None
    attachment_size: int | None = None
    reply_to_id: int | None = None


class MessageOut(BaseModel):
    id: int
    sender_role: str
    sender_name: str
    sender_avatar: str
    body: str
    message_type: str
    attachment_url: str | None
    attachment_urls: list[str] | None = None  # gallery 图集 URL 列表
    attachment_name: str | None
    attachment_mime: str | None
    attachment_size: int | None
    read_by_parent: int = 0
    read_by_child: int = 0
    recalled: bool = False
    reply_to_id: int | None = None
    quote_sender: str | None = None
    quote_body: str | None = None
    quote_type: str | None = None
    created_at: str


class MessageListResponse(BaseModel):
    items: list[MessageOut]


def _key_matches(given: str | None, expected: str) -> bool:
    if not given or not expected or len(given) != len(expected):
        return False
    return secrets.compare_digest(given, expected)


def _require_chat_key(sender_role: str, x_chat_key: str | None) -> None:
    expected = settings.CHILD_CHAT_KEY if sender_role == "child" else settings.PARENT_CHAT_KEY
    if not _key_matches(x_chat_key, expected):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid chat key")


def _require_any_chat_key(x_chat_key: str | None) -> str:
    """读接口：家长或孩子口令均可。返回命中的角色。"""
    if _key_matches(x_chat_key, settings.PARENT_CHAT_KEY):
        return "parent"
    if _key_matches(x_chat_key, settings.CHILD_CHAT_KEY):
        return "child"
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid chat key")


class PairRequest(BaseModel):
    code: str = Field(min_length=1, max_length=64)


class PairResponse(BaseModel):
    chat_key: str


_pair_attempts: dict[str, list[float]] = defaultdict(list)
_PAIR_WINDOW_SECONDS = 600.0
_PAIR_MAX_ATTEMPTS = 8


def _pair_client_ip(request: Request) -> str:
    forwarded = (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
    return forwarded or (request.client.host if request.client else "unknown")


def _pair_rate_limited(ip: str) -> bool:
    now = monotonic()
    window = [stamp for stamp in _pair_attempts[ip] if now - stamp < _PAIR_WINDOW_SECONDS]
    _pair_attempts[ip] = window
    if len(window) >= _PAIR_MAX_ATTEMPTS:
        return True
    window.append(now)
    return False


@router.post("/pair", response_model=PairResponse)
async def pair_child_device(body: PairRequest, request: Request) -> PairResponse:
    """孩子端 APK 首启：用安装码换取 CHILD_CHAT_KEY。这是唯一不带口令的孩子写接口。"""
    if _pair_rate_limited(_pair_client_ip(request)):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many attempts")
    if not _key_matches(body.code.strip(), settings.CHILD_PAIR_CODE):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid pair code")
    return PairResponse(chat_key=settings.CHILD_CHAT_KEY)


def _created_at_iso(created_at: datetime | None) -> str:
    if not isinstance(created_at, datetime):
        return ""
    if created_at.tzinfo is None:
        created_at = created_at.replace(tzinfo=timezone.utc)
    return created_at.astimezone(timezone.utc).isoformat()


def _safe_upload_name(filename: str) -> str:
    suffix = Path(filename).suffix.lower()
    if len(suffix) > 12:
        suffix = ""
    return f"{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}_{secrets.token_hex(8)}{suffix}"


def _message_type_for_mime(content_type: str | None) -> str:
    if (content_type or "").startswith("image/"):
        return "image"
    if (content_type or "").startswith("audio/"):
        return "audio"
    return "file"


def _looks_like_image(data: bytes) -> bool:
    """按 magic bytes 校验真实图片内容，防止伪装成 image/* 的非图片文件混入图集。"""
    if len(data) < 12:
        return False
    if data.startswith(b"\x89PNG\r\n\x1a\n") or data.startswith(b"\xff\xd8\xff") or data.startswith(b"GIF8"):
        return True
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return True
    return data[4:8] == b"ftyp"  # HEIC / HEIF / AVIF


THUMB_MAX_EDGE = 480
THUMB_JPEG_QUALITY = 82


def _make_thumbnail(data: bytes) -> bytes | None:
    """生成 JPEG 缩略图（长边 ≤THUMB_MAX_EDGE）。失败返回 None，调用方回退用原图。"""
    try:
        from PIL import Image, ImageOps

        image = Image.open(io.BytesIO(data))
        image = ImageOps.exif_transpose(image)
        width, height = image.size
        longest = max(width, height)
        if longest <= THUMB_MAX_EDGE:
            # 原图已经很小，仍转成 JPEG 统一走缩略图路径（避免 GIF/PNG 大色域）
            pass
        else:
            scale = THUMB_MAX_EDGE / longest
            image = image.resize(
                (max(1, int(width * scale)), max(1, int(height * scale))),
                Image.Resampling.LANCZOS,
            )
        if image.mode not in ("RGB", "L"):
            image = image.convert("RGB")
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=THUMB_JPEG_QUALITY, optimize=True)
        return buffer.getvalue()
    except Exception:
        return None


def _thumb_name(original_name: str) -> str:
    """原文件名 → 缩略图文件名：'xx.jpg' → 'xx.thumb.jpg'（前端据此拼缩略图加载路径）。"""
    stem, _, suffix = original_name.rpartition(".")
    return f"{stem}.thumb.jpg" if suffix else f"{original_name}.thumb.jpg"


def _message_out(message: MicroChatMessage) -> MessageOut:
    return MessageOut(
        id=message.id,
        sender_role=message.sender_role,
        sender_name=message.sender_name,
        sender_avatar=message.sender_avatar or "",
        body=message.body,
        message_type=message.message_type or "text",
        attachment_url=message.attachment_url,
        attachment_urls=_decode_urls(message.attachment_urls),
        attachment_name=message.attachment_name,
        attachment_mime=message.attachment_mime,
        attachment_size=message.attachment_size,
        read_by_parent=message.read_by_parent or 0,
        read_by_child=message.read_by_child or 0,
        recalled=bool(message.recalled),
        reply_to_id=message.reply_to_id,
        quote_sender=message.quote_sender,
        quote_body=message.quote_body,
        quote_type=message.quote_type,
        created_at=_created_at_iso(message.created_at),
    )


def _quote_summary(message: MicroChatMessage) -> tuple[str, str, str]:
    sender = (message.sender_name or "")[:32]
    mtype = message.message_type or "text"
    if message.recalled:
        body = "[已撤回]"
    elif mtype == "image":
        body = "[图片]"
    elif mtype == "gallery":
        body = "[图集]"
    elif mtype == "audio":
        body = "[语音]"
    elif mtype == "sticker":
        body = "[表情]"
    elif mtype == "file":
        body = (message.attachment_name or "")[:40] or "[文件]"
    else:
        body = (message.body or "").strip()[:80] or "[消息]"
    return sender, body, mtype


async def _snapshot_quote(
    db: AsyncSession, reply_to_id: int | None
) -> tuple[int | None, str | None, str | None, str | None]:
    if not reply_to_id:
        return None, None, None, None
    result = await db.execute(select(MicroChatMessage).where(MicroChatMessage.id == reply_to_id))
    original = result.scalar_one_or_none()
    if original is None:
        return None, None, None, None
    sender, body, mtype = _quote_summary(original)
    return original.id, sender, body, mtype


def _decode_urls(raw: str | None) -> list[str] | None:
    if not raw:
        return None
    try:
        urls = json.loads(raw)
    except (TypeError, ValueError):
        return None
    if isinstance(urls, list) and urls and all(isinstance(url, str) for url in urls):
        return urls
    return None


def _build_message(
    body: CreateMessageRequest,
    reply_to_id: int | None = None,
    quote_sender: str | None = None,
    quote_body: str | None = None,
    quote_type: str | None = None,
) -> MicroChatMessage:
    return MicroChatMessage(
        sender_role=body.sender_role,
        sender_name=body.sender_name.strip(),
        sender_avatar=body.sender_avatar.strip(),
        body=body.body.strip(),
        message_type=body.message_type,
        attachment_url=body.attachment_url,
        attachment_name=body.attachment_name,
        attachment_mime=body.attachment_mime,
        attachment_size=body.attachment_size,
        reply_to_id=reply_to_id,
        quote_sender=quote_sender,
        quote_body=quote_body,
        quote_type=quote_type,
        created_at=datetime.now(timezone.utc),
    )


@router.get("/messages", response_model=MessageListResponse)
async def list_messages(
    since_id: int = Query(0, ge=0),
    before_id: int = Query(0, ge=0),
    limit: int = Query(80, ge=1, le=200),
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_any_chat_key(x_chat_key)
    if before_id:
        query = (
            select(MicroChatMessage)
            .where(MicroChatMessage.id < before_id)
            .order_by(desc(MicroChatMessage.id))
            .limit(limit)
        )
    elif since_id:
        query = (
            select(MicroChatMessage)
            .where(MicroChatMessage.id > since_id)
            .order_by(MicroChatMessage.id.asc())
            .limit(limit)
        )
    else:
        query = select(MicroChatMessage).order_by(desc(MicroChatMessage.id)).limit(limit)
    result = await db.execute(query)
    messages = result.scalars().all()
    if before_id or not since_id:
        messages = sorted(messages, key=lambda message: message.id)
    return MessageListResponse(items=[_message_out(message) for message in messages])


@router.post("/read", status_code=status.HTTP_204_NO_CONTENT)
async def mark_read(
    up_to_id: int = Query(..., ge=1),
    reader_role: SenderRole = Query(...),
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    """把 ≤up_to_id 的、由对方发的消息标记为「已读」。
    reader_role=parent 时置 read_by_parent=1（孩子发的消息被家长读）；
    reader_role=child 时置 read_by_child=1（家长发的消息被孩子读）。"""
    _require_chat_key(reader_role, x_chat_key)
    result = await db.execute(
        select(MicroChatMessage).where(MicroChatMessage.id <= up_to_id)
    )
    messages = result.scalars().all()
    column = (
        MicroChatMessage.read_by_parent if reader_role == "parent" else MicroChatMessage.read_by_child
    )
    changed = False
    for message in messages:
        # 只标记对方发的消息（自己发的消息不需要"自己已读"回执）
        if message.sender_role == reader_role:
            continue
        if not getattr(message, column.key):
            setattr(message, column.key, 1)
            changed = True
    if changed:
        await db.commit()


RECALL_WINDOW_SECONDS = 2 * 60  # 发出 2 分钟内可撤回（微信同款规则）


@router.post("/{message_id}/recall", status_code=status.HTTP_204_NO_CONTENT)
async def recall_message(
    message_id: int,
    sender_role: SenderRole = Query(...),
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    """发件人撤回自己的消息：2 分钟内、本人所发，标记 recalled=1（内容保留在库不外泄）。"""
    _require_chat_key(sender_role, x_chat_key)
    result = await db.execute(select(MicroChatMessage).where(MicroChatMessage.id == message_id))
    message = result.scalar_one_or_none()
    if message is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Message not found")
    if message.sender_role != sender_role:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="只能撤回自己发的消息")
    if message.recalled:
        return  # 幂等：重复撤回不报错
    created_at = message.created_at
    if isinstance(created_at, datetime) and created_at.tzinfo is None:
        created_at = created_at.replace(tzinfo=timezone.utc)
    if created_at and (datetime.now(timezone.utc) - created_at).total_seconds() > RECALL_WINDOW_SECONDS:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="超过 2 分钟，不能撤回了")
    message.recalled = 1
    message.recalled_at = datetime.now(timezone.utc)
    await db.commit()


@router.post("/messages", response_model=MessageOut, status_code=status.HTTP_201_CREATED)
async def create_message(
    body: CreateMessageRequest,
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_chat_key(body.sender_role, x_chat_key)
    if body.message_type == "text" and not body.body.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Text body is required")
    if body.message_type != "text" and not body.attachment_url:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Attachment is required")
    reply_to_id, quote_sender, quote_body, quote_type = await _snapshot_quote(db, body.reply_to_id)
    message = _build_message(
        body,
        reply_to_id=reply_to_id,
        quote_sender=quote_sender,
        quote_body=quote_body,
        quote_type=quote_type,
    )
    db.add(message)
    await db.commit()
    await db.refresh(message)

    # 发送推送（异步，不阻塞响应）
    # 家长发消息 → 极光推给孩子端；孩子发消息 → 企微机器人提醒家长微信
    if body.sender_role == "parent":
        title = "微聊有新消息"
        content = f"{body.sender_name}：{body.body[:80]}"
        # 不 await，让推送在后台执行（火忘模式）
        import asyncio
        asyncio.create_task(send_jpush_notification(title, content, body.sender_role))
    elif body.sender_role == "child":
        import asyncio
        from app.wecom_bot import notify_parent_new_child_message
        summary = body.body.strip()[:80] or "[空消息]"
        asyncio.create_task(notify_parent_new_child_message(body.sender_name.strip(), summary))

    return _message_out(message)


@router.post("/messages/with-file", response_model=MessageOut, status_code=status.HTTP_201_CREATED)
async def create_message_with_file(
    sender_role: SenderRole = Form(...),
    sender_name: str = Form(..., min_length=1, max_length=32),
    sender_avatar: str = Form(default="", max_length=64),
    body: str = Form(default=""),
    reply_to_id: int | None = Form(default=None),
    upload: UploadFile = File(...),
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_chat_key(sender_role, x_chat_key)
    upload_dir = Path(settings.UPLOAD_DIR) / "micro-chat"
    upload_dir.mkdir(parents=True, exist_ok=True)

    data = await upload.read()
    if not data:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty file")
    if len(data) > settings.MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="File too large")

    original_name = Path(upload.filename or "file").name
    stored_name = _safe_upload_name(original_name)
    target = upload_dir / stored_name
    target.write_bytes(data)

    content_type = upload.content_type or mimetypes.guess_type(original_name)[0] or "application/octet-stream"
    # 图片消息同样落一份缩略图，加速孩子端加载
    if content_type.startswith("image/"):
        thumbnail = _make_thumbnail(data)
        if thumbnail:
            (upload_dir / _thumb_name(stored_name)).write_bytes(thumbnail)
    message_type = _message_type_for_mime(content_type)
    quote_id, quote_sender, quote_body, quote_type = await _snapshot_quote(db, reply_to_id)
    message = MicroChatMessage(
        sender_role=sender_role,
        sender_name=sender_name.strip(),
        sender_avatar=sender_avatar.strip(),
        body=body.strip(),
        message_type=message_type,
        attachment_url=f"/api/uploads/micro-chat/{stored_name}",
        attachment_name=original_name,
        attachment_mime=content_type,
        attachment_size=len(data),
        reply_to_id=quote_id,
        quote_sender=quote_sender,
        quote_body=quote_body,
        quote_type=quote_type,
        created_at=datetime.now(timezone.utc),
    )
    db.add(message)
    await db.commit()
    await db.refresh(message)

    # 孩子发文件/图片 → 提醒家长微信
    if sender_role == "child":
        import asyncio
        from app.wecom_bot import notify_parent_new_child_message
        kind = "图片" if message_type == "image" else ("语音" if message_type == "audio" else "文件")
        summary = body.strip()[:40] or f"[{kind}]"
        asyncio.create_task(notify_parent_new_child_message(sender_name.strip(), summary))

    return _message_out(message)


@router.post("/messages/with-images", response_model=MessageOut, status_code=status.HTTP_201_CREATED)
async def create_message_with_images(
    sender_role: SenderRole = Form(...),
    sender_name: str = Form(..., min_length=1, max_length=32),
    sender_avatar: str = Form(default="", max_length=64),
    body: str = Form(default="", max_length=2000),
    reply_to_id: int | None = Form(default=None),
    uploads: list[UploadFile] = File(...),
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    """一次上传 1-9 张图片，作为单条 gallery 图集消息（微信式相册）。"""
    _require_chat_key(sender_role, x_chat_key)
    if not 1 <= len(uploads) <= 9:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="请选择 1-9 张图片")

    upload_dir = Path(settings.UPLOAD_DIR) / "micro-chat"
    upload_dir.mkdir(parents=True, exist_ok=True)

    urls: list[str] = []
    total_bytes = 0
    for upload in uploads:
        data = await upload.read()
        if not data:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty file")
        if len(data) > settings.MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="单张图片过大")
        original_name = Path(upload.filename or "image").name
        content_type = upload.content_type or mimetypes.guess_type(original_name)[0] or ""
        if not content_type.startswith("image/"):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="仅支持发送图片文件")
        if not _looks_like_image(data):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="文件内容不是有效图片")
        total_bytes += len(data)
        if total_bytes > settings.MAX_GALLERY_BYTES:
            raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="图集总大小超过限制")
        stored_name = _safe_upload_name(original_name)
        (upload_dir / stored_name).write_bytes(data)
        # 额外落一份 JPEG 缩略图，孩子端缩略图/宫格秒加载，点开全屏仍取原图
        thumbnail = _make_thumbnail(data)
        if thumbnail:
            (upload_dir / _thumb_name(stored_name)).write_bytes(thumbnail)
        urls.append(f"/api/uploads/micro-chat/{stored_name}")

    quote_id, quote_sender, quote_body, quote_type = await _snapshot_quote(db, reply_to_id)
    message = MicroChatMessage(
        sender_role=sender_role,
        sender_name=sender_name.strip(),
        sender_avatar=sender_avatar.strip(),
        body=body.strip(),
        message_type="gallery",
        attachment_urls=json.dumps(urls),
        reply_to_id=quote_id,
        quote_sender=quote_sender,
        quote_body=quote_body,
        quote_type=quote_type,
        created_at=datetime.now(timezone.utc),
    )
    db.add(message)
    await db.commit()
    await db.refresh(message)

    # 家长发图集 → 极光推给孩子端；孩子发图集 → 企微机器人提醒家长微信（火忘模式）
    if sender_role == "parent":
        import asyncio

        summary = body.strip()[:80] or f"[图集] {len(urls)} 张图片"
        asyncio.create_task(
            send_jpush_notification("微聊有新消息", f"{sender_name.strip()}：{summary}", sender_role)
        )
    elif sender_role == "child":
        import asyncio
        from app.wecom_bot import notify_parent_new_child_message

        summary = body.strip()[:40] or f"[图集] {len(urls)} 张图片"
        asyncio.create_task(notify_parent_new_child_message(sender_name.strip(), summary))

    return _message_out(message)

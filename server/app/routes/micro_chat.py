"""Micro chat message routes for the study toolbox."""

import mimetypes
import secrets
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Query, UploadFile, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.micro_chat import MicroChatMessage

router = APIRouter(prefix="/api/micro-chat", tags=["micro-chat"])

SenderRole = Literal["parent", "child"]
MessageType = Literal["text", "image", "file"]


class CreateMessageRequest(BaseModel):
    sender_role: SenderRole
    sender_name: str = Field(min_length=1, max_length=32)
    sender_avatar: str = Field(default="", max_length=64)
    body: str = Field(min_length=1, max_length=2000)
    message_type: MessageType = "text"
    attachment_url: str | None = None
    attachment_name: str | None = None
    attachment_mime: str | None = None
    attachment_size: int | None = None


class MessageOut(BaseModel):
    id: int
    sender_role: str
    sender_name: str
    sender_avatar: str
    body: str
    message_type: str
    attachment_url: str | None
    attachment_name: str | None
    attachment_mime: str | None
    attachment_size: int | None
    created_at: str


class MessageListResponse(BaseModel):
    items: list[MessageOut]


def _require_chat_key(sender_role: str, x_chat_key: str | None) -> None:
    expected = settings.CHILD_CHAT_KEY if sender_role == "child" else settings.PARENT_CHAT_KEY
    if not expected or x_chat_key != expected:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid chat key")


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
    return "image" if (content_type or "").startswith("image/") else "file"


def _message_out(message: MicroChatMessage) -> MessageOut:
    return MessageOut(
        id=message.id,
        sender_role=message.sender_role,
        sender_name=message.sender_name,
        sender_avatar=message.sender_avatar or "",
        body=message.body,
        message_type=message.message_type or "text",
        attachment_url=message.attachment_url,
        attachment_name=message.attachment_name,
        attachment_mime=message.attachment_mime,
        attachment_size=message.attachment_size,
        created_at=_created_at_iso(message.created_at),
    )


def _build_message(body: CreateMessageRequest) -> MicroChatMessage:
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
        created_at=datetime.now(timezone.utc),
    )


@router.get("/messages", response_model=MessageListResponse)
async def list_messages(
    since_id: int = Query(0, ge=0),
    limit: int = Query(80, ge=1, le=200),
    db: AsyncSession = Depends(get_db),
):
    query = (
        select(MicroChatMessage)
        .where(MicroChatMessage.id > since_id)
        .order_by(MicroChatMessage.id.asc())
        .limit(limit)
    )
    result = await db.execute(query)
    return MessageListResponse(items=[_message_out(message) for message in result.scalars().all()])


@router.post("/messages", response_model=MessageOut, status_code=status.HTTP_201_CREATED)
async def create_message(
    body: CreateMessageRequest,
    x_chat_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_chat_key(body.sender_role, x_chat_key)
    if body.message_type != "text" and not body.attachment_url:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Attachment is required")
    message = _build_message(body)
    db.add(message)
    await db.commit()
    await db.refresh(message)
    return _message_out(message)


@router.post("/messages/with-file", response_model=MessageOut, status_code=status.HTTP_201_CREATED)
async def create_message_with_file(
    sender_role: SenderRole = Form(...),
    sender_name: str = Form(..., min_length=1, max_length=32),
    sender_avatar: str = Form(default="", max_length=64),
    body: str = Form(default=""),
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
    message_type = _message_type_for_mime(content_type)
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
        created_at=datetime.now(timezone.utc),
    )
    db.add(message)
    await db.commit()
    await db.refresh(message)
    return _message_out(message)

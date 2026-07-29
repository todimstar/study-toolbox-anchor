"""Remote HTML delivery routes."""

from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.remote_html import RemoteHtmlDelivery

router = APIRouter(prefix="/api/remote-html", tags=["remote-html"])

ClientRole = Literal["parent", "child"]


class CreateRemoteHtmlRequest(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=1000)
    html: str = Field(min_length=1, max_length=300000)
    created_by_name: str = Field(default="高人", min_length=1, max_length=32)


class RemoteHtmlOut(BaseModel):
    id: int
    title: str
    description: str
    html: str
    created_by_name: str
    created_at: str


class RemoteHtmlListResponse(BaseModel):
    items: list[RemoteHtmlOut]


def _require_key(role: str, key: str | None) -> None:
    expected = settings.CHILD_CHAT_KEY if role == "child" else settings.PARENT_CHAT_KEY
    if not expected or key != expected:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid key")


def _iso(value: datetime | None) -> str:
    if not isinstance(value, datetime):
        return ""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


def _out(item: RemoteHtmlDelivery) -> RemoteHtmlOut:
    return RemoteHtmlOut(
        id=item.id,
        title=item.title,
        description=item.description,
        html=item.html,
        created_by_name=item.created_by_name,
        created_at=_iso(item.created_at),
    )


@router.get("", response_model=RemoteHtmlListResponse)
async def list_remote_html(
    role: ClientRole,
    limit: int = Query(50, ge=1, le=100),
    x_remote_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_key(role, x_remote_key)
    result = await db.execute(select(RemoteHtmlDelivery).order_by(desc(RemoteHtmlDelivery.id)).limit(limit))
    return RemoteHtmlListResponse(items=[_out(item) for item in result.scalars().all()])


@router.post("", response_model=RemoteHtmlOut, status_code=status.HTTP_201_CREATED)
async def create_remote_html(
    body: CreateRemoteHtmlRequest,
    x_remote_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_key("parent", x_remote_key)
    item = RemoteHtmlDelivery(
        title=body.title.strip(),
        description=body.description.strip(),
        html=body.html,
        created_by_name=body.created_by_name.strip(),
    )
    db.add(item)
    await db.commit()
    await db.refresh(item)
    return _out(item)


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_remote_html(
    item_id: int,
    x_remote_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    """家长撤销投递。收件箱里直接消失；孩子已保存到本地的副本不受影响。"""
    _require_key("parent", x_remote_key)
    item = await db.get(RemoteHtmlDelivery, item_id)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Delivery not found")
    await db.delete(item)
    await db.commit()

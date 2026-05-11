"""Parent-child task delivery routes."""

from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.models.study_task import StudyTask

router = APIRouter(prefix="/api/tasks", tags=["study-tasks"])

ClientRole = Literal["parent", "child"]
TaskStatus = Literal["pending", "accepted", "returned", "done"]


class CreateTaskRequest(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=2000)
    granularity: str = Field(default="normal", max_length=16)
    created_by_name: str = Field(default="家长", min_length=1, max_length=32)


class UpdateTaskStatusRequest(BaseModel):
    status: TaskStatus
    child_reply: str = Field(default="", max_length=1000)


class TaskOut(BaseModel):
    id: int
    title: str
    description: str
    granularity: str
    status: str
    created_by_name: str
    child_reply: str
    created_at: str
    updated_at: str


class TaskListResponse(BaseModel):
    items: list[TaskOut]


def _require_role_key(role: str, key: str | None) -> None:
    expected = settings.CHILD_CHAT_KEY if role == "child" else settings.PARENT_CHAT_KEY
    if not expected or key != expected:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid key")


def _iso(value: datetime | None) -> str:
    if not isinstance(value, datetime):
        return ""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


def _out(task: StudyTask) -> TaskOut:
    return TaskOut(
        id=task.id,
        title=task.title,
        description=task.description,
        granularity=task.granularity,
        status=task.status,
        created_by_name=task.created_by_name,
        child_reply=task.child_reply,
        created_at=_iso(task.created_at),
        updated_at=_iso(task.updated_at),
    )


@router.get("", response_model=TaskListResponse)
async def list_tasks(
    role: ClientRole,
    x_task_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_role_key(role, x_task_key)
    result = await db.execute(select(StudyTask).order_by(StudyTask.id.desc()).limit(100))
    return TaskListResponse(items=[_out(task) for task in result.scalars().all()])


@router.post("", response_model=TaskOut, status_code=status.HTTP_201_CREATED)
async def create_task(
    body: CreateTaskRequest,
    x_task_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_role_key("parent", x_task_key)
    now = datetime.now(timezone.utc)
    task = StudyTask(
        title=body.title.strip(),
        description=body.description.strip(),
        granularity=body.granularity,
        status="pending",
        created_by_name=body.created_by_name.strip(),
        child_reply="",
        created_at=now,
        updated_at=now,
    )
    db.add(task)
    await db.commit()
    await db.refresh(task)
    return _out(task)


@router.patch("/{task_id}", response_model=TaskOut)
async def update_task(
    task_id: int,
    body: UpdateTaskStatusRequest,
    role: ClientRole,
    x_task_key: str | None = Header(default=None),
    db: AsyncSession = Depends(get_db),
):
    _require_role_key(role, x_task_key)
    result = await db.execute(select(StudyTask).where(StudyTask.id == task_id))
    task = result.scalar_one_or_none()
    if task is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found")

    if role == "child" and body.status not in {"accepted", "returned", "done"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid child status")
    if role == "parent" and body.status not in {"pending", "done"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid parent status")

    task.status = body.status
    if body.status != "pending":
        task.child_reply = body.child_reply.strip()
    task.updated_at = datetime.now(timezone.utc)
    await db.commit()
    await db.refresh(task)
    return _out(task)

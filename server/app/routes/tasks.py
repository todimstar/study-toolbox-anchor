"""Installation task management routes."""

import logging
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.apk import APKFile
from app.models.device import Device
from app.models.task import InstallTask
from app.models.user import User
from app.routes.auth import get_current_user
from app.services.ws_manager import ws_manager

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/tasks", tags=["tasks"])


# ═══════════════════════════════════════════════════════════════════
#  Schemas
# ═══════════════════════════════════════════════════════════════════

class CreateTaskRequest(BaseModel):
    apk_id: int
    device_id: int


class TaskStatusUpdateRequest(BaseModel):
    status: str
    error_message: str = ""


class DeviceStatusUpdateRequest(BaseModel):
    device_id: str
    status: str
    error_message: str = ""


class TaskOut(BaseModel):
    id: int
    apk_id: int
    apk_name: str = ""
    device_id: int
    device_name: str = ""
    status: str
    retry_count: int
    error_message: str
    created_at: str
    updated_at: str

    class Config:
        from_attributes = True


class TaskListResponse(BaseModel):
    total: int
    items: list[TaskOut]


# ═══════════════════════════════════════════════════════════════════
#  Helpers
# ═══════════════════════════════════════════════════════════════════

async def _build_task_out(task: InstallTask, db: AsyncSession) -> TaskOut:
    """Build a TaskOut with resolved names."""
    apk = await db.get(APKFile, task.apk_id)
    device = await db.get(Device, task.device_id)
    return TaskOut(
        id=task.id,
        apk_id=task.apk_id,
        apk_name=apk.original_filename if apk else "",
        device_id=task.device_id,
        device_name=device.device_name if device else "",
        status=task.status,
        retry_count=task.retry_count,
        error_message=task.error_message or "",
        created_at=task.created_at.isoformat() if task.created_at else "",
        updated_at=task.updated_at.isoformat() if task.updated_at else "",
    )


# ═══════════════════════════════════════════════════════════════════
#  Routes
# ═══════════════════════════════════════════════════════════════════

@router.post("", response_model=TaskOut, status_code=status.HTTP_201_CREATED)
async def create_task(
    body: CreateTaskRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Create an installation task: assign an APK to a device."""
    apk = await db.get(APKFile, body.apk_id)
    if not apk:
        raise HTTPException(status_code=404, detail="APK not found")

    device = await db.get(Device, body.device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    task = InstallTask(
        apk_id=body.apk_id,
        device_id=body.device_id,
        status="pending",
    )
    db.add(task)
    await db.commit()
    await db.refresh(task)

    # Push task to device via WebSocket if online
    sent = await ws_manager.send_task(
        device.device_id,
        {
            "type": "new_task",
            "task_id": task.id,
            "apk_id": apk.id,
            "apk_download_url": f"/api/apps/download/{apk.id}",
            "apk_name": apk.original_filename,
            "package_name": apk.package_name,
        },
    )

    logger.info(
        "Task %d created: APK=%s → Device=%s (pushed=%s)",
        task.id, apk.original_filename, device.device_name, sent,
    )

    return await _build_task_out(task, db)


@router.get("", response_model=TaskListResponse)
async def list_tasks(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    status_filter: Optional[str] = Query(None, alias="status"),
    device_id: Optional[int] = Query(None),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """List installation tasks (paginated, filterable)."""
    query = select(InstallTask)

    if status_filter:
        query = query.where(InstallTask.status == status_filter)
    if device_id is not None:
        query = query.where(InstallTask.device_id == device_id)

    count_q = await db.execute(query)
    total = len(count_q.scalars().all())

    result = await db.execute(
        query.order_by(InstallTask.created_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
    )
    tasks_list = result.scalars().all()

    items = [await _build_task_out(t, db) for t in tasks_list]
    return TaskListResponse(total=total, items=items)


@router.get("/{task_id}", response_model=TaskOut)
async def get_task(task_id: int, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Get a single task by ID."""
    task = await db.get(InstallTask, task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return await _build_task_out(task, db)


@router.patch("/{task_id}/status", response_model=TaskOut)
async def update_task_status(
    task_id: int,
    body: TaskStatusUpdateRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Update a task's status (web panel, requires JWT)."""
    return await _set_task_status(task_id, body.status, body.error_message, db)


@router.patch("/{task_id}/device-status", response_model=TaskOut)
async def update_task_status_device(
    task_id: int,
    body: DeviceStatusUpdateRequest,
    db: AsyncSession = Depends(get_db),
):
    """Update a task's status from a device (no JWT, device_id auth)."""
    # Verify device owns this task
    device = (await db.execute(select(Device).where(Device.device_id == body.device_id))).scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=403, detail="Unknown device")

    task = await db.get(InstallTask, task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    if device.id != task.device_id:
        raise HTTPException(status_code=403, detail="Device not authorized for this task")

    return await _set_task_status(task_id, body.status, body.error_message, db)


async def _set_task_status(task_id: int, status: str, error_message: str, db: AsyncSession) -> TaskOut:
    """Shared helper: validate and update task status."""
    valid_statuses = {"pending", "downloading", "downloaded", "installing", "success", "failed"}
    if status not in valid_statuses:
        raise HTTPException(status_code=400, detail=f"Invalid status. Must be one of: {valid_statuses}")

    task = await db.get(InstallTask, task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    task.status = status
    task.error_message = error_message
    task.updated_at = datetime.now(timezone.utc)
    await db.commit()
    await db.refresh(task)

    return await _build_task_out(task, db)

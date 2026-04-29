"""APK upload and download routes (web panel)."""

import logging
from typing import Optional

from fastapi import APIRouter, Depends, File, HTTPException, Query, Response, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.apk import APKFile
from app.models.user import User
from app.routes.auth import get_current_user
from app.services.apk_service import delete_apk, get_apk_path, save_apk

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/apps", tags=["apps"])


# ── Schemas ────────────────────────────────────────────────────────

class APKOut(BaseModel):
    id: int
    original_filename: str
    file_size: int
    package_name: str
    version_name: str
    uploaded_at: str

    class Config:
        from_attributes = True


class APKListResponse(BaseModel):
    total: int
    items: list[APKOut]


class UploadResponse(BaseModel):
    id: int
    original_filename: str
    file_size: int
    message: str


# ── Routes ─────────────────────────────────────────────────────────

@router.post("/upload", response_model=UploadResponse, status_code=status.HTTP_201_CREATED)
async def upload_apk(file: UploadFile = File(...), user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Upload an APK file from the web panel."""
    original_name, stored_name, size = await save_apk(file)

    apk = APKFile(
        original_filename=original_name,
        stored_filename=stored_name,
        file_size=size,
        package_name="",   # could be extracted via aapt, skipped for MVP
        version_name="",
        uploaded_by=user.id,
    )
    db.add(apk)
    await db.commit()
    await db.refresh(apk)

    logger.info("APK uploaded: %s (%d bytes) by user %s", original_name, size, user.username)
    return UploadResponse(
        id=apk.id,
        original_filename=original_name,
        file_size=size,
        message="Upload successful",
    )


@router.get("", response_model=APKListResponse)
async def list_apks(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """List uploaded APK files (paginated)."""
    count_q = await db.execute(select(APKFile))
    total = len(count_q.scalars().all())

    result = await db.execute(
        select(APKFile)
        .order_by(APKFile.uploaded_at.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
    )
    items = result.scalars().all()

    return APKListResponse(
        total=total,
        items=[
            APKOut(
                id=a.id,
                original_filename=a.original_filename,
                file_size=a.file_size,
                package_name=a.package_name,
                version_name=a.version_name,
                uploaded_at=a.uploaded_at.isoformat() if a.uploaded_at else "",
            )
            for a in items
        ],
    )


@router.delete("/{apk_id}")
async def delete_apk_endpoint(apk_id: int, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Delete an APK file and its record."""
    result = await db.execute(select(APKFile).where(APKFile.id == apk_id))
    apk = result.scalar_one_or_none()
    if not apk:
        raise HTTPException(status_code=404, detail="APK not found")

    delete_apk(apk.stored_filename)
    await db.delete(apk)
    await db.commit()
    return {"message": "Deleted"}


@router.get("/download/{apk_id}")
async def download_apk(apk_id: int, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Download an APK file (web panel, requires JWT)."""
    return await _serve_apk(apk_id, db)


@router.get("/device-download/{apk_id}")
async def device_download_apk(
    apk_id: int,
    device_id: str = Query(...),
    psk: str = Query(...),
    db: AsyncSession = Depends(get_db),
):
    """Download an APK file (device auth via device_id + PSK query params)."""
    from app.models.device import Device
    from app.utils.security import verify_device_psk

    if not verify_device_psk(psk):
        raise HTTPException(status_code=403, detail="Invalid device PSK")

    device = (await db.execute(select(Device).where(Device.device_id == device_id))).scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=403, detail="Unknown device")

    return await _serve_apk(apk_id, db)


async def _serve_apk(apk_id: int, db: AsyncSession):
    """Shared helper: serve an APK file from disk."""
    result = await db.execute(select(APKFile).where(APKFile.id == apk_id))
    apk = result.scalar_one_or_none()
    if not apk:
        raise HTTPException(status_code=404, detail="APK not found")

    path = get_apk_path(apk.stored_filename)
    if not path.exists():
        raise HTTPException(status_code=404, detail="File missing on server")

    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=apk.original_filename,
    )

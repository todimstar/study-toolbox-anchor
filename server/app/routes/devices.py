"""Device registration and listing routes."""

import logging

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.device import Device
from app.models.user import User
from app.routes.auth import get_current_user
from app.utils.security import get_device_psk_hash, verify_device_psk

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/devices", tags=["devices"])


# ── Schemas ────────────────────────────────────────────────────────

class RegisterDeviceRequest(BaseModel):
    device_id: str
    device_name: str = "Unknown"
    model: str = ""
    psk: str  # The pre-shared key – verified against DEVICE_PSK


class DeviceOut(BaseModel):
    id: int
    device_id: str
    device_name: str
    model: str
    is_online: bool
    last_seen: str
    created_at: str

    class Config:
        from_attributes = True


class DeviceListResponse(BaseModel):
    total: int
    items: list[DeviceOut]


# ── Routes ─────────────────────────────────────────────────────────

@router.post("/register", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)
async def register_device(body: RegisterDeviceRequest, db: AsyncSession = Depends(get_db)):
    """Register a new device. The PSK is verified against the server's DEVICE_PSK."""
    if not verify_device_psk(body.psk):
        raise HTTPException(status_code=403, detail="Invalid device pre-shared key")

    # Check if device already registered
    result = await db.execute(select(Device).where(Device.device_id == body.device_id))
    existing = result.scalar_one_or_none()
    if existing:
        # Update existing device info
        existing.device_name = body.device_name
        existing.model = body.model
        existing.psk_hash = get_device_psk_hash()
        await db.commit()
        await db.refresh(existing)
        return _to_device_out(existing)

    device = Device(
        device_id=body.device_id,
        device_name=body.device_name,
        model=body.model,
        psk_hash=get_device_psk_hash(),
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)

    logger.info("Device registered: %s (%s)", body.device_name, body.device_id)
    return _to_device_out(device)


@router.get("", response_model=DeviceListResponse)
async def list_devices(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """List all registered devices."""
    result = await db.execute(select(Device).order_by(Device.last_seen.desc()))
    devices = result.scalars().all()
    return DeviceListResponse(
        total=len(devices),
        items=[_to_device_out(d) for d in devices],
    )


@router.get("/{device_id}", response_model=DeviceOut)
async def get_device(
    device_id: int,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Get a single device by its database ID."""
    device = await db.get(Device, device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    return _to_device_out(device)


def _to_device_out(d: Device) -> DeviceOut:
    return DeviceOut(
        id=d.id,
        device_id=d.device_id,
        device_name=d.device_name,
        model=d.model,
        is_online=d.is_online,
        last_seen=d.last_seen.isoformat() if d.last_seen else "",
        created_at=d.created_at.isoformat() if d.created_at else "",
    )

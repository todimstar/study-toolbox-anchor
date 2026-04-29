"""WebSocket endpoint for device communication."""

import json
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from sqlalchemy import select

from app.database import async_session
from app.models.device import Device
from app.models.task import InstallTask
from app.services.ws_manager import ws_manager
from app.utils.security import verify_device_psk

logger = logging.getLogger(__name__)
router = APIRouter()


@router.websocket("/ws/device")
async def device_websocket(
    ws: WebSocket,
    device_id: str = Query(...),
    psk: str = Query(...),
):
    """Main WebSocket connection for a device.

    Query params: device_id=SOME_ID&psk=PSK_VALUE

    Messages from device:
      - {"type": "pong"}                            → keep-alive response
      - {"type": "task_status", "task_id": N,       → report task progress
         "status": "downloading|installing|success|failed",
         "error": "..."}

    Messages to device (pushed by server via WSManager):
      - {"type": "new_task", "task_id": N, ...}
    """

    # Authenticate device
    if not verify_device_psk(psk):
        await ws.close(code=4003, reason="Invalid PSK")
        return

    # Update device online status
    async with async_session() as db:
        result = await db.execute(select(Device).where(Device.device_id == device_id))
        device = result.scalar_one_or_none()
        if device:
            device.is_online = True
            device.last_seen = datetime.now(timezone.utc)
            await db.commit()

    await ws_manager.connect(device_id, ws)

    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            msg_type = msg.get("type", "")

            if msg_type == "pong":
                # Keep-alive heartbeat – nothing to do
                pass

            elif msg_type == "task_status":
                task_id = msg.get("task_id")
                status = msg.get("status", "")
                error_msg = msg.get("error", "")

                if task_id and status:
                    async with async_session() as db:
                        task = await db.get(InstallTask, task_id)
                        if task:
                            task.status = status
                            task.error_message = error_msg
                            task.updated_at = datetime.now(timezone.utc)
                            await db.commit()
                            logger.info("Task %d status → %s (device %s)", task_id, status, device_id)

            else:
                logger.debug("Unknown message type from %s: %s", device_id, msg_type)

    except WebSocketDisconnect:
        pass
    finally:
        ws_manager.disconnect(device_id)
        # Mark device offline
        async with async_session() as db:
            result = await db.execute(select(Device).where(Device.device_id == device_id))
            device = result.scalar_one_or_none()
            if device:
                device.is_online = False
                device.last_seen = datetime.now(timezone.utc)
                await db.commit()

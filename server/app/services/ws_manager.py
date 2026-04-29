"""WebSocket connection manager for device communication."""

import json
import logging
from typing import Dict

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class WSManager:
    """Manages all active device WebSocket connections.

    Keyed by device_id (string) so the server can push task notifications
    to the correct tablet.
    """

    def __init__(self) -> None:
        self._connections: Dict[str, WebSocket] = {}

    async def connect(self, device_id: str, ws: WebSocket) -> None:
        await ws.accept()
        self._connections[device_id] = ws
        logger.info("Device %s connected (%d active)", device_id, len(self._connections))

    def disconnect(self, device_id: str) -> None:
        self._connections.pop(device_id, None)
        logger.info("Device %s disconnected (%d active)", device_id, len(self._connections))

    async def send_task(self, device_id: str, payload: dict) -> bool:
        """Push a task notification to a specific device.

        Returns True if the message was queued, False if the device is offline.
        """
        ws = self._connections.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_text(json.dumps(payload))
            return True
        except Exception:
            self.disconnect(device_id)
            return False

    async def broadcast(self, payload: dict) -> None:
        """Send a message to all connected devices (rarely used)."""
        disconnected: list[str] = []
        for device_id, ws in self._connections.items():
            try:
                await ws.send_text(json.dumps(payload))
            except Exception:
                disconnected.append(device_id)
        for d in disconnected:
            self.disconnect(d)

    @property
    def online_count(self) -> int:
        return len(self._connections)

    def is_online(self, device_id: str) -> bool:
        return device_id in self._connections


ws_manager = WSManager()

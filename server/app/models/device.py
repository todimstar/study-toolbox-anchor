"""Registered Android tablet device."""

from sqlalchemy import Boolean, Column, Integer, String, DateTime, func

from app.database import Base


class Device(Base):
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(128), unique=True, nullable=False, index=True)  # e.g. Android ID
    device_name = Column(String(256), nullable=False, default="Unknown")
    model = Column(String(128), default="")  # e.g. BZC-W00
    # Hash of the shared device PSK – used to verify device authenticity
    psk_hash = Column(String(256), nullable=False)
    is_online = Column(Boolean, default=False)
    last_seen = Column(DateTime, server_default=func.now())
    created_at = Column(DateTime, server_default=func.now())

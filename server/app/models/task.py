"""Installation task – links an APK to a target device."""

from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, Text, func

from app.database import Base


class InstallTask(Base):
    __tablename__ = "install_tasks"

    id = Column(Integer, primary_key=True, index=True)
    apk_id = Column(Integer, ForeignKey("apk_files.id"), nullable=False)
    device_id = Column(Integer, ForeignKey("devices.id"), nullable=False)
    # pending → downloading → downloaded → installing → success / failed
    status = Column(String(32), nullable=False, default="pending", index=True)
    retry_count = Column(Integer, default=0)
    max_retries = Column(Integer, default=3)
    error_message = Column(Text, default="")
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())

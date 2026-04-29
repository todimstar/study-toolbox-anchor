"""Uploaded APK file metadata."""

from sqlalchemy import Column, Integer, String, DateTime, ForeignKey, func, BigInteger

from app.database import Base


class APKFile(Base):
    __tablename__ = "apk_files"

    id = Column(Integer, primary_key=True, index=True)
    original_filename = Column(String(512), nullable=False)  # original upload name
    stored_filename = Column(String(512), nullable=False)  # UUID-based name on disk
    file_size = Column(BigInteger, nullable=False)  # bytes
    package_name = Column(String(256), default="")  # extracted from APK
    version_name = Column(String(64), default="")
    uploaded_by = Column(Integer, ForeignKey("users.id"), nullable=False)
    uploaded_at = Column(DateTime, server_default=func.now())

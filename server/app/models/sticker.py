"""Custom sticker emoticons uploaded by the child for reuse in micro chat."""

from sqlalchemy import Column, DateTime, Integer, String, Text, func

from app.database import Base


class Sticker(Base):
    __tablename__ = "stickers"

    id = Column(Integer, primary_key=True, index=True)
    owner_role = Column(String(16), nullable=False, index=True)  # 'child' / 'parent'
    owner_key_hash = Column(String(64), nullable=False, default="", index=True)  # sha256(口令)，多家长隔离
    url = Column(String(255), nullable=False)                   # /api/uploads/stickers/xxx.jpg
    thumbnail_url = Column(String(255), nullable=True)          # 缩略图加速宫格
    name = Column(String(64), nullable=False, default="")
    created_at = Column(DateTime, server_default=func.now(), index=True)
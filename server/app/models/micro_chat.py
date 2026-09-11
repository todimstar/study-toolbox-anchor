"""Micro chat messages for parent-child toolbox interaction."""

from sqlalchemy import Column, DateTime, Integer, String, Text, func

from app.database import Base


class MicroChatMessage(Base):
    __tablename__ = "micro_chat_messages"

    id = Column(Integer, primary_key=True, index=True)
    sender_role = Column(String(16), nullable=False, index=True)
    sender_name = Column(String(32), nullable=False)
    sender_avatar = Column(String(64), nullable=False, default="")
    body = Column(Text, nullable=False)
    message_type = Column(String(16), nullable=False, default="text")
    attachment_url = Column(Text, nullable=True)
    attachment_urls = Column(Text, nullable=True)  # gallery 图集：JSON 数组字符串
    attachment_name = Column(String(255), nullable=True)
    attachment_mime = Column(String(128), nullable=True)
    attachment_size = Column(Integer, nullable=True)
    read_by_parent = Column(Integer, nullable=False, default=0)  # 家长是否已读
    read_by_child = Column(Integer, nullable=False, default=0)   # 孩子是否已读
    recalled = Column(Integer, nullable=False, default=0)        # 是否已撤回
    recalled_at = Column(DateTime, nullable=True)                # 撤回时间
    created_at = Column(DateTime, server_default=func.now(), index=True)

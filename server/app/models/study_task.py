"""Parent-delivered study tasks."""

from sqlalchemy import Column, DateTime, Integer, String, Text, func

from app.database import Base


class StudyTask(Base):
    __tablename__ = "study_tasks"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String(120), nullable=False)
    description = Column(Text, nullable=False, default="")
    granularity = Column(String(16), nullable=False, default="normal")
    status = Column(String(16), nullable=False, default="pending", index=True)
    resubmit_count = Column(Integer, nullable=False, default=0)
    created_by_name = Column(String(32), nullable=False, default="家长")
    child_reply = Column(Text, nullable=False, default="")
    created_at = Column(DateTime, server_default=func.now(), index=True)
    updated_at = Column(DateTime, server_default=func.now(), index=True)

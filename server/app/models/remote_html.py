"""Remote HTML deliveries for the study inbox."""

from sqlalchemy import Column, DateTime, Integer, String, Text, func

from app.database import Base


class RemoteHtmlDelivery(Base):
    __tablename__ = "remote_html_deliveries"

    id = Column(Integer, primary_key=True, index=True)
    title = Column(String(120), nullable=False)
    description = Column(Text, nullable=False, default="")
    html = Column(Text, nullable=False)
    created_by_name = Column(String(32), nullable=False, default="高人")
    created_at = Column(DateTime, server_default=func.now(), index=True)

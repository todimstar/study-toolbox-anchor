"""Database engine, session factory, and Base declarative model."""

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import settings

engine = create_async_engine(settings.DATABASE_URL, echo=False)
async_session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


async def get_db() -> AsyncSession:
    """FastAPI dependency – yields an async DB session."""
    async with async_session() as session:
        try:
            yield session
        finally:
            await session.close()


async def init_db() -> None:
    """Create all tables on startup."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        columns = await conn.execute(text("PRAGMA table_info(micro_chat_messages)"))
        existing = {row[1] for row in columns.fetchall()}
        if existing:
            migrations = {
                "sender_avatar": "ALTER TABLE micro_chat_messages ADD COLUMN sender_avatar VARCHAR(64) NOT NULL DEFAULT ''",
                "message_type": "ALTER TABLE micro_chat_messages ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'text'",
                "attachment_url": "ALTER TABLE micro_chat_messages ADD COLUMN attachment_url TEXT",
                "attachment_name": "ALTER TABLE micro_chat_messages ADD COLUMN attachment_name VARCHAR(255)",
                "attachment_mime": "ALTER TABLE micro_chat_messages ADD COLUMN attachment_mime VARCHAR(128)",
                "attachment_size": "ALTER TABLE micro_chat_messages ADD COLUMN attachment_size INTEGER",
            }
            for column, statement in migrations.items():
                if column not in existing:
                    await conn.execute(text(statement))
        task_columns = await conn.execute(text("PRAGMA table_info(study_tasks)"))
        existing_task_columns = {row[1] for row in task_columns.fetchall()}
        if existing_task_columns:
            task_migrations = {
                "resubmit_count": "ALTER TABLE study_tasks ADD COLUMN resubmit_count INTEGER NOT NULL DEFAULT 0",
            }
            for column, statement in task_migrations.items():
                if column not in existing_task_columns:
                    await conn.execute(text(statement))

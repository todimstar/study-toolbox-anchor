"""Database engine, session factory, and Base declarative model."""

import hashlib

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
                "read_by_parent": "ALTER TABLE micro_chat_messages ADD COLUMN read_by_parent INTEGER NOT NULL DEFAULT 0",
                "read_by_child": "ALTER TABLE micro_chat_messages ADD COLUMN read_by_child INTEGER NOT NULL DEFAULT 0",
                "recalled": "ALTER TABLE micro_chat_messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0",
                "recalled_at": "ALTER TABLE micro_chat_messages ADD COLUMN recalled_at DATETIME",
                "attachment_urls": "ALTER TABLE micro_chat_messages ADD COLUMN attachment_urls TEXT",
                "reply_to_id": "ALTER TABLE micro_chat_messages ADD COLUMN reply_to_id INTEGER",
                "quote_sender": "ALTER TABLE micro_chat_messages ADD COLUMN quote_sender VARCHAR(32)",
                "quote_body": "ALTER TABLE micro_chat_messages ADD COLUMN quote_body VARCHAR(200)",
                "quote_type": "ALTER TABLE micro_chat_messages ADD COLUMN quote_type VARCHAR(16)",
            }
            for column, statement in migrations.items():
                if column not in existing:
                    await conn.execute(text(statement))
        sticker_columns = await conn.execute(text("PRAGMA table_info(stickers)"))
        existing_sticker_columns = {row[1] for row in sticker_columns.fetchall()}
        if existing_sticker_columns and "owner_key_hash" not in existing_sticker_columns:
            await conn.execute(
                text("ALTER TABLE stickers ADD COLUMN owner_key_hash VARCHAR(64) NOT NULL DEFAULT ''")
            )
            parent_hash = hashlib.sha256((settings.PARENT_CHAT_KEY or "").encode("utf-8")).hexdigest()
            child_hash = hashlib.sha256((settings.CHILD_CHAT_KEY or "").encode("utf-8")).hexdigest()
            await conn.execute(
                text("UPDATE stickers SET owner_key_hash = :h WHERE owner_role = 'parent' AND owner_key_hash = ''"),
                {"h": parent_hash},
            )
            await conn.execute(
                text("UPDATE stickers SET owner_key_hash = :h WHERE owner_role = 'child' AND owner_key_hash = ''"),
                {"h": child_hash},
            )
        task_columns = await conn.execute(text("PRAGMA table_info(study_tasks)"))
        existing_task_columns = {row[1] for row in task_columns.fetchall()}
        if existing_task_columns:
            task_migrations = {
                "resubmit_count": "ALTER TABLE study_tasks ADD COLUMN resubmit_count INTEGER NOT NULL DEFAULT 0",
            }
            for column, statement in task_migrations.items():
                if column not in existing_task_columns:
                    await conn.execute(text(statement))

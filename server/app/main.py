"""Study Toolbox API application entry point."""

import logging
import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.config import settings
from app.database import init_db
from app.routes import micro_chat, study_tasks

# Force UTF-8 for console logging on Windows
handler = logging.StreamHandler(sys.stdout)
handler.setLevel(logging.INFO)
handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
if hasattr(handler, "stream") and hasattr(handler.stream, "reconfigure"):
    handler.stream.reconfigure(encoding="utf-8")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[handler],
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup: create DB tables. Shutdown: clean up connections."""
    await init_db()
    logger.info("Database tables initialized")
    yield


app = FastAPI(
    title="Study Toolbox API",
    version="0.1.0",
    lifespan=lifespan,
)

# CORS – allow web panel dev server
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "https://toolbox.zakuku.top",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(micro_chat.router)
app.include_router(study_tasks.router)

upload_root = Path(settings.UPLOAD_DIR)
upload_root.mkdir(parents=True, exist_ok=True)
app.mount("/api/uploads", StaticFiles(directory=upload_root), name="uploads")


@app.get("/")
async def root():
    return {"service": "Study Toolbox API", "version": "0.1.0"}

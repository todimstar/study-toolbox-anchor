"""Remote Silent Installer – FastAPI Application Entry Point."""

import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.database import init_db
from app.routes import apps, auth, devices, tasks, ws

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
    title="Remote Silent Installer API",
    version="0.1.0",
    lifespan=lifespan,
)

# CORS – allow web panel dev server
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers
app.include_router(auth.router)
app.include_router(apps.router)
app.include_router(devices.router)
app.include_router(tasks.router)
app.include_router(ws.router)


@app.get("/")
async def root():
    return {"service": "Remote Silent Installer API", "version": "0.1.0"}

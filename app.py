from __future__ import annotations
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any
import sqlite3
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
import ai
import db

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    yield


app = FastAPI(title="物栈 ItemNest", version="0.4.0", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


class ContainerIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    notes: str = ""


class ContainerPatch(BaseModel):
    name: str | None = None
    notes: str | None = None


class ItemIn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    container_id: int
    quantity: int | None = 1
    quantity_text: str = ""
    condition: str = "正常"
    notes: str = ""
    tags: str = ""


class ItemPatch(BaseModel):
    name: str | None = None
    container_id: int | None = None
    quantity: int | None = None
    quantity_text: str | None = None
    condition: str | None = None
    notes: str | None = None
    tags: str | None = None


class ChatIn(BaseModel):
    message: str = Field(min_length=1, max_length=2000)


class ActionIn(BaseModel):
    action: dict[str, Any]


class SettingsIn(BaseModel):
    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/manifest.webmanifest")
def manifest():
    return FileResponse(STATIC_DIR / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript")


@app.get("/api/summary")
def get_summary(): return db.summary()


@app.get("/api/containers")
def containers(): return db.list_containers()


@app.post("/api/containers")
def add_container(payload: ContainerIn):
    try: return db.create_container(payload.model_dump())
    except sqlite3.IntegrityError: raise HTTPException(409, "箱子名称已存在")


@app.patch("/api/containers/{container_id}")
def patch_container(container_id: int, payload: ContainerPatch):
    if not db.get_container(container_id): raise HTTPException(404, "箱子不存在")
    try: return db.update_container(container_id, payload.model_dump(exclude_none=True))
    except sqlite3.IntegrityError: raise HTTPException(409, "箱子名称已存在")


@app.delete("/api/containers/{container_id}")
def remove_container(container_id: int):
    try:
        if not db.delete_container(container_id): raise HTTPException(404, "箱子不存在")
        return {"ok": True}
    except ValueError as e: raise HTTPException(409, str(e))


@app.get("/api/items")
def items(q: str = Query(default="", max_length=200), container_id: int | None = None):
    return db.list_items(q, container_id)


@app.post("/api/items")
def add_item(payload: ItemIn):
    if not db.get_container(payload.container_id): raise HTTPException(400, "目标箱子不存在")
    return db.create_item(payload.model_dump())


@app.patch("/api/items/{item_id}")
def patch_item(item_id: int, payload: ItemPatch):
    if not db.get_item(item_id): raise HTTPException(404, "物品不存在")
    data = payload.model_dump(exclude_unset=True)
    if data.get("container_id") is not None and not db.get_container(data["container_id"]): raise HTTPException(400, "目标箱子不存在")
    return db.update_item(item_id, data)


@app.delete("/api/items/{item_id}")
def remove_item(item_id: int):
    if not db.delete_item(item_id): raise HTTPException(404, "物品不存在")
    return {"ok": True}


@app.get("/api/settings")
def get_settings(): return ai.get_settings(False)


@app.post("/api/settings")
def set_settings(payload: SettingsIn): return ai.save_settings(payload.model_dump(exclude_none=True))


@app.post("/api/chat")
async def chat(payload: ChatIn): return await ai.chat(payload.message)


@app.post("/api/ai/execute")
def execute(payload: ActionIn):
    try: return ai.execute_action(payload.action)
    except (ValueError, sqlite3.IntegrityError) as e: raise HTTPException(400, str(e))

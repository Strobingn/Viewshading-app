"""Small self-hosted collaboration service with durable SQLite version history."""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, Field


router = APIRouter(prefix="/collaboration", tags=["collaboration"])
DB_PATH = Path(os.getenv("VIEWSHADE_COLLAB_DB", str(Path(tempfile.gettempdir()) / "viewshade-collaboration.db")))
ACCESS_TOKEN = os.getenv("VIEWSHADE_COLLAB_TOKEN", "").strip()
LOCK = threading.RLock()


class ProjectCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=120)
    payload: dict[str, Any] = Field(default_factory=dict)


class VersionCreate(BaseModel):
    payload: dict[str, Any]
    message: str = Field("Updated analysis", min_length=1, max_length=240)


class CommentCreate(BaseModel):
    body: str = Field(..., min_length=1, max_length=4000)


class MemberCreate(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=120)
    role: Literal["viewer", "editor", "owner"] = "viewer"


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA journal_mode=WAL")
    return connection


def _initialize() -> None:
    with LOCK, _connect() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, owner_id TEXT NOT NULL,
                created_at REAL NOT NULL, updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS members (
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                user_id TEXT NOT NULL, role TEXT NOT NULL,
                PRIMARY KEY(project_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS versions (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                version INTEGER NOT NULL, author_id TEXT NOT NULL, message TEXT NOT NULL,
                payload_json TEXT NOT NULL, created_at REAL NOT NULL,
                UNIQUE(project_id, version)
            );
            CREATE TABLE IF NOT EXISTS comments (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                author_id TEXT NOT NULL, body TEXT NOT NULL, created_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_versions_project ON versions(project_id, version DESC);
            CREATE INDEX IF NOT EXISTS idx_comments_project ON comments(project_id, created_at);
            """
        )


_initialize()


def identity(
    authorization: str | None = Header(default=None),
    x_viewshade_user: str = Header(default="local-user"),
) -> str:
    if ACCESS_TOKEN:
        supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
        if supplied != ACCESS_TOKEN:
            raise HTTPException(401, "Invalid collaboration token")
    user = x_viewshade_user.strip()
    if not user or len(user) > 120:
        raise HTTPException(400, "X-Viewshade-User must identify the collaborator")
    return user


def _role(db: sqlite3.Connection, project_id: str, user_id: str) -> str | None:
    row = db.execute(
        "SELECT role FROM members WHERE project_id=? AND user_id=?",
        (project_id, user_id),
    ).fetchone()
    return row["role"] if row else None


def _require(db: sqlite3.Connection, project_id: str, user_id: str, write: bool = False, owner: bool = False) -> str:
    role = _role(db, project_id, user_id)
    if role is None or (write and role not in {"editor", "owner"}) or (owner and role != "owner"):
        raise HTTPException(403, "Project permission denied")
    return role


def _project(db: sqlite3.Connection, project_id: str) -> sqlite3.Row:
    row = db.execute("SELECT * FROM projects WHERE id=?", (project_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "Project not found")
    return row


def _project_response(db: sqlite3.Connection, project_id: str, user_id: str) -> dict[str, Any]:
    project = _project(db, project_id)
    role = _require(db, project_id, user_id)
    latest = db.execute(
        "SELECT * FROM versions WHERE project_id=? ORDER BY version DESC LIMIT 1",
        (project_id,),
    ).fetchone()
    return {
        "id": project["id"],
        "name": project["name"],
        "owner_id": project["owner_id"],
        "role": role,
        "created_at": project["created_at"],
        "updated_at": project["updated_at"],
        "version": latest["version"] if latest else 0,
        "payload": json.loads(latest["payload_json"]) if latest else {},
    }


@router.post("/projects")
def create_project(request: ProjectCreate, user_id: str = Depends(identity)):
    project_id = str(uuid.uuid4())
    now = time.time()
    with LOCK, _connect() as db:
        db.execute("INSERT INTO projects VALUES(?,?,?,?,?)", (project_id, request.name.strip(), user_id, now, now))
        db.execute("INSERT INTO members VALUES(?,?,?)", (project_id, user_id, "owner"))
        db.execute(
            "INSERT INTO versions VALUES(?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), project_id, 1, user_id, "Initial version", json.dumps(request.payload), now),
        )
        return _project_response(db, project_id, user_id)


@router.get("/projects")
def list_projects(user_id: str = Depends(identity)):
    with _connect() as db:
        ids = db.execute(
            "SELECT project_id FROM members WHERE user_id=? ORDER BY project_id",
            (user_id,),
        ).fetchall()
        return [_project_response(db, row["project_id"], user_id) for row in ids]


@router.get("/projects/{project_id}")
def get_project(project_id: str, user_id: str = Depends(identity)):
    with _connect() as db:
        return _project_response(db, project_id, user_id)


@router.post("/projects/{project_id}/versions")
def add_version(project_id: str, request: VersionCreate, user_id: str = Depends(identity)):
    now = time.time()
    with LOCK, _connect() as db:
        _project(db, project_id)
        _require(db, project_id, user_id, write=True)
        row = db.execute("SELECT COALESCE(MAX(version),0)+1 AS next FROM versions WHERE project_id=?", (project_id,)).fetchone()
        version = int(row["next"])
        db.execute(
            "INSERT INTO versions VALUES(?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), project_id, version, user_id, request.message, json.dumps(request.payload), now),
        )
        db.execute("UPDATE projects SET updated_at=? WHERE id=?", (now, project_id))
        return {"project_id": project_id, "version": version, "created_at": now}


@router.get("/projects/{project_id}/versions")
def list_versions(project_id: str, user_id: str = Depends(identity)):
    with _connect() as db:
        _project(db, project_id)
        _require(db, project_id, user_id)
        rows = db.execute(
            "SELECT id,version,author_id,message,created_at FROM versions WHERE project_id=? ORDER BY version DESC",
            (project_id,),
        ).fetchall()
        return [dict(row) for row in rows]


@router.get("/projects/{project_id}/versions/{version}")
def get_version(project_id: str, version: int, user_id: str = Depends(identity)):
    with _connect() as db:
        _project(db, project_id)
        _require(db, project_id, user_id)
        row = db.execute("SELECT * FROM versions WHERE project_id=? AND version=?", (project_id, version)).fetchone()
        if row is None:
            raise HTTPException(404, "Version not found")
        return {**dict(row), "payload": json.loads(row["payload_json"])}


@router.post("/projects/{project_id}/comments")
def add_comment(project_id: str, request: CommentCreate, user_id: str = Depends(identity)):
    now = time.time()
    comment_id = str(uuid.uuid4())
    with LOCK, _connect() as db:
        _project(db, project_id)
        _require(db, project_id, user_id)
        db.execute("INSERT INTO comments VALUES(?,?,?,?,?)", (comment_id, project_id, user_id, request.body.strip(), now))
        return {"id": comment_id, "author_id": user_id, "body": request.body.strip(), "created_at": now}


@router.get("/projects/{project_id}/comments")
def list_comments(
    project_id: str,
    after: float = Query(0.0, ge=0.0),
    user_id: str = Depends(identity),
):
    with _connect() as db:
        _project(db, project_id)
        _require(db, project_id, user_id)
        rows = db.execute(
            "SELECT id,author_id,body,created_at FROM comments WHERE project_id=? AND created_at>? ORDER BY created_at",
            (project_id, after),
        ).fetchall()
        return [dict(row) for row in rows]


@router.put("/projects/{project_id}/members")
def set_member(project_id: str, request: MemberCreate, user_id: str = Depends(identity)):
    with LOCK, _connect() as db:
        project = _project(db, project_id)
        _require(db, project_id, user_id, owner=True)
        if request.user_id == project["owner_id"] and request.role != "owner":
            raise HTTPException(400, "The project owner cannot be demoted")
        db.execute(
            "INSERT INTO members(project_id,user_id,role) VALUES(?,?,?) "
            "ON CONFLICT(project_id,user_id) DO UPDATE SET role=excluded.role",
            (project_id, request.user_id, request.role),
        )
        return {"project_id": project_id, "user_id": request.user_id, "role": request.role}

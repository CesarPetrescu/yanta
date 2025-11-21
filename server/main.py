"""FastAPI WebSocket server that backs the Live Notes phone + wear apps.

Features:
- SQLite persistence for projects (with per-project colors) and notes (title + markdown body).
- WebSocket sync: on connect we send the full state, and any new note/project is broadcast to
  every connected client (phone and watch bridge).
- Lightweight REST helpers to quickly inspect current state when debugging.
"""

from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path
from typing import Any, Dict, List

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

app = FastAPI(title="Live Notes Sync Server")

DB_PATH = Path(__file__).parent / "notes.db"


# --- SQLite helpers ---------------------------------------------------------
def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    DB_PATH.touch(exist_ok=True)
    with get_conn() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                color TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                project_id INTEGER,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO projects (name, color)
            VALUES (?, ?)
            """,
            ("General", "#90CAF9"),
        )
        conn.commit()


def serialize_projects() -> List[Dict[str, Any]]:
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT id, name, color FROM projects ORDER BY name ASC"
        ).fetchall()
    return [dict(row) for row in rows]


def serialize_notes() -> List[Dict[str, Any]]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT n.id, n.title, n.content, n.project_id, n.updated_at,
                   p.name AS project_name, p.color AS project_color
            FROM notes n
            LEFT JOIN projects p ON p.id = n.project_id
            ORDER BY n.updated_at DESC
            """
        ).fetchall()
    serialized = []
    for row in rows:
        serialized.append(
            {
                "id": row["id"],
                "title": row["title"],
                "content": row["content"],
                "projectId": row["project_id"],
                "projectName": row["project_name"],
                "projectColor": row["project_color"],
                "updatedAt": row["updated_at"],
            }
        )
    return serialized


def upsert_project(name: str, color: str) -> int:
    cleaned_name = name.strip() or "General"
    cleaned_color = color.strip() or "#90CAF9"
    with get_conn() as conn:
        conn.execute(
            """
            INSERT INTO projects (name, color)
            VALUES (?, ?)
            ON CONFLICT(name) DO UPDATE SET color=excluded.color
            """,
            (cleaned_name, cleaned_color),
        )
        project_id = conn.execute(
            "SELECT id FROM projects WHERE name = ?", (cleaned_name,)
        ).fetchone()[0]
        conn.commit()
        return int(project_id)


def store_note(note_payload: Dict[str, Any]) -> Dict[str, Any]:
    title = str(note_payload.get("title", "")).strip()
    content = str(note_payload.get("content", "")).strip()
    if not title or not content:
        raise ValueError("Both title and content are required for a note.")

    project_name = str(note_payload.get("projectName") or "General")
    project_color = str(note_payload.get("projectColor") or "#90CAF9")
    project_id = upsert_project(project_name, project_color)

    now_ms = int(time.time() * 1000)
    with get_conn() as conn:
        conn.execute(
            """
            INSERT INTO notes (title, content, project_id, updated_at)
            VALUES (?, ?, ?, ?)
            """,
            (title, content, project_id, now_ms),
        )
        note_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        conn.commit()

    return {
        "id": int(note_id),
        "title": title,
        "content": content,
        "projectId": project_id,
        "projectName": project_name,
        "projectColor": project_color,
        "updatedAt": now_ms,
    }


def build_state_payload() -> Dict[str, Any]:
    return {
        "projects": serialize_projects(),
        "notes": serialize_notes(),
    }


# --- Connection manager -----------------------------------------------------
class ConnectionManager:
    def __init__(self) -> None:
        self.active_connections: List[WebSocket] = []

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.active_connections.append(websocket)
        await websocket.send_json(build_state_payload())

    def disconnect(self, websocket: WebSocket) -> None:
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def broadcast(self, payload: dict) -> None:
        for connection in list(self.active_connections):
            try:
                await connection.send_json(payload)
            except Exception:
                self.disconnect(connection)


manager = ConnectionManager()
init_db()


# --- REST endpoints ---------------------------------------------------------
@app.get("/notes")
def get_notes() -> List[Dict[str, Any]]:
    return serialize_notes()


@app.get("/projects")
def get_projects() -> List[Dict[str, Any]]:
    return serialize_projects()


@app.get("/state")
def get_full_state() -> Dict[str, Any]:
    return build_state_payload()


# --- WebSocket sync ---------------------------------------------------------
@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await manager.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            new_project = payload.get("new_project")
            new_note = payload.get("new_note")

            try:
                if isinstance(new_project, dict):
                    upsert_project(
                        str(new_project.get("name") or "General"),
                        str(new_project.get("color") or "#90CAF9"),
                    )

                if isinstance(new_note, dict):
                    store_note(new_note)

                await manager.broadcast(build_state_payload())
            except ValueError:
                # Bad payload: ignore quietly to avoid breaking active connections.
                continue
    except WebSocketDisconnect:
        manager.disconnect(websocket)

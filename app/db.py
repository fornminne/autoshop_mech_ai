import json
import sqlite3
from pathlib import Path
from uuid import UUID

from app.models import Job

DB_PATH = Path("autoshop.db")


def init_db() -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                payload TEXT NOT NULL
            )
            """
        )
        conn.commit()


def save_job(job: Job) -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            "INSERT OR REPLACE INTO jobs (id, payload) VALUES (?, ?)",
            (str(job.id), job.model_dump_json()),
        )
        conn.commit()


def load_job(job_id: UUID) -> Job | None:
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute("SELECT payload FROM jobs WHERE id = ?", (str(job_id),)).fetchone()
    if row is None:
        return None
    return Job.model_validate(json.loads(row[0]))


def list_jobs() -> list[Job]:
    with sqlite3.connect(DB_PATH) as conn:
        rows = conn.execute("SELECT payload FROM jobs ORDER BY rowid DESC").fetchall()
    return [Job.model_validate(json.loads(row[0])) for row in rows]

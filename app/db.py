import json
import sqlite3
from pathlib import Path
from typing import TypeVar
from uuid import UUID

from pydantic import BaseModel

from app.models import Customer, Invoice, Job, RepairOrder

DB_PATH = Path("autoshop.db")
T = TypeVar("T", bound=BaseModel)

_TABLE_MODELS: dict[str, type[BaseModel]] = {
    "jobs": Job,
    "customers": Customer,
    "repair_orders": RepairOrder,
    "invoices": Invoice,
}


def init_db() -> None:
    with sqlite3.connect(DB_PATH) as conn:
        for table in _TABLE_MODELS:
            conn.execute(
                f"""
                CREATE TABLE IF NOT EXISTS {table} (
                    id TEXT PRIMARY KEY,
                    payload TEXT NOT NULL
                )
                """
            )
        conn.commit()


def _save(table: str, item: BaseModel) -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            f"INSERT OR REPLACE INTO {table} (id, payload) VALUES (?, ?)",
            (str(getattr(item, "id")), item.model_dump_json()),
        )
        conn.commit()


def _load(table: str, item_id: UUID, model: type[T]) -> T | None:
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute(
            f"SELECT payload FROM {table} WHERE id = ?", (str(item_id),)
        ).fetchone()
    if row is None:
        return None
    return model.model_validate(json.loads(row[0]))


def _list(table: str, model: type[T]) -> list[T]:
    with sqlite3.connect(DB_PATH) as conn:
        rows = conn.execute(f"SELECT payload FROM {table} ORDER BY rowid DESC").fetchall()
    return [model.model_validate(json.loads(row[0])) for row in rows]


def save_job(job: Job) -> None:
    _save("jobs", job)


def load_job(job_id: UUID) -> Job | None:
    return _load("jobs", job_id, Job)


def list_jobs() -> list[Job]:
    return _list("jobs", Job)


def save_customer(customer: Customer) -> None:
    _save("customers", customer)


def load_customer(customer_id: UUID) -> Customer | None:
    return _load("customers", customer_id, Customer)


def list_customers() -> list[Customer]:
    return _list("customers", Customer)


def save_repair_order(ro: RepairOrder) -> None:
    _save("repair_orders", ro)


def load_repair_order(ro_id: UUID) -> RepairOrder | None:
    return _load("repair_orders", ro_id, RepairOrder)


def list_repair_orders() -> list[RepairOrder]:
    return _list("repair_orders", RepairOrder)


def save_invoice(invoice: Invoice) -> None:
    _save("invoices", invoice)


def load_invoice(invoice_id: UUID) -> Invoice | None:
    return _load("invoices", invoice_id, Invoice)


def list_invoices() -> list[Invoice]:
    return _list("invoices", Invoice)

"""Postgres-backed persistence via the Supabase REST interface.

Replaces the previous SQLite JSON-blob store. The relational schema lives in
Supabase (see the create_autoshop_schema migration): customers, vehicles,
repair_orders, repair_order_lines, invoices, invoice_lines, and the legacy
jobs table.
"""

import httpx

from app.models import (
    Customer,
    Invoice,
    InvoiceStatus,
    Job,
    LineType,
    OBDScan,
    RepairOrder,
    RepairOrderLine,
    RepairOrderStatus,
    ApprovalStatus,
    Vehicle,
)
from app.config import settings

_TIMEOUT = 20.0


def _headers() -> dict[str, str]:
    return {
        "apikey": settings.supabase_anon_key,
        "Authorization": f"Bearer {settings.supabase_anon_key}",
        "Content-Type": "application/json",
    }


def _base_url() -> str:
    return f"{settings.supabase_url.rstrip('/')}/rest/v1"


async def _request(method: str, path: str, *, params: dict | None = None, json: object | None = None, prefer: str | None = None) -> httpx.Response:
    headers = _headers()
    if prefer:
        headers["Prefer"] = prefer
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        response = await client.request(
            method,
            f"{_base_url()}/{path}",
            params=params,
            json=json,
            headers=headers,
        )
    if response.status_code >= 400:
        raise RuntimeError(f"Database error {response.status_code}: {response.text}")
    return response


def _row_to_vehicle(row: dict) -> Vehicle:
    return Vehicle(
        id=row.get("id"),
        vin=row.get("vin"),
        year=row.get("year"),
        make=row.get("make"),
        model=row.get("model"),
        engine=row.get("engine"),
        trim=row.get("trim"),
        plate=row.get("plate"),
        mileage=row.get("mileage"),
        notes=row.get("notes") or "",
    )


def _vehicle_payload(vehicle: Vehicle) -> dict:
    return {
        "id": str(vehicle.id),
        "vin": vehicle.vin,
        "year": vehicle.year,
        "make": vehicle.make,
        "model": vehicle.model,
        "engine": vehicle.engine,
        "trim": vehicle.trim,
        "plate": vehicle.plate,
        "mileage": vehicle.mileage,
        "notes": vehicle.notes,
    }


def _ro_vehicle_columns(vehicle: Vehicle) -> dict:
    return {
        "vin": vehicle.vin,
        "vehicle_year": vehicle.year,
        "vehicle_make": vehicle.make,
        "vehicle_model": vehicle.model,
        "vehicle_engine": vehicle.engine,
        "vehicle_trim": vehicle.trim,
        "vehicle_plate": vehicle.plate,
        "vehicle_mileage": vehicle.mileage,
        "vehicle_notes": vehicle.notes,
    }


def _row_to_line(row: dict) -> RepairOrderLine:
    return RepairOrderLine(
        id=row["id"],
        line_type=LineType(row["line_type"]),
        description=row["description"],
        quantity=float(row["quantity"]),
        unit_price=float(row["unit_price"]),
        unit_cost=float(row["unit_cost"]) if row.get("unit_cost") is not None else None,
    )


def _line_payload(line: RepairOrderLine, *, repair_order_id: str | None = None, invoice_id: str | None = None) -> dict:
    payload = {
        "id": str(line.id),
        "line_type": int(line.line_type),
        "description": line.description,
        "quantity": line.quantity,
        "unit_price": line.unit_price,
        "unit_cost": line.unit_cost,
    }
    if repair_order_id is not None:
        payload["repair_order_id"] = repair_order_id
    if invoice_id is not None:
        payload["invoice_id"] = invoice_id
    return payload


def _row_to_repair_order(row: dict, lines: list[dict]) -> RepairOrder:
    vehicle = Vehicle(
        vin=row.get("vin"),
        year=row.get("vehicle_year"),
        make=row.get("vehicle_make"),
        model=row.get("vehicle_model"),
        engine=row.get("vehicle_engine"),
        trim=row.get("vehicle_trim"),
        plate=row.get("vehicle_plate"),
        mileage=row.get("vehicle_mileage"),
        notes=row.get("vehicle_notes") or "",
    )
    return RepairOrder(
        id=row["id"],
        ro_number=row.get("ro_number"),
        customer_id=row["customer_id"],
        vehicle=vehicle,
        complaint=row["complaint"],
        diagnosis_summary=row.get("diagnosis_summary") or "",
        technician_notes=row.get("technician_notes") or "",
        odometer_in=row.get("odometer_in"),
        status=RepairOrderStatus(row["status"]),
        assigned_technician=row.get("assigned_technician"),
        obd_scan=OBDScan.model_validate(row["obd_scan"]) if row.get("obd_scan") else None,
        lines=[_row_to_line(line) for line in lines],
        approval_status=ApprovalStatus(row.get("approval_status") or "not_sent"),
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def _row_to_invoice(row: dict, lines: list[dict]) -> Invoice:
    return Invoice(
        id=row["id"],
        invoice_number=row.get("invoice_number"),
        repair_order_id=row["repair_order_id"],
        customer_id=row["customer_id"],
        customer_display_name=row.get("customer_display_name") or "",
        lines=[_row_to_line(line) for line in lines],
        status=InvoiceStatus(row["status"]),
        notes=row.get("notes") or "",
        issued_at=row["issued_at"],
        due_at=row.get("due_at"),
        paid_at=row.get("paid_at"),
        parts_tax_rate=float(row.get("parts_tax_rate") or 0),
        labor_tax_rate=float(row.get("labor_tax_rate") or 0),
    )


# ---------------------------------------------------------------------------
# Customers
# ---------------------------------------------------------------------------

async def save_customer(customer: Customer) -> None:
    await _request(
        "POST",
        "customers?on_conflict=id",
        json={
            "id": str(customer.id),
            "first_name": customer.first_name,
            "last_name": customer.last_name,
            "email": customer.email,
            "phone": customer.phone,
            "notes": customer.notes,
            "archived": customer.archived,
        },
        prefer="resolution=merge-duplicates,return=minimal",
    )
    if customer.vehicles:
        await _request(
            "POST",
            "vehicles?on_conflict=id",
            json=[
                {**_vehicle_payload(vehicle), "customer_id": str(customer.id)}
                for vehicle in customer.vehicles
            ],
            prefer="resolution=merge-duplicates,return=minimal",
        )


async def load_customer(customer_id) -> Customer | None:
    response = await _request(
        "GET",
        "customers",
        params={
            "id": f"eq.{customer_id}",
            "select": "*,vehicles(*)",
        },
    )
    rows = response.json()
    if not rows:
        return None
    row = rows[0]
    return Customer(
        id=row["id"],
        first_name=row["first_name"],
        last_name=row["last_name"],
        email=row.get("email"),
        phone=row.get("phone"),
        notes=row.get("notes") or "",
        vehicles=[_row_to_vehicle(v) for v in row.get("vehicles") or []],
        archived=row.get("archived") or False,
        created_at=row["created_at"],
    )


async def list_customers() -> list[Customer]:
    response = await _request(
        "GET",
        "customers",
        params={
            "select": "*,vehicles(*)",
            "order": "created_at.desc",
        },
    )
    return [
        Customer(
            id=row["id"],
            first_name=row["first_name"],
            last_name=row["last_name"],
            email=row.get("email"),
            phone=row.get("phone"),
            notes=row.get("notes") or "",
            vehicles=[_row_to_vehicle(v) for v in row.get("vehicles") or []],
            archived=row.get("archived") or False,
            created_at=row["created_at"],
        )
        for row in response.json()
    ]


# ---------------------------------------------------------------------------
# Repair orders
# ---------------------------------------------------------------------------

async def save_repair_order(ro: RepairOrder) -> None:
    payload = {
        "id": str(ro.id),
        "ro_number": ro.ro_number,
        "customer_id": str(ro.customer_id),
        **_ro_vehicle_columns(ro.vehicle),
        "complaint": ro.complaint,
        "diagnosis_summary": ro.diagnosis_summary,
        "technician_notes": ro.technician_notes,
        "odometer_in": ro.odometer_in,
        "status": str(ro.status),
        "assigned_technician": ro.assigned_technician,
        "approval_status": str(ro.approval_status),
        "obd_scan": ro.obd_scan.model_dump(mode="json") if ro.obd_scan else None,
        "created_at": ro.created_at.isoformat(),
        "updated_at": ro.updated_at.isoformat(),
    }
    await _request(
        "POST",
        "repair_orders?on_conflict=id",
        json=payload,
        prefer="resolution=merge-duplicates,return=minimal",
    )

    ro_id = str(ro.id)
    existing = await _request(
        "GET",
        "repair_order_lines",
        params={"repair_order_id": f"eq.{ro_id}", "select": "id"},
    )
    existing_ids = {row["id"] for row in existing.json()}
    current_ids = {str(line.id) for line in ro.lines}

    stale = existing_ids - current_ids
    if stale:
        id_list = ",".join(f'"{x}"' for x in sorted(stale))
        await _request(
            "DELETE",
            "repair_order_lines",
            params={"repair_order_id": f"eq.{ro_id}", "id": f"in.({id_list})"},
        )

    if ro.lines:
        await _request(
            "POST",
            "repair_order_lines?on_conflict=id",
            json=[_line_payload(line, repair_order_id=ro_id) for line in ro.lines],
            prefer="resolution=merge-duplicates,return=minimal",
        )


async def _fetch_repair_orders(params: dict) -> list[RepairOrder]:
    response = await _request("GET", "repair_orders", params=params)
    return [
        _row_to_repair_order(row, row.get("repair_order_lines") or [])
        for row in response.json()
    ]


async def load_repair_order(ro_id) -> RepairOrder | None:
    results = await _fetch_repair_orders({"id": f"eq.{ro_id}", "select": "*,repair_order_lines(*)"})
    return results[0] if results else None


async def list_repair_orders() -> list[RepairOrder]:
    return await _fetch_repair_orders({
        "select": "*,repair_order_lines(*)",
        "order": "created_at.desc",
    })


# ---------------------------------------------------------------------------
# Invoices
# ---------------------------------------------------------------------------

async def save_invoice(invoice: Invoice) -> None:
    payload = {
        "id": str(invoice.id),
        "invoice_number": invoice.invoice_number,
        "repair_order_id": str(invoice.repair_order_id),
        "customer_id": str(invoice.customer_id),
        "customer_display_name": invoice.customer_display_name,
        "status": int(invoice.status),
        "notes": invoice.notes,
        "issued_at": invoice.issued_at.isoformat(),
        "due_at": invoice.due_at.isoformat() if invoice.due_at else None,
        "paid_at": invoice.paid_at.isoformat() if invoice.paid_at else None,
        "parts_tax_rate": invoice.parts_tax_rate,
        "labor_tax_rate": invoice.labor_tax_rate,
    }
    await _request(
        "POST",
        "invoices?on_conflict=id",
        json=payload,
        prefer="resolution=merge-duplicates,return=minimal",
    )

    invoice_id = str(invoice.id)
    existing = await _request(
        "GET",
        "invoice_lines",
        params={"invoice_id": f"eq.{invoice_id}", "select": "id"},
    )
    existing_ids = {row["id"] for row in existing.json()}
    current_ids = {str(line.id) for line in invoice.lines}

    stale = existing_ids - current_ids
    if stale:
        id_list = ",".join(f'"{x}"' for x in sorted(stale))
        await _request(
            "DELETE",
            "invoice_lines",
            params={"invoice_id": f"eq.{invoice_id}", "id": f"in.({id_list})"},
        )

    if invoice.lines:
        await _request(
            "POST",
            "invoice_lines?on_conflict=id",
            json=[_line_payload(line, invoice_id=invoice_id) for line in invoice.lines],
            prefer="resolution=merge-duplicates,return=minimal",
        )


async def _fetch_invoices(params: dict) -> list[Invoice]:
    response = await _request("GET", "invoices", params=params)
    return [
        _row_to_invoice(row, row.get("invoice_lines") or [])
        for row in response.json()
    ]


async def load_invoice(invoice_id) -> Invoice | None:
    results = await _fetch_invoices({"id": f"eq.{invoice_id}", "select": "*,invoice_lines(*)"})
    return results[0] if results else None


async def list_invoices() -> list[Invoice]:
    return await _fetch_invoices({
        "select": "*,invoice_lines(*)",
        "order": "issued_at.desc",
    })


# ---------------------------------------------------------------------------
# Legacy jobs
# ---------------------------------------------------------------------------

async def save_job(job: Job) -> None:
    await _request(
        "POST",
        "jobs?on_conflict=id",
        json={
            "id": str(job.id),
            "vehicle": job.vehicle.model_dump(mode="json"),
            "concern": job.concern,
            "status": job.status,
            "created_at": job.created_at.isoformat(),
        },
        prefer="resolution=merge-duplicates,return=minimal",
    )


def _row_to_job(row: dict) -> Job:
    return Job(
        id=row["id"],
        vehicle=Vehicle.model_validate(row["vehicle"]),
        concern=row["concern"],
        status=row.get("status") or "open",
        created_at=row["created_at"],
    )


async def load_job(job_id) -> Job | None:
    response = await _request("GET", "jobs", params={"id": f"eq.{job_id}"})
    rows = response.json()
    return _row_to_job(rows[0]) if rows else None


async def list_jobs() -> list[Job]:
    response = await _request("GET", "jobs", params={"order": "created_at.desc"})
    return [_row_to_job(row) for row in response.json()]


def init_db() -> None:
    """Schema is managed as Supabase migrations; nothing to initialize here."""
    return None

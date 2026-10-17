from datetime import datetime, timezone
from uuid import UUID

from fastapi import FastAPI, HTTPException, Query

from app.config import settings
from app.db import (
    init_db,
    list_customers,
    list_invoices,
    list_jobs,
    list_repair_orders,
    load_customer,
    load_invoice,
    load_job,
    load_repair_order,
    save_customer,
    save_invoice,
    save_job,
    save_repair_order,
)
from app.models import (
    Customer,
    CustomerCreate,
    DiagnosticEvidence,
    DiagnosticRequest,
    DiagnosticResponse,
    Invoice,
    InvoiceCreate,
    InvoiceStatus,
    Job,
    JobCreate,
    OBDScan,
    RepairOrder,
    RepairOrderCreate,
    RepairOrderLine,
    RepairOrderPatch,
    RepairOrderStatus,
    Vehicle,
    VinDecodeRequest,
)
from app.services.ai import get_ai_provider
from app.services.nhtsa import get_recalls
from app.services.vehicle import decode_vin

app = FastAPI(title=settings.app_name, version="0.3.0")


@app.on_event("startup")
def startup() -> None:
    init_db()


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": settings.app_name, "env": settings.app_env}


@app.post("/v1/vehicles/decode", response_model=Vehicle)
async def vehicle_decode(request: VinDecodeRequest) -> Vehicle:
    try:
        return await decode_vin(request.vin)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"VIN provider error: {exc}") from exc


@app.get("/v1/vehicles/recalls")
async def vehicle_recalls(
    make: str = Query(min_length=1),
    model: str = Query(min_length=1),
    year: int = Query(ge=1980, le=2100),
) -> dict:
    try:
        recalls = await get_recalls(make=make, model=model, year=year)
        return {"count": len(recalls), "results": recalls}
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Recall provider error: {exc}") from exc


@app.post("/v1/diagnostics/analyze", response_model=DiagnosticResponse)
async def diagnostic_analyze(request: DiagnosticRequest) -> DiagnosticResponse:
    try:
        return await get_ai_provider().diagnose(request)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"AI provider error: {exc}") from exc


@app.post("/v1/customers", response_model=Customer, status_code=201)
async def create_customer(request: CustomerCreate) -> Customer:
    customer = Customer(**request.model_dump())
    await save_customer(customer)
    return customer


@app.get("/v1/customers", response_model=list[Customer])
async def customers() -> list[Customer]:
    return await list_customers()


@app.get("/v1/customers/{customer_id}", response_model=Customer)
async def get_customer(customer_id: UUID) -> Customer:
    customer = await load_customer(customer_id)
    if customer is None:
        raise HTTPException(status_code=404, detail="Customer not found")
    return customer


@app.post("/v1/customers/{customer_id}/vehicles", response_model=Customer)
async def add_customer_vehicle(customer_id: UUID, vehicle: Vehicle) -> Customer:
    customer = await load_customer(customer_id)
    if customer is None:
        raise HTTPException(status_code=404, detail="Customer not found")
    customer.vehicles.append(vehicle)
    await save_customer(customer)
    return customer


@app.post("/v1/repair-orders", response_model=RepairOrder, status_code=201)
async def create_repair_order(request: RepairOrderCreate) -> RepairOrder:
    customer = await load_customer(request.customer_id)
    if customer is None:
        raise HTTPException(status_code=404, detail="Customer not found")
    ro = RepairOrder(
        customer_id=request.customer_id,
        vehicle=request.vehicle,
        complaint=request.complaint,
        odometer_in=request.odometer_in,
    )
    ro.ro_number = f"RO-{str(ro.id)[:8].upper()}"
    await save_repair_order(ro)
    return ro


@app.get("/v1/repair-orders", response_model=list[RepairOrder])
async def repair_orders() -> list[RepairOrder]:
    return await list_repair_orders()


@app.get("/v1/repair-orders/{ro_id}", response_model=RepairOrder)
async def get_repair_order(ro_id: UUID) -> RepairOrder:
    ro = await load_repair_order(ro_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")
    return ro


@app.patch("/v1/repair-orders/{ro_id}", response_model=RepairOrder)
async def patch_repair_order(ro_id: UUID, patch: RepairOrderPatch) -> RepairOrder:
    ro = await load_repair_order(ro_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")
    for key, value in patch.model_dump(exclude_none=True).items():
        setattr(ro, key, value)
    ro.updated_at = datetime.now(timezone.utc)
    await save_repair_order(ro)
    return ro


@app.post("/v1/repair-orders/{ro_id}/lines", response_model=RepairOrder)
async def add_repair_order_line(ro_id: UUID, line: RepairOrderLine) -> RepairOrder:
    ro = await load_repair_order(ro_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")
    if ro.status == RepairOrderStatus.CLOSED:
        raise HTTPException(status_code=409, detail="Closed repair order cannot be modified")
    ro.lines.append(line)
    ro.updated_at = datetime.now(timezone.utc)
    await save_repair_order(ro)
    return ro


@app.post("/v1/repair-orders/{ro_id}/obd-scan", response_model=RepairOrder)
async def attach_obd_scan(ro_id: UUID, scan: OBDScan) -> RepairOrder:
    ro = await load_repair_order(ro_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")
    ro.obd_scan = scan
    ro.updated_at = datetime.now(timezone.utc)
    await save_repair_order(ro)
    return ro


@app.post("/v1/repair-orders/{ro_id}/diagnose", response_model=DiagnosticResponse)
async def diagnose_repair_order(ro_id: UUID) -> DiagnosticResponse:
    ro = await load_repair_order(ro_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")

    scan = ro.obd_scan
    evidence = DiagnosticEvidence(
        dtcs=[x.code for x in scan.dtcs] if scan else [],
        symptoms=[ro.complaint],
        freeze_frame=scan.freeze_frame if scan else {},
        live_data=scan.live_data if scan else {},
        mode06_failures=scan.mode06_failures if scan else [],
        technician_notes=ro.technician_notes,
    )
    try:
        result = await get_ai_provider().diagnose(DiagnosticRequest(vehicle=ro.vehicle, evidence=evidence))
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"AI provider error: {exc}") from exc

    ro.diagnosis_summary = result.summary
    ro.updated_at = datetime.now(timezone.utc)
    await save_repair_order(ro)
    return result


@app.post("/v1/invoices", response_model=Invoice, status_code=201)
async def create_invoice(request: InvoiceCreate) -> Invoice:
    ro = await load_repair_order(request.repair_order_id)
    if ro is None:
        raise HTTPException(status_code=404, detail="Repair order not found")
    customer = await load_customer(ro.customer_id)
    if customer is None:
        raise HTTPException(status_code=404, detail="Customer not found")

    invoice = Invoice(
        repair_order_id=ro.id,
        customer_id=customer.id,
        customer_display_name=f"{customer.first_name} {customer.last_name}".strip(),
        lines=[line.model_copy(deep=True) for line in ro.lines],
        due_at=request.due_at,
        notes=request.notes,
        parts_tax_rate=request.parts_tax_rate,
        labor_tax_rate=request.labor_tax_rate,
    )
    invoice.invoice_number = f"INV-{str(invoice.id)[:8].upper()}"
    await save_invoice(invoice)
    return invoice


@app.get("/v1/invoices", response_model=list[Invoice])
async def invoices() -> list[Invoice]:
    return await list_invoices()


@app.get("/v1/invoices/{invoice_id}", response_model=Invoice)
async def get_invoice(invoice_id: UUID) -> Invoice:
    invoice = await load_invoice(invoice_id)
    if invoice is None:
        raise HTTPException(status_code=404, detail="Invoice not found")
    return invoice


@app.post("/v1/invoices/{invoice_id}/status/{status}", response_model=Invoice)
async def transition_invoice(invoice_id: UUID, status: InvoiceStatus) -> Invoice:
    invoice = await load_invoice(invoice_id)
    if invoice is None:
        raise HTTPException(status_code=404, detail="Invoice not found")
    invoice.status = status
    if status == InvoiceStatus.PAID:
        invoice.paid_at = datetime.now(timezone.utc)
    await save_invoice(invoice)
    return invoice


# Legacy MVP jobs API retained while the Android client migrates to repair orders.
@app.post("/v1/jobs", response_model=Job, status_code=201)
async def create_job(request: JobCreate) -> Job:
    job = Job(vehicle=request.vehicle, concern=request.concern)
    await save_job(job)
    return job


@app.get("/v1/jobs", response_model=list[Job])
async def jobs() -> list[Job]:
    return list_jobs()


@app.get("/v1/jobs/{job_id}", response_model=Job)
async def get_job(job_id: UUID) -> Job:
    job = await load_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return job

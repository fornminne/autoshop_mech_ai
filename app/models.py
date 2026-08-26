from datetime import datetime, timezone
from enum import IntEnum, StrEnum
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Vehicle(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    vin: str | None = None
    year: int | None = None
    make: str | None = None
    model: str | None = None
    engine: str | None = None
    trim: str | None = None
    plate: str | None = None
    mileage: int | None = Field(default=None, ge=0)
    notes: str = ""


class VinDecodeRequest(BaseModel):
    vin: str = Field(min_length=11, max_length=17)


class Customer(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    first_name: str
    last_name: str
    email: str | None = None
    phone: str | None = None
    notes: str = ""
    vehicles: list[Vehicle] = Field(default_factory=list)
    archived: bool = False
    created_at: datetime = Field(default_factory=utcnow)


class CustomerCreate(BaseModel):
    first_name: str
    last_name: str
    email: str | None = None
    phone: str | None = None
    notes: str = ""


class OBDCode(BaseModel):
    code: str
    description: str | None = None


class OBDScan(BaseModel):
    scan_id: UUID = Field(default_factory=uuid4)
    vin: str | None = None
    dongle_model: str | None = None
    dtcs: list[OBDCode] = Field(default_factory=list)
    readiness: dict[str, Any] = Field(default_factory=dict)
    freeze_frame: dict[str, Any] = Field(default_factory=dict)
    live_data: dict[str, Any] = Field(default_factory=dict)
    mode06_failures: list[dict[str, Any]] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=utcnow)


class DiagnosticEvidence(BaseModel):
    dtcs: list[str] = Field(default_factory=list)
    symptoms: list[str] = Field(default_factory=list)
    freeze_frame: dict[str, Any] = Field(default_factory=dict)
    live_data: dict[str, Any] = Field(default_factory=dict)
    mode06_failures: list[dict[str, Any]] = Field(default_factory=list)
    technician_notes: str = ""


class DiagnosticRequest(BaseModel):
    vehicle: Vehicle
    evidence: DiagnosticEvidence


class DiagnosticStep(BaseModel):
    order: int
    action: str
    rationale: str
    expected: str | None = None


class DiagnosticResponse(BaseModel):
    summary: str
    likely_causes: list[str]
    next_steps: list[DiagnosticStep]
    source_refs: list[str] = Field(default_factory=list)
    confidence: str = "preliminary"


class LineType(IntEnum):
    LABOR = 0
    PART = 1
    FEE = 2


class RepairOrderStatus(StrEnum):
    INTAKE = "intake"
    IN_PROGRESS = "in_progress"
    AWAITING_PARTS = "awaiting_parts"
    READY = "ready"
    CLOSED = "closed"


class ApprovalStatus(StrEnum):
    NOT_SENT = "not_sent"
    PENDING = "pending"
    APPROVED = "approved"
    DECLINED = "declined"


class RepairOrderLine(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    line_type: LineType
    description: str
    quantity: float = Field(default=1, gt=0)
    unit_price: float = Field(default=0, ge=0)
    unit_cost: float | None = Field(default=None, ge=0)

    @property
    def line_total(self) -> float:
        return round(self.quantity * self.unit_price, 2)


class RepairOrderCreate(BaseModel):
    customer_id: UUID
    vehicle: Vehicle
    complaint: str
    odometer_in: int | None = Field(default=None, ge=0)


class RepairOrder(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    ro_number: str | None = None
    customer_id: UUID
    vehicle: Vehicle
    complaint: str
    diagnosis_summary: str = ""
    technician_notes: str = ""
    odometer_in: int | None = Field(default=None, ge=0)
    status: RepairOrderStatus = RepairOrderStatus.INTAKE
    assigned_technician: str | None = None
    obd_scan: OBDScan | None = None
    lines: list[RepairOrderLine] = Field(default_factory=list)
    approval_status: ApprovalStatus = ApprovalStatus.NOT_SENT
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)

    @property
    def subtotal(self) -> float:
        return round(sum(line.line_total for line in self.lines), 2)


class RepairOrderPatch(BaseModel):
    status: RepairOrderStatus | None = None
    diagnosis_summary: str | None = None
    technician_notes: str | None = None
    assigned_technician: str | None = None
    approval_status: ApprovalStatus | None = None


class InvoiceStatus(IntEnum):
    DRAFT = 0
    SENT = 1
    PAID = 2
    VOID = 3


class InvoiceCreate(BaseModel):
    repair_order_id: UUID
    due_at: datetime | None = None
    notes: str = ""
    parts_tax_rate: float = Field(default=0, ge=0)
    labor_tax_rate: float = Field(default=0, ge=0)


class Invoice(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    invoice_number: str | None = None
    repair_order_id: UUID
    customer_id: UUID
    customer_display_name: str = ""
    lines: list[RepairOrderLine] = Field(default_factory=list)
    status: InvoiceStatus = InvoiceStatus.DRAFT
    notes: str = ""
    issued_at: datetime = Field(default_factory=utcnow)
    due_at: datetime | None = None
    paid_at: datetime | None = None
    parts_tax_rate: float = 0
    labor_tax_rate: float = 0

    @property
    def parts_subtotal(self) -> float:
        return round(sum(x.line_total for x in self.lines if x.line_type == LineType.PART), 2)

    @property
    def labor_subtotal(self) -> float:
        return round(sum(x.line_total for x in self.lines if x.line_type == LineType.LABOR), 2)

    @property
    def fee_subtotal(self) -> float:
        return round(sum(x.line_total for x in self.lines if x.line_type == LineType.FEE), 2)

    @property
    def total(self) -> float:
        parts_tax = self.parts_subtotal * self.parts_tax_rate
        labor_tax = self.labor_subtotal * self.labor_tax_rate
        return round(self.parts_subtotal + self.labor_subtotal + self.fee_subtotal + parts_tax + labor_tax, 2)


# Backward-compatible MVP job API. New development should use RepairOrder.
class JobCreate(BaseModel):
    vehicle: Vehicle
    concern: str


class Job(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    vehicle: Vehicle
    concern: str
    status: str = "open"
    created_at: datetime = Field(default_factory=utcnow)

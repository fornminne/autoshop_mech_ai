from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


class Vehicle(BaseModel):
    vin: str | None = None
    year: int | None = None
    make: str | None = None
    model: str | None = None
    engine: str | None = None
    trim: str | None = None


class VinDecodeRequest(BaseModel):
    vin: str = Field(min_length=11, max_length=17)


class DiagnosticEvidence(BaseModel):
    dtcs: list[str] = []
    symptoms: list[str] = []
    freeze_frame: dict[str, Any] = {}
    live_data: dict[str, Any] = {}
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
    source_refs: list[str] = []
    confidence: str = "preliminary"


class JobCreate(BaseModel):
    vehicle: Vehicle
    concern: str


class Job(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    vehicle: Vehicle
    concern: str
    status: str = "open"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

from uuid import UUID

from fastapi import FastAPI, HTTPException

from app.config import settings
from app.models import DiagnosticRequest, DiagnosticResponse, Job, JobCreate, Vehicle, VinDecodeRequest
from app.services.ai import get_ai_provider
from app.services.vehicle import decode_vin

app = FastAPI(title=settings.app_name, version="0.1.0")
_jobs: dict[UUID, Job] = {}


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": settings.app_name, "env": settings.app_env}


@app.post("/v1/vehicles/decode", response_model=Vehicle)
async def vehicle_decode(request: VinDecodeRequest) -> Vehicle:
    try:
        return await decode_vin(request.vin)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"VIN provider error: {exc}") from exc


@app.post("/v1/diagnostics/analyze", response_model=DiagnosticResponse)
async def diagnostic_analyze(request: DiagnosticRequest) -> DiagnosticResponse:
    return await get_ai_provider().diagnose(request)


@app.post("/v1/jobs", response_model=Job, status_code=201)
async def create_job(request: JobCreate) -> Job:
    job = Job(vehicle=request.vehicle, concern=request.concern)
    _jobs[job.id] = job
    return job


@app.get("/v1/jobs/{job_id}", response_model=Job)
async def get_job(job_id: UUID) -> Job:
    job = _jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return job

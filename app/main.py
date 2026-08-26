from uuid import UUID

from fastapi import FastAPI, HTTPException, Query

from app.config import settings
from app.db import init_db, list_jobs, load_job, save_job
from app.models import DiagnosticRequest, DiagnosticResponse, Job, JobCreate, Vehicle, VinDecodeRequest
from app.services.ai import get_ai_provider
from app.services.nhtsa import get_recalls
from app.services.vehicle import decode_vin

app = FastAPI(title=settings.app_name, version="0.2.0")


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


@app.post("/v1/jobs", response_model=Job, status_code=201)
async def create_job(request: JobCreate) -> Job:
    job = Job(vehicle=request.vehicle, concern=request.concern)
    save_job(job)
    return job


@app.get("/v1/jobs", response_model=list[Job])
async def jobs() -> list[Job]:
    return list_jobs()


@app.get("/v1/jobs/{job_id}", response_model=Job)
async def get_job(job_id: UUID) -> Job:
    job = load_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return job

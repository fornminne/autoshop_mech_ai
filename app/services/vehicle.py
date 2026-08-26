import httpx

from app.config import settings
from app.models import Vehicle


async def decode_vin(vin: str) -> Vehicle:
    url = f"{settings.nhtsa_base_url}/vehicles/DecodeVinValues/{vin}?format=json"
    async with httpx.AsyncClient(timeout=15) as client:
        response = await client.get(url)
        response.raise_for_status()
        payload = response.json()

    results = payload.get("Results") or []
    row = results[0] if results else {}

    year = row.get("ModelYear")
    return Vehicle(
        vin=vin.upper(),
        year=int(year) if year and year.isdigit() else None,
        make=row.get("Make") or None,
        model=row.get("Model") or None,
        engine=" ".join(
            part for part in [row.get("DisplacementL"), row.get("EngineConfiguration"), row.get("EngineCylinders")] if part
        ) or None,
        trim=row.get("Trim") or None,
    )

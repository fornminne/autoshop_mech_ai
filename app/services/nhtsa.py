import httpx


async def get_recalls(make: str, model: str, year: int) -> list[dict]:
    url = "https://api.nhtsa.gov/recalls/recallsByVehicle"
    params = {"make": make, "model": model, "modelYear": year}
    async with httpx.AsyncClient(timeout=15) as client:
        response = await client.get(url, params=params)
        response.raise_for_status()
        data = response.json()
    results = []
    for item in data.get("results", []):
        results.append(
            {
                "campaign_number": item.get("NHTSACampaignNumber"),
                "component": item.get("Component"),
                "summary": item.get("Summary"),
                "consequence": item.get("Consequence"),
                "remedy": item.get("Remedy"),
                "manufacturer": item.get("Manufacturer"),
            }
        )
    return results

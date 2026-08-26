# AUTOSHOP MECH AI

Clean-room automotive diagnostic and shop-assistant platform inspired by professional repair workflows.

## Current MVP

- VIN decoding through NHTSA vPIC
- NHTSA recall lookup by year/make/model
- Gemini diagnostic reasoning using the current `google-genai` SDK
- Safe stub fallback when no AI credential is configured
- Persistent SQLite repair jobs
- FastAPI/OpenAPI backend
- Android Kotlin/Jetpack Compose client
- VIN entry and decode flow
- DTC/symptom diagnostic workflow
- Retrofit backend client
- Read-only ELM327 transport/session abstraction

## Architecture

```text
Android App
   |-- Vehicle / VIN
   |-- Diagnostic Session
   |-- ELM327 read-only transport
   |
   v
FastAPI Backend
   |-- NHTSA VIN + Recalls
   |-- Gemini Diagnostic Engine
   |-- SQLite Shop Jobs
   |-- Service Data / RAG adapters (next)
   `-- OEM/licensed data adapters (next)
```

## Backend quick start

```bash
cp .env.example .env
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Set `GEMINI_API_KEY` in `.env` to enable Gemini. Without a key, diagnostic requests use a deterministic safe fallback instead of fabricating service information.

Open `http://127.0.0.1:8000/docs` for interactive API documentation.

## API

- `GET /health`
- `POST /v1/vehicles/decode`
- `GET /v1/vehicles/recalls?make=&model=&year=`
- `POST /v1/diagnostics/analyze`
- `POST /v1/jobs`
- `GET /v1/jobs`
- `GET /v1/jobs/{job_id}`

## Android

The Android Studio project is under `android/`. The emulator default backend is `http://10.0.2.2:8000/`.

For a physical Android device, change `BuildConfig.API_BASE_URL` in `android/app/build.gradle.kts` to the reachable HTTPS backend or LAN development address.

The ELM327 boundary is intentionally read-only in the MVP. `ObdTransport` can be implemented for Bluetooth Classic RFCOMM, BLE GATT, or Wi-Fi TCP adapters without changing the diagnostic UI or backend schema.

## Diagnostic safety and data integrity

The AI system prompt explicitly forbids inventing OEM pinouts, wiring colors, torque values, TSB identifiers, or service procedures. Exact vehicle-specific values should enter through authorized service-data adapters and be cited to the technician.

This project does not copy or bypass third-party subscription systems. OEM service information, wiring diagrams, TSB content, and other licensed repair data must come from sources the operator is authorized to access.

## Next build targets

1. Bluetooth Classic ELM327 implementation and PID parser
2. BLE/Wi-Fi ELM327 implementations
3. Freeze-frame and live-data capture into diagnostic requests
4. TSB metadata/provider adapter
5. Licensed service-data/RAG adapter with citations
6. Repair-order notes and diagnostic history UI
7. Estimates and customer-facing PDF reports
8. Multi-device shop accounts and authentication

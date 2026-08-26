# AUTOSHOP MECH AI

Clean-room automotive diagnostic and shop-assistant platform inspired by professional repair workflows.

## Goals

- VIN/YMME-aware vehicle context
- DTC and live-data interpretation
- AI-assisted diagnostic trees
- TSB/recall correlation
- OEM/service-data retrieval adapters
- Wiring/service-reference retrieval with citations
- Repair-order notes and diagnostic history
- Customer-facing summaries
- Multi-provider AI support (Gemini first, OpenAI-compatible optional)
- Android-first client architecture

## Architecture

```text
Android App
   |
   v
FastAPI Backend
   |-- Vehicle Context
   |-- Diagnostic Engine
   |-- AI Provider Layer
   |-- Service Data / RAG
   |-- TSB + Recall Sources
   |-- OBD Read-Only Adapter
   `-- Shop Jobs / Reports
```

## Quick start

```bash
cp .env.example .env
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Open `http://127.0.0.1:8000/docs`.

## Initial API

- `GET /health`
- `POST /v1/vehicles/decode`
- `POST /v1/diagnostics/analyze`
- `POST /v1/jobs`
- `GET /v1/jobs/{job_id}`

## Data policy

This project does not copy or bypass third-party subscription systems. OEM service information, wiring diagrams, TSB content, and other licensed repair data must come from sources the operator is authorized to access. Adapters are intentionally separated from the reasoning layer so licensed providers can be added cleanly.

## Roadmap

1. Core backend and diagnostic-session schema
2. Gemini provider
3. NHTSA VIN/recall integration
4. ELM327 read-only ingestion
5. Android client
6. Service-data RAG adapters
7. Wiring/TSB workflow
8. Shop reports, estimates, and multi-device accounts

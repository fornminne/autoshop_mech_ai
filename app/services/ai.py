import asyncio
import json
from abc import ABC, abstractmethod

from app.config import settings
from app.models import DiagnosticRequest, DiagnosticResponse, DiagnosticStep

SYSTEM_PROMPT = """You are an automotive diagnostic assistant for professional technicians.
Use supplied evidence only. Never invent OEM pinouts, torque values, wiring colors, TSB numbers,
or service procedures. When exact service information is unavailable, explicitly request it.
Return concise JSON with keys: summary, likely_causes, next_steps, source_refs, confidence.
next_steps must be a list of objects containing order, action, rationale, and optional expected.
Rank causes by evidence, prefer measurements over parts replacement, and identify uncertainty.
"""


class AIProvider(ABC):
    @abstractmethod
    async def diagnose(self, request: DiagnosticRequest) -> DiagnosticResponse:
        raise NotImplementedError


class StubProvider(AIProvider):
    async def diagnose(self, request: DiagnosticRequest) -> DiagnosticResponse:
        dtcs = ", ".join(request.evidence.dtcs) or "no DTCs supplied"
        return DiagnosticResponse(
            summary=f"Preliminary diagnostic session for {dtcs}.",
            likely_causes=[
                "Insufficient evidence for a ranked component-level diagnosis",
                "Verify powers, grounds, connectors, and relevant sensor/actuator signals",
            ],
            next_steps=[
                DiagnosticStep(order=1, action="Confirm and reproduce the concern", rationale="Establishes a repeatable failure condition."),
                DiagnosticStep(order=2, action="Capture DTC status, freeze-frame, and relevant live PIDs", rationale="Correlates the failure with operating conditions."),
                DiagnosticStep(order=3, action="Retrieve vehicle-specific service information and applicable TSBs", rationale="Exact topology and specifications are required before circuit-level testing."),
            ],
            confidence="preliminary",
        )


class GeminiProvider(AIProvider):
    def __init__(self) -> None:
        from google import genai
        self.client = genai.Client(api_key=settings.gemini_api_key)
        self.model = settings.model_name or "gemini-3.7-flash"

    async def diagnose(self, request: DiagnosticRequest) -> DiagnosticResponse:
        from google.genai import types
        prompt = request.model_dump_json(indent=2)

        def _call():
            return self.client.models.generate_content(
                model=self.model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_PROMPT,
                    response_mime_type="application/json",
                    temperature=0.15,
                ),
            )

        response = await asyncio.to_thread(_call)
        payload = json.loads(response.text)
        return DiagnosticResponse.model_validate(payload)


def get_ai_provider() -> AIProvider:
    if settings.ai_provider.lower() == "gemini" and settings.gemini_api_key:
        return GeminiProvider()
    return StubProvider()

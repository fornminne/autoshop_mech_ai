from abc import ABC, abstractmethod

from app.config import settings
from app.models import DiagnosticRequest, DiagnosticResponse, DiagnosticStep


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
                DiagnosticStep(
                    order=1,
                    action="Confirm the customer concern and reproduce the fault",
                    rationale="A repeatable symptom prevents diagnosis from drifting into parts-cannon territory.",
                ),
                DiagnosticStep(
                    order=2,
                    action="Capture DTC status, freeze-frame, and relevant live PIDs",
                    rationale="Correlates fault conditions with the vehicle state when the code set.",
                ),
                DiagnosticStep(
                    order=3,
                    action="Retrieve vehicle-specific service information and applicable TSBs",
                    rationale="Testing must use the correct topology, pinout, specifications, and known-failure information.",
                ),
            ],
            confidence="preliminary",
        )


def get_ai_provider() -> AIProvider:
    # Gemini/OpenAI implementations plug in here without changing API routes.
    # Until credentials and provider clients are configured, fail safe to a
    # deterministic diagnostic scaffold rather than fabricating service data.
    return StubProvider()

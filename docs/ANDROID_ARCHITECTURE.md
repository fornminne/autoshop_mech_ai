# Android Client Architecture

## Recommended stack

- Kotlin
- Jetpack Compose + Material 3
- Navigation Compose
- Hilt dependency injection
- Retrofit/OkHttp
- Room for cached vehicles/jobs
- DataStore for settings and API configuration
- BLE APIs for supported ELM327 adapters

## Primary screens

1. Garage
2. Vehicle detail
3. New diagnostic job
4. OBD scan/live data
5. Diagnostic assistant
6. Service information
7. Wiring
8. TSBs/recalls
9. Estimate/report
10. Settings

## Settings

The client should allow the operator to configure:

- Backend URL
- AI provider selection
- API keys when running in direct-provider mode
- Shop name/contact details
- Units
- Diagnostic verbosity
- Service-data providers

Secrets should use Android Keystore-backed encrypted storage and should never be committed to source control.

## OBD boundary

Phase 1 is read-only: DTCs, freeze-frame, readiness monitors, VIN where available, and supported PIDs. Active tests, coding, flashing, actuator commands, and bidirectional control require separate capability gating and vehicle-specific safety handling.

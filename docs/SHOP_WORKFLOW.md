# Locked Shop Workflow

This document is the MVP contract for AutoShop Mech AI.

## Product flow

Customer complaint → vehicle context → OBD evidence → AI diagnostic session → technician verification → repair-order lines → estimate/approval → repair → invoice → service history.

## Core entities

### Customer
Owns contact information and one or more vehicles.

### Vehicle
VIN, year, make, model, engine, trim, plate, mileage and shop notes.

### OBD scan
Stores DTCs, readiness data, freeze-frame values, live PIDs and Mode $06 failures. A scan can be attached directly to a repair order.

### Repair order
The central object in the application. AI is subordinate to the repair order, not a separate source of truth.

Fields include customer, vehicle, complaint, diagnosis summary, technician notes, odometer-in, technician assignment, OBD scan, estimate approval state and Labor / Part / Fee lines.

Status lifecycle:

`intake → in_progress → awaiting_parts → ready → closed`

### Invoice
Created from a repair order and snapshots its lines. Labor and parts have independent tax rates.

Status lifecycle:

`Draft(0) → Sent(1) → Paid(2)` with `Void(3)` as a terminal business state.

## AI boundary

Diagnostic inputs may include:

- vehicle identity and engine/trim context
- customer complaint and observed symptoms
- DTCs
- readiness state
- freeze-frame values
- live OBD PIDs
- Mode $06 failures
- technician notes and test results

Diagnostic output is advisory and must remain auditable. The first implementation stores the returned summary on the repair order while returning the full structured result to the client.

## API MVP

- `POST /v1/customers`
- `GET /v1/customers`
- `GET /v1/customers/{id}`
- `POST /v1/customers/{id}/vehicles`
- `POST /v1/repair-orders`
- `GET /v1/repair-orders`
- `GET /v1/repair-orders/{id}`
- `PATCH /v1/repair-orders/{id}`
- `POST /v1/repair-orders/{id}/lines`
- `POST /v1/repair-orders/{id}/obd-scan`
- `POST /v1/repair-orders/{id}/diagnose`
- `POST /v1/invoices`
- `GET /v1/invoices`
- `GET /v1/invoices/{id}`
- `POST /v1/invoices/{id}/status/{status}`

The older `/v1/jobs` API remains temporarily for Android-client compatibility and should eventually be migrated to repair orders.

## Next implementation targets

1. Persist diagnostic sessions and test-result history instead of only the latest summary.
2. Add update/delete operations for RO and invoice lines.
3. Add estimate approval links with opaque, single-purpose tokens and strict no-referrer behavior.
4. Add role-aware shop authentication and authorization.
5. Migrate the Android Compose client from `Job` to `RepairOrder`.
6. Add technician-facing diagnostic test workflow and AI re-evaluation after each recorded test result.

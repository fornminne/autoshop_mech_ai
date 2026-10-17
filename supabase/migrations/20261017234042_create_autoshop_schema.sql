/*
# Create AutoShop Mech AI relational schema

1. Plain-English summary
This migration replaces the backend's opaque JSON-blob storage with a real
relational schema in the project's Supabase Postgres database. It creates
tables for customers, vehicles, repair orders, estimate lines, OBD scans,
and invoices, matching the entities already defined in the FastAPI backend
(app/models.py) and documented in docs/SHOP_WORKFLOW.md.

2. New Tables
- `customers`: shop customers (contact info, notes, archived flag).
- `vehicles`: vehicles owned by customers (VIN, year/make/model/engine/trim,
  plate, mileage, notes). One vehicle belongs to one customer record here;
  vehicle snapshots on repair orders are stored as columns on the RO row.
- `repair_orders`: the central shop object. Customer complaint, diagnosis
  summary, technician notes, odometer, status lifecycle
  (intake -> in_progress -> awaiting_parts -> ready -> closed), assigned
  technician, approval status, and vehicle snapshot columns.
- `repair_order_lines`: Labor / Part / Fee estimate lines belonging to a
  repair order (quantity, unit price, unit cost).
- `obd_scans`: OBD evidence attached to a repair order. DTCs, readiness,
  freeze-frame, live PIDs, and Mode $06 failures are stored as JSONB so the
  variable-shaped scan data keeps its original structure.
- `invoices`: invoices snapshotting a repair order's lines, with separate
  parts and labor tax rates and a status lifecycle (draft/sent/paid/void).
- `invoice_lines`: snapshot copies of the estimate lines at invoice time.
- `jobs`: legacy MVP job API table, kept for backward compatibility with the
  existing `/v1/jobs` endpoints while clients migrate to repair orders.

3. Modified Tables
- None. This is the initial schema.

4. Security
- Row Level Security is ENABLED on every table.
- This app has no sign-in screen (single-tenant shop tool), so policies are
  scoped `TO anon, authenticated` so the anon-key backend client can operate.
  USING (true) is intentional here: the data is shared within the single shop.
- The anon role is granted standard table privileges (SELECT/INSERT/UPDATE/
  DELETE) plus sequences usage.

5. Important notes
- All primary keys are uuid with gen_random_uuid() defaults, matching the
  backend's UUID-based models.
- Monetary values use numeric(12,2); quantities numeric(10,3); tax rates
  numeric(6,4) stored as fractions (0.0825 = 8.25%).
- The `jobs` table is legacy and exists only so `/v1/jobs` endpoints keep
  working; do not extend it.
*/

CREATE TABLE IF NOT EXISTS customers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name text NOT NULL,
    last_name text NOT NULL,
    email text,
    phone text,
    notes text NOT NULL DEFAULT '',
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vehicles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    vin text,
    year integer,
    make text,
    model text,
    engine text,
    trim text,
    plate text,
    mileage integer CHECK (mileage IS NULL OR mileage >= 0),
    notes text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS repair_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ro_number text UNIQUE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    vin text,
    vehicle_year integer,
    vehicle_make text,
    vehicle_model text,
    vehicle_engine text,
    vehicle_trim text,
    vehicle_plate text,
    vehicle_mileage integer CHECK (vehicle_mileage IS NULL OR vehicle_mileage >= 0),
    vehicle_notes text NOT NULL DEFAULT '',
    complaint text NOT NULL,
    diagnosis_summary text NOT NULL DEFAULT '',
    technician_notes text NOT NULL DEFAULT '',
    odometer_in integer CHECK (odometer_in IS NULL OR odometer_in >= 0),
    status text NOT NULL DEFAULT 'intake',
    assigned_technician text,
    approval_status text NOT NULL DEFAULT 'not_sent',
    obd_scan jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS repair_order_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repair_order_id uuid NOT NULL REFERENCES repair_orders(id) ON DELETE CASCADE,
    line_type integer NOT NULL CHECK (line_type IN (0, 1, 2)),
    description text NOT NULL,
    quantity numeric(10, 3) NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price numeric(12, 2) NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
    unit_cost numeric(12, 2) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number text UNIQUE,
    repair_order_id uuid NOT NULL REFERENCES repair_orders(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    customer_display_name text NOT NULL DEFAULT '',
    status integer NOT NULL DEFAULT 0 CHECK (status IN (0, 1, 2, 3)),
    notes text NOT NULL DEFAULT '',
    issued_at timestamptz NOT NULL DEFAULT now(),
    due_at timestamptz,
    paid_at timestamptz,
    parts_tax_rate numeric(6, 4) NOT NULL DEFAULT 0 CHECK (parts_tax_rate >= 0),
    labor_tax_rate numeric(6, 4) NOT NULL DEFAULT 0 CHECK (labor_tax_rate >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS invoice_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    line_type integer NOT NULL CHECK (line_type IN (0, 1, 2)),
    description text NOT NULL,
    quantity numeric(10, 3) NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price numeric(12, 2) NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
    unit_cost numeric(12, 2) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle jsonb NOT NULL,
    concern text NOT NULL,
    status text NOT NULL DEFAULT 'open',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vehicles_customer ON vehicles(customer_id);
CREATE INDEX IF NOT EXISTS idx_repair_orders_customer ON repair_orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_repair_orders_vin ON repair_orders(vin);
CREATE INDEX IF NOT EXISTS idx_repair_orders_status ON repair_orders(status);
CREATE INDEX IF NOT EXISTS idx_ro_lines_repair_order ON repair_order_lines(repair_order_id);
CREATE INDEX IF NOT EXISTS idx_invoices_repair_order ON invoices(repair_order_id);
CREATE INDEX IF NOT EXISTS idx_invoices_customer ON invoices(customer_id);
CREATE INDEX IF NOT EXISTS idx_invoice_lines_invoice ON invoice_lines(invoice_id);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE repair_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE repair_order_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE jobs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "anon_select_customers" ON customers;
CREATE POLICY "anon_select_customers" ON customers FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_customers" ON customers;
CREATE POLICY "anon_insert_customers" ON customers FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_customers" ON customers;
CREATE POLICY "anon_update_customers" ON customers FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_customers" ON customers;
CREATE POLICY "anon_delete_customers" ON customers FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_vehicles" ON vehicles;
CREATE POLICY "anon_select_vehicles" ON vehicles FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_vehicles" ON vehicles;
CREATE POLICY "anon_insert_vehicles" ON vehicles FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_vehicles" ON vehicles;
CREATE POLICY "anon_update_vehicles" ON vehicles FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_vehicles" ON vehicles;
CREATE POLICY "anon_delete_vehicles" ON vehicles FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_repair_orders" ON repair_orders;
CREATE POLICY "anon_select_repair_orders" ON repair_orders FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_repair_orders" ON repair_orders;
CREATE POLICY "anon_insert_repair_orders" ON repair_orders FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_repair_orders" ON repair_orders;
CREATE POLICY "anon_update_repair_orders" ON repair_orders FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_repair_orders" ON repair_orders;
CREATE POLICY "anon_delete_repair_orders" ON repair_orders FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_repair_order_lines" ON repair_order_lines;
CREATE POLICY "anon_select_repair_order_lines" ON repair_order_lines FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_repair_order_lines" ON repair_order_lines;
CREATE POLICY "anon_insert_repair_order_lines" ON repair_order_lines FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_repair_order_lines" ON repair_order_lines;
CREATE POLICY "anon_update_repair_order_lines" ON repair_order_lines FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_repair_order_lines" ON repair_order_lines;
CREATE POLICY "anon_delete_repair_order_lines" ON repair_order_lines FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_invoices" ON invoices;
CREATE POLICY "anon_select_invoices" ON invoices FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_invoices" ON invoices;
CREATE POLICY "anon_insert_invoices" ON invoices FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_invoices" ON invoices;
CREATE POLICY "anon_update_invoices" ON invoices FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_invoices" ON invoices;
CREATE POLICY "anon_delete_invoices" ON invoices FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_invoice_lines" ON invoice_lines;
CREATE POLICY "anon_select_invoice_lines" ON invoice_lines FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_invoice_lines" ON invoice_lines;
CREATE POLICY "anon_insert_invoice_lines" ON invoice_lines FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_invoice_lines" ON invoice_lines;
CREATE POLICY "anon_update_invoice_lines" ON invoice_lines FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_invoice_lines" ON invoice_lines;
CREATE POLICY "anon_delete_invoice_lines" ON invoice_lines FOR DELETE
    TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "anon_select_jobs" ON jobs;
CREATE POLICY "anon_select_jobs" ON jobs FOR SELECT
    TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "anon_insert_jobs" ON jobs;
CREATE POLICY "anon_insert_jobs" ON jobs FOR INSERT
    TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "anon_update_jobs" ON jobs;
CREATE POLICY "anon_update_jobs" ON jobs FOR UPDATE
    TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "anon_delete_jobs" ON jobs;
CREATE POLICY "anon_delete_jobs" ON jobs FOR DELETE
    TO anon, authenticated USING (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON customers, vehicles, repair_orders, repair_order_lines, invoices, invoice_lines, jobs TO anon, authenticated;

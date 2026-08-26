from app.models import (
    Customer,
    Invoice,
    InvoiceStatus,
    LineType,
    RepairOrder,
    RepairOrderLine,
    RepairOrderStatus,
    Vehicle,
)


def test_repair_order_subtotal() -> None:
    customer = Customer(first_name="Test", last_name="Customer")
    vehicle = Vehicle(year=2017, make="Chevrolet", model="Silverado")
    ro = RepairOrder(customer_id=customer.id, vehicle=vehicle, complaint="Rough idle")
    ro.lines.extend(
        [
            RepairOrderLine(line_type=LineType.LABOR, description="Diagnosis", quantity=1.5, unit_price=120),
            RepairOrderLine(line_type=LineType.PART, description="Ignition coil", quantity=1, unit_price=80),
        ]
    )

    assert ro.subtotal == 260.0
    assert ro.status == RepairOrderStatus.INTAKE


def test_invoice_separate_labor_and_parts_tax() -> None:
    customer = Customer(first_name="Test", last_name="Customer")
    vehicle = Vehicle(year=2017, make="Chevrolet", model="Silverado")
    ro = RepairOrder(customer_id=customer.id, vehicle=vehicle, complaint="Rough idle")
    lines = [
        RepairOrderLine(line_type=LineType.LABOR, description="Labor", quantity=1, unit_price=100),
        RepairOrderLine(line_type=LineType.PART, description="Part", quantity=1, unit_price=50),
        RepairOrderLine(line_type=LineType.FEE, description="Shop supplies", quantity=1, unit_price=10),
    ]
    invoice = Invoice(
        repair_order_id=ro.id,
        customer_id=customer.id,
        lines=lines,
        parts_tax_rate=0.08,
        labor_tax_rate=0.02,
    )

    assert invoice.parts_subtotal == 50
    assert invoice.labor_subtotal == 100
    assert invoice.fee_subtotal == 10
    assert invoice.total == 166
    assert invoice.status == InvoiceStatus.DRAFT

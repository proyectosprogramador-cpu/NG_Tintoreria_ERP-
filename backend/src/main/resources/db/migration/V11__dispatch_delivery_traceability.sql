-- v0.10.2 - Despacho, entrega y trazabilidad final

ALTER TABLE production_orders DROP CONSTRAINT ck_production_order_status;
ALTER TABLE production_orders
  ADD CONSTRAINT ck_production_order_status CHECK (status IN (
    'PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','REPROCESS_REQUIRED',
    'READY_FOR_DISPATCH','PARTIALLY_DISPATCHED','DISPATCHED','DELIVERED',
    'QUALITY_REJECTED','CANCELLED'
  ));

ALTER TABLE sales_orders DROP CONSTRAINT ck_sales_orders_status;
ALTER TABLE sales_orders ADD CONSTRAINT ck_sales_orders_status CHECK (
  status IN ('NEW','CONFIRMED','ON_HOLD','READY_FOR_RECEPTION','PARTIALLY_RECEIVED','RECEIVED',
             'PARTIALLY_DISPATCHED','DISPATCHED','DELIVERED','CANCELLED')
);

CREATE SEQUENCE dispatch_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE dispatches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dispatch_number varchar(30) NOT NULL UNIQUE,
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE RESTRICT,
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  status varchar(24) NOT NULL DEFAULT 'DRAFT',
  dispatch_date date NOT NULL DEFAULT current_date,
  transport_mode varchar(24) NOT NULL DEFAULT 'OWN',
  destination_address varchar(300),
  carrier_name varchar(180),
  driver_name varchar(180),
  driver_document varchar(40),
  vehicle_plate varchar(30),
  guide_number varchar(60),
  customer_reference varchar(120),
  total_rolls integer NOT NULL DEFAULT 0,
  total_weight_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_meters numeric(16,3) NOT NULL DEFAULT 0,
  actual_dispatch_at timestamptz,
  recipient_name varchar(180),
  recipient_document varchar(40),
  recipient_role varchar(120),
  delivered_at timestamptz,
  delivery_notes text,
  notes text,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_dispatch_status CHECK (status IN ('DRAFT','READY','DISPATCHED','DELIVERED','CANCELLED')),
  CONSTRAINT ck_dispatch_transport CHECK (transport_mode IN ('OWN','THIRD_PARTY','CLIENT_PICKUP')),
  CONSTRAINT ck_dispatch_totals CHECK (total_rolls >= 0 AND total_weight_kg >= 0 AND total_meters >= 0)
);
CREATE INDEX idx_dispatches_order ON dispatches(production_order_id,created_at DESC);
CREATE INDEX idx_dispatches_sales_order ON dispatches(sales_order_id,created_at DESC);
CREATE INDEX idx_dispatches_status ON dispatches(status,dispatch_date DESC);

CREATE UNIQUE INDEX uq_dispatch_open_per_order
  ON dispatches(production_order_id)
  WHERE status IN ('DRAFT','READY');

CREATE TABLE dispatch_rolls (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dispatch_id uuid NOT NULL REFERENCES dispatches(id) ON DELETE CASCADE,
  line_no integer NOT NULL,
  roll_code varchar(80) NOT NULL,
  weight_kg numeric(16,3) NOT NULL,
  meters numeric(16,3) NOT NULL DEFAULT 0,
  pieces integer NOT NULL DEFAULT 1,
  notes varchar(500),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_dispatch_roll_line UNIQUE(dispatch_id,line_no),
  CONSTRAINT ck_dispatch_roll_values CHECK (weight_kg > 0 AND meters >= 0 AND pieces > 0)
);
CREATE INDEX idx_dispatch_rolls_dispatch ON dispatch_rolls(dispatch_id,line_no);

CREATE TABLE dispatch_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dispatch_id uuid NOT NULL REFERENCES dispatches(id) ON DELETE CASCADE,
  event_type varchar(40) NOT NULL,
  from_status varchar(24),
  to_status varchar(24),
  comment varchar(700),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispatch_events_dispatch ON dispatch_events(dispatch_id,created_at);

INSERT INTO permissions(code,name) VALUES
('DISPATCH_READ','Consultar despachos y entregas'),
('DISPATCH_WRITE','Preparar despachos y rollos de salida'),
('DISPATCH_CONFIRM','Confirmar salida física del despacho'),
('DISPATCH_DELIVERY','Registrar entrega y recepción por cliente')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('DISPATCH_READ','DISPATCH_WRITE','DISPATCH_CONFIRM','DISPATCH_DELIVERY')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('DISPATCH_READ','DISPATCH_WRITE','DISPATCH_CONFIRM','DISPATCH_DELIVERY')
WHERE r.code='DISPATCH'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='DISPATCH_READ'
WHERE r.code IN ('SALES','QUALITY','PRODUCTION','WAREHOUSE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='DISPATCH'
ON CONFLICT DO NOTHING;

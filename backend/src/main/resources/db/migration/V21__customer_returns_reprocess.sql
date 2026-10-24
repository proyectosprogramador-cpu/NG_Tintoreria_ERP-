-- v0.12.2 - Devoluciones post-despacho, reclamos y reproceso trazable

CREATE SEQUENCE IF NOT EXISTS customer_return_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS customer_returns (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  return_number varchar(30) NOT NULL UNIQUE,
  dispatch_id uuid NOT NULL REFERENCES dispatches(id) ON DELETE RESTRICT,
  source_production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE RESTRICT,
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  status varchar(24) NOT NULL DEFAULT 'DRAFT',
  reason_code varchar(30) NOT NULL DEFAULT 'OTHER',
  decision varchar(24) NOT NULL DEFAULT 'PENDING',
  return_date date NOT NULL DEFAULT current_date,
  claim_reference varchar(120),
  description text,
  inspection_notes text,
  decision_notes text,
  total_rolls integer NOT NULL DEFAULT 0,
  total_weight_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_meters numeric(16,3) NOT NULL DEFAULT 0,
  return_transport_cost numeric(18,4) NOT NULL DEFAULT 0,
  other_cost numeric(18,4) NOT NULL DEFAULT 0,
  credit_amount numeric(18,4) NOT NULL DEFAULT 0,
  received_by varchar(160),
  received_at timestamptz,
  decision_by varchar(160),
  decision_at timestamptz,
  reprocess_production_order_id uuid REFERENCES production_orders(id) ON DELETE RESTRICT,
  closed_by varchar(160),
  closed_at timestamptz,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_customer_return_status CHECK (status IN ('DRAFT','RECEIVED','UNDER_REVIEW','DECIDED','REPROCESS_CREATED','CLOSED','CANCELLED')),
  CONSTRAINT ck_customer_return_reason CHECK (reason_code IN ('COLOR_VARIATION','STAINS','DAMAGE','MEASUREMENT','FINISH','CLIENT_REQUEST','OTHER')),
  CONSTRAINT ck_customer_return_decision CHECK (decision IN ('PENDING','REPROCESS','REPLACEMENT','CREDIT','REJECTED')),
  CONSTRAINT ck_customer_return_totals CHECK (
    total_rolls >= 0 AND total_weight_kg >= 0 AND total_meters >= 0 AND
    return_transport_cost >= 0 AND other_cost >= 0 AND credit_amount >= 0
  )
);
CREATE INDEX IF NOT EXISTS idx_customer_returns_dispatch ON customer_returns(dispatch_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_returns_order ON customer_returns(source_production_order_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_returns_sales ON customer_returns(sales_order_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_returns_status ON customer_returns(status,return_date DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_return_open_draft_per_dispatch
  ON customer_returns(dispatch_id) WHERE status='DRAFT';
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_return_reprocess_order
  ON customer_returns(reprocess_production_order_id) WHERE reprocess_production_order_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS customer_return_lines (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_return_id uuid NOT NULL REFERENCES customer_returns(id) ON DELETE CASCADE,
  dispatch_roll_id uuid NOT NULL REFERENCES dispatch_rolls(id) ON DELETE RESTRICT,
  line_no integer NOT NULL,
  roll_code varchar(80) NOT NULL,
  dispatched_weight_kg numeric(16,3) NOT NULL,
  returned_weight_kg numeric(16,3) NOT NULL,
  returned_meters numeric(16,3) NOT NULL DEFAULT 0,
  pieces integer NOT NULL DEFAULT 1,
  condition_code varchar(30) NOT NULL DEFAULT 'OTHER',
  notes varchar(700),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_customer_return_line UNIQUE(customer_return_id,line_no),
  CONSTRAINT uq_customer_return_roll UNIQUE(customer_return_id,dispatch_roll_id),
  CONSTRAINT ck_customer_return_line_values CHECK (
    dispatched_weight_kg > 0 AND returned_weight_kg > 0 AND returned_meters >= 0 AND pieces > 0 AND returned_weight_kg <= dispatched_weight_kg
  ),
  CONSTRAINT ck_customer_return_condition CHECK (condition_code IN ('GOOD','COLOR_VARIATION','STAINS','DAMAGE','MEASUREMENT','FINISH','OTHER'))
);
CREATE INDEX IF NOT EXISTS idx_customer_return_lines_return ON customer_return_lines(customer_return_id,line_no);
CREATE INDEX IF NOT EXISTS idx_customer_return_lines_dispatch_roll ON customer_return_lines(dispatch_roll_id);

CREATE TABLE IF NOT EXISTS customer_return_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_return_id uuid NOT NULL REFERENCES customer_returns(id) ON DELETE CASCADE,
  event_type varchar(40) NOT NULL,
  from_status varchar(24),
  to_status varchar(24),
  comment varchar(700),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_customer_return_events_return ON customer_return_events(customer_return_id,created_at);

ALTER TABLE production_orders
  ADD COLUMN IF NOT EXISTS source_return_id uuid REFERENCES customer_returns(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_order_source_return
  ON production_orders(source_return_id) WHERE source_return_id IS NOT NULL AND status <> 'CANCELLED';

INSERT INTO permissions(code,name) VALUES
('RETURNS_READ','Consultar devoluciones post-despacho y reclamos'),
('RETURNS_WRITE','Registrar y recepcionar devoluciones del cliente'),
('RETURNS_DECIDE','Evaluar y decidir resolución de devoluciones'),
('RETURNS_REPROCESS','Crear órdenes de reproceso desde devoluciones')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('RETURNS_READ','RETURNS_WRITE','RETURNS_DECIDE','RETURNS_REPROCESS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('RETURNS_READ','RETURNS_WRITE')
WHERE r.code='DISPATCH'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('RETURNS_READ','RETURNS_DECIDE')
WHERE r.code='QUALITY'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('RETURNS_READ','RETURNS_REPROCESS')
WHERE r.code='PLANNING'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='RETURNS_READ'
WHERE r.code IN ('PRODUCTION','SALES')
ON CONFLICT DO NOTHING;

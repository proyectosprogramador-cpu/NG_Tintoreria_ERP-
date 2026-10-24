CREATE SEQUENCE quotation_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE sales_order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE quotations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quote_number varchar(30) NOT NULL UNIQUE,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'DRAFT',
  currency varchar(3) NOT NULL DEFAULT 'PEN',
  valid_until date,
  requested_date date,
  payment_terms varchar(180),
  tax_rate numeric(8,3) NOT NULL DEFAULT 18,
  discount_pct numeric(8,3) NOT NULL DEFAULT 0,
  subtotal numeric(16,2) NOT NULL DEFAULT 0,
  discount_amount numeric(16,2) NOT NULL DEFAULT 0,
  tax_amount numeric(16,2) NOT NULL DEFAULT 0,
  total numeric(16,2) NOT NULL DEFAULT 0,
  notes text,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  sent_at timestamptz,
  approved_at timestamptz,
  CONSTRAINT ck_quotations_status CHECK (status IN ('DRAFT','IN_REVIEW','SENT','APPROVED','REJECTED','EXPIRED','CANCELLED')),
  CONSTRAINT ck_quotations_amounts CHECK (tax_rate >= 0 AND tax_rate <= 100 AND discount_pct >= 0 AND discount_pct <= 100)
);
CREATE INDEX idx_quotations_client ON quotations(client_id);
CREATE INDEX idx_quotations_status ON quotations(status);
CREATE INDEX idx_quotations_created_at ON quotations(created_at DESC);

CREATE TABLE quotation_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quotation_id uuid NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
  line_no integer NOT NULL,
  fabric_id uuid NOT NULL REFERENCES fabrics(id) ON DELETE RESTRICT,
  color_id uuid NOT NULL REFERENCES dye_colors(id) ON DELETE RESTRICT,
  process_id uuid NOT NULL REFERENCES process_types(id) ON DELETE RESTRICT,
  finish_id uuid REFERENCES finish_types(id) ON DELETE RESTRICT,
  formula_id uuid REFERENCES formulas(id) ON DELETE RESTRICT,
  description varchar(500),
  quantity numeric(14,3) NOT NULL,
  billable_quantity numeric(14,3) NOT NULL,
  unit varchar(20) NOT NULL,
  unit_price numeric(14,4) NOT NULL,
  surcharge_pct numeric(8,3) NOT NULL DEFAULT 0,
  discount_pct numeric(8,3) NOT NULL DEFAULT 0,
  line_subtotal numeric(16,2) NOT NULL,
  line_total numeric(16,2) NOT NULL,
  price_source varchar(180),
  CONSTRAINT uq_quotation_line UNIQUE(quotation_id,line_no),
  CONSTRAINT ck_quotation_item_values CHECK (quantity > 0 AND billable_quantity >= quantity AND unit_price >= 0 AND surcharge_pct >= 0 AND discount_pct >= 0 AND discount_pct <= 100)
);
CREATE INDEX idx_quotation_items_quote ON quotation_items(quotation_id);

CREATE TABLE quotation_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quotation_id uuid NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status varchar(30) NOT NULL,
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_quotation_events_quote ON quotation_events(quotation_id,created_at);

CREATE TABLE quotation_deliveries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quotation_id uuid NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
  channel varchar(20) NOT NULL,
  destination varchar(180) NOT NULL,
  detail varchar(500),
  sent_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT ck_quotation_delivery_channel CHECK (channel IN ('WHATSAPP','EMAIL','PRINT','OTHER'))
);
CREATE INDEX idx_quotation_deliveries_quote ON quotation_deliveries(quotation_id,created_at);

CREATE TABLE sales_orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_number varchar(30) NOT NULL UNIQUE,
  quotation_id uuid NOT NULL UNIQUE REFERENCES quotations(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'NEW',
  priority varchar(20) NOT NULL DEFAULT 'NORMAL',
  requested_date date,
  commercial_notes text,
  total numeric(16,2) NOT NULL DEFAULT 0,
  currency varchar(3) NOT NULL DEFAULT 'PEN',
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  confirmed_at timestamptz,
  CONSTRAINT ck_sales_orders_status CHECK (status IN ('NEW','CONFIRMED','ON_HOLD','READY_FOR_RECEPTION','CANCELLED')),
  CONSTRAINT ck_sales_orders_priority CHECK (priority IN ('NORMAL','HIGH','URGENT'))
);
CREATE INDEX idx_sales_orders_client ON sales_orders(client_id);
CREATE INDEX idx_sales_orders_status ON sales_orders(status);

CREATE TABLE sales_order_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
  quotation_item_id uuid NOT NULL REFERENCES quotation_items(id) ON DELETE RESTRICT,
  line_no integer NOT NULL,
  fabric_id uuid NOT NULL REFERENCES fabrics(id) ON DELETE RESTRICT,
  color_id uuid NOT NULL REFERENCES dye_colors(id) ON DELETE RESTRICT,
  process_id uuid NOT NULL REFERENCES process_types(id) ON DELETE RESTRICT,
  finish_id uuid REFERENCES finish_types(id) ON DELETE RESTRICT,
  formula_id uuid REFERENCES formulas(id) ON DELETE RESTRICT,
  description varchar(500),
  quantity numeric(14,3) NOT NULL,
  billable_quantity numeric(14,3) NOT NULL,
  unit varchar(20) NOT NULL,
  unit_price numeric(14,4) NOT NULL,
  line_total numeric(16,2) NOT NULL,
  CONSTRAINT uq_sales_order_line UNIQUE(sales_order_id,line_no)
);
CREATE INDEX idx_sales_order_items_order ON sales_order_items(sales_order_id);

CREATE TABLE sales_order_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status varchar(30) NOT NULL,
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_sales_order_events_order ON sales_order_events(sales_order_id,created_at);

INSERT INTO permissions(code,name) VALUES
('QUOTES_READ','Consultar cotizaciones y proformas'),
('QUOTES_WRITE','Crear y editar cotizaciones'),
('QUOTES_APPROVE','Aprobar cotizaciones y descuentos excepcionales'),
('ORDERS_READ','Consultar pedidos'),
('ORDERS_WRITE','Gestionar pedidos')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('QUOTES_READ','QUOTES_WRITE','QUOTES_APPROVE','ORDERS_READ','ORDERS_WRITE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('QUOTES_READ','QUOTES_WRITE','ORDERS_READ','ORDERS_WRITE')
WHERE r.code='SALES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('QUOTES_READ','QUOTES_APPROVE','ORDERS_READ')
WHERE r.code IN ('FINANCE','BILLING')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('ORDERS_READ','ORDERS_WRITE')
WHERE r.code='PLANNING'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='ORDERS_READ'
WHERE r.code IN ('PRODUCTION','WAREHOUSE','QUALITY','DISPATCH')
ON CONFLICT DO NOTHING;

ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS address varchar(250);

CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS purchase_receipt_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE purchase_orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_number varchar(30) NOT NULL UNIQUE,
  purchase_request_id uuid REFERENCES purchase_requests(id) ON DELETE RESTRICT,
  supplier_id uuid REFERENCES suppliers(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'DRAFT',
  order_date date NOT NULL DEFAULT current_date,
  expected_date date,
  currency varchar(3) NOT NULL DEFAULT 'PEN',
  payment_terms varchar(120),
  delivery_address varchar(250),
  notes varchar(1000),
  subtotal numeric(18,4) NOT NULL DEFAULT 0,
  discount numeric(18,4) NOT NULL DEFAULT 0,
  igv_rate numeric(8,4) NOT NULL DEFAULT 18,
  igv_amount numeric(18,4) NOT NULL DEFAULT 0,
  total numeric(18,4) NOT NULL DEFAULT 0,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  issued_by varchar(160),
  issued_at timestamptz,
  sent_at timestamptz,
  closed_at timestamptz,
  version_no integer NOT NULL DEFAULT 1,
  CONSTRAINT ck_purchase_order_status CHECK (status IN ('DRAFT','ISSUED','SENT','PARTIALLY_RECEIVED','RECEIVED','CLOSED','CANCELLED')),
  CONSTRAINT ck_purchase_order_currency CHECK (currency IN ('PEN','USD')),
  CONSTRAINT ck_purchase_order_amounts CHECK (subtotal >= 0 AND discount >= 0 AND igv_rate >= 0 AND igv_amount >= 0 AND total >= 0)
);
CREATE INDEX idx_purchase_orders_status_date ON purchase_orders(status,order_date DESC);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders(supplier_id,order_date DESC);
CREATE INDEX idx_purchase_orders_request ON purchase_orders(purchase_request_id);

CREATE TABLE purchase_order_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  purchase_order_id uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
  purchase_request_item_id uuid REFERENCES purchase_request_items(id) ON DELETE RESTRICT,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  item_code varchar(60) NOT NULL,
  item_name varchar(180) NOT NULL,
  quantity numeric(18,6) NOT NULL,
  unit varchar(20) NOT NULL DEFAULT 'KG',
  unit_price numeric(18,6) NOT NULL DEFAULT 0,
  line_discount numeric(18,4) NOT NULL DEFAULT 0,
  line_total numeric(18,4) NOT NULL DEFAULT 0,
  received_quantity numeric(18,6) NOT NULL DEFAULT 0,
  line_no integer NOT NULL,
  CONSTRAINT uq_purchase_order_line UNIQUE(purchase_order_id,line_no),
  CONSTRAINT ck_purchase_order_item_qty CHECK (quantity > 0 AND received_quantity >= 0 AND received_quantity <= quantity),
  CONSTRAINT ck_purchase_order_item_price CHECK (unit_price >= 0 AND line_discount >= 0 AND line_total >= 0)
);
CREATE INDEX idx_purchase_order_items_item ON purchase_order_items(inventory_item_id);

CREATE TABLE purchase_receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  receipt_number varchar(30) NOT NULL UNIQUE,
  purchase_order_id uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE RESTRICT,
  supplier_id uuid NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
  received_at timestamptz NOT NULL DEFAULT now(),
  supplier_document_type varchar(30),
  supplier_document_number varchar(80),
  notes varchar(500),
  received_by varchar(160) NOT NULL
);
CREATE INDEX idx_purchase_receipts_order ON purchase_receipts(purchase_order_id,received_at DESC);

CREATE TABLE purchase_receipt_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  purchase_receipt_id uuid NOT NULL REFERENCES purchase_receipts(id) ON DELETE CASCADE,
  purchase_order_item_id uuid NOT NULL REFERENCES purchase_order_items(id) ON DELETE RESTRICT,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  quantity numeric(18,6) NOT NULL,
  lot_code varchar(80) NOT NULL,
  unit_cost numeric(18,6),
  CONSTRAINT ck_purchase_receipt_item_qty CHECK (quantity > 0),
  CONSTRAINT ck_purchase_receipt_item_cost CHECK (unit_cost IS NULL OR unit_cost >= 0)
);

INSERT INTO permissions(code,name) VALUES
('PURCHASE_ORDERS_READ','Consultar órdenes de compra'),
('PURCHASE_ORDERS_WRITE','Crear y editar órdenes de compra'),
('PURCHASE_ORDERS_APPROVE','Emitir y aprobar órdenes de compra'),
('PURCHASE_RECEIPTS','Registrar recepciones contra órdenes de compra'),
('REPORTS_READ','Consultar reportes'),
('REPORTS_EXPORT','Exportar reportes Excel y PDF')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('PURCHASE_ORDERS_READ','PURCHASE_ORDERS_WRITE','PURCHASE_ORDERS_APPROVE','PURCHASE_RECEIPTS','REPORTS_READ','REPORTS_EXPORT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('PURCHASE_ORDERS_READ','PURCHASE_ORDERS_WRITE','PURCHASE_ORDERS_APPROVE','PURCHASE_RECEIPTS','REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='PURCHASES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('PURCHASE_ORDERS_READ','PURCHASE_RECEIPTS','REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='WAREHOUSE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('REPORTS_READ','REPORTS_EXPORT')
WHERE r.code IN ('PLANNING','FINANCE')
ON CONFLICT DO NOTHING;

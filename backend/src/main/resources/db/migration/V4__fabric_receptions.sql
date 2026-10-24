CREATE SEQUENCE reception_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE reception_roll_number_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE sales_orders DROP CONSTRAINT ck_sales_orders_status;
ALTER TABLE sales_orders ADD CONSTRAINT ck_sales_orders_status CHECK (
  status IN ('NEW','CONFIRMED','ON_HOLD','READY_FOR_RECEPTION','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')
);

CREATE TABLE fabric_receptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reception_number varchar(30) NOT NULL UNIQUE,
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'DRAFT',
  received_date date NOT NULL,
  client_guide_number varchar(80),
  vehicle_plate varchar(20),
  delivered_by varchar(180),
  received_by varchar(180),
  warehouse_location varchar(120),
  notes text,
  roll_count integer NOT NULL DEFAULT 0,
  total_declared_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_gross_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_tare_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_net_kg numeric(16,3) NOT NULL DEFAULT 0,
  total_meters numeric(16,3) NOT NULL DEFAULT 0,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  released_at timestamptz,
  CONSTRAINT ck_fabric_reception_status CHECK (status IN ('DRAFT','IN_INSPECTION','OBSERVED','RELEASED','CANCELLED')),
  CONSTRAINT ck_fabric_reception_totals CHECK (
    roll_count >= 0 AND total_declared_kg >= 0 AND total_gross_kg >= 0 AND
    total_tare_kg >= 0 AND total_net_kg >= 0 AND total_meters >= 0
  )
);
CREATE INDEX idx_fabric_receptions_order ON fabric_receptions(sales_order_id);
CREATE INDEX idx_fabric_receptions_client ON fabric_receptions(client_id);
CREATE INDEX idx_fabric_receptions_status ON fabric_receptions(status);
CREATE INDEX idx_fabric_receptions_date ON fabric_receptions(received_date DESC);

CREATE TABLE reception_rolls (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reception_id uuid NOT NULL REFERENCES fabric_receptions(id) ON DELETE CASCADE,
  sales_order_item_id uuid NOT NULL REFERENCES sales_order_items(id) ON DELETE RESTRICT,
  line_no integer NOT NULL,
  roll_code varchar(40) NOT NULL UNIQUE,
  client_roll_code varchar(80),
  fabric_id uuid NOT NULL REFERENCES fabrics(id) ON DELETE RESTRICT,
  ownership varchar(20) NOT NULL DEFAULT 'CLIENT',
  declared_kg numeric(14,3) NOT NULL DEFAULT 0,
  gross_kg numeric(14,3) NOT NULL DEFAULT 0,
  tare_kg numeric(14,3) NOT NULL DEFAULT 0,
  net_kg numeric(14,3) NOT NULL DEFAULT 0,
  meters numeric(14,3) NOT NULL DEFAULT 0,
  width_cm numeric(10,2),
  gsm numeric(10,2),
  inspection_status varchar(20) NOT NULL DEFAULT 'PENDING',
  defect_description varchar(500),
  observations varchar(500),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_reception_roll_line UNIQUE(reception_id,line_no),
  CONSTRAINT ck_reception_roll_ownership CHECK (ownership IN ('CLIENT','COMPANY','THIRD_PARTY')),
  CONSTRAINT ck_reception_roll_inspection CHECK (inspection_status IN ('PENDING','INSPECTED','OBSERVED','REJECTED')),
  CONSTRAINT ck_reception_roll_measurements CHECK (
    declared_kg >= 0 AND gross_kg >= 0 AND tare_kg >= 0 AND net_kg >= 0 AND
    meters >= 0 AND gross_kg >= tare_kg AND (width_cm IS NULL OR width_cm > 0) AND
    (gsm IS NULL OR gsm > 0)
  )
);
CREATE INDEX idx_reception_rolls_reception ON reception_rolls(reception_id);
CREATE INDEX idx_reception_rolls_order_item ON reception_rolls(sales_order_item_id);
CREATE INDEX idx_reception_rolls_inspection ON reception_rolls(inspection_status);

CREATE TABLE reception_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reception_id uuid NOT NULL REFERENCES fabric_receptions(id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status varchar(30) NOT NULL,
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_reception_events_reception ON reception_events(reception_id,created_at);

CREATE TABLE reception_attachments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reception_id uuid NOT NULL REFERENCES fabric_receptions(id) ON DELETE CASCADE,
  roll_id uuid REFERENCES reception_rolls(id) ON DELETE RESTRICT,
  original_name varchar(255) NOT NULL,
  content_type varchar(100) NOT NULL,
  size_bytes bigint NOT NULL,
  storage_name varchar(255) NOT NULL UNIQUE,
  uploaded_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT ck_reception_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);
CREATE INDEX idx_reception_attachments_reception ON reception_attachments(reception_id,created_at);
CREATE INDEX idx_reception_attachments_roll ON reception_attachments(roll_id);

INSERT INTO permissions(code,name) VALUES
('RECEPTIONS_READ','Consultar recepciones de tela'),
('RECEPTIONS_WRITE','Registrar e inspeccionar rollos'),
('RECEPTIONS_RELEASE','Liberar recepciones inspeccionadas')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('RECEPTIONS_READ','RECEPTIONS_WRITE','RECEPTIONS_RELEASE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('RECEPTIONS_READ','RECEPTIONS_WRITE','RECEPTIONS_RELEASE')
WHERE r.code='WAREHOUSE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='RECEPTIONS_READ'
WHERE r.code IN ('SALES','LAB','PLANNING','PRODUCTION','QUALITY','DISPATCH')
ON CONFLICT DO NOTHING;

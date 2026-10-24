CREATE SEQUENCE production_order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE production_orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_number varchar(30) NOT NULL UNIQUE,
  batch_number varchar(40) NOT NULL UNIQUE,
  lab_request_id uuid NOT NULL REFERENCES lab_requests(id) ON DELETE RESTRICT,
  lab_recipe_version_id uuid NOT NULL REFERENCES lab_recipe_versions(id) ON DELETE RESTRICT,
  formula_id uuid NOT NULL REFERENCES formulas(id) ON DELETE RESTRICT,
  formula_version_id uuid NOT NULL REFERENCES formula_versions(id) ON DELETE RESTRICT,
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE RESTRICT,
  sales_order_item_id uuid NOT NULL REFERENCES sales_order_items(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  machine_id uuid NOT NULL REFERENCES machines(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'PLANNED',
  material_status varchar(20) NOT NULL DEFAULT 'PENDING',
  priority varchar(20) NOT NULL DEFAULT 'NORMAL',
  shift_code varchar(20) NOT NULL DEFAULT 'MORNING',
  operator_name varchar(160),
  color_family varchar(100),
  batch_weight_kg numeric(16,3) NOT NULL,
  bath_volume_l numeric(16,3) NOT NULL,
  scheduled_start timestamptz NOT NULL,
  scheduled_end timestamptz NOT NULL,
  estimated_minutes integer NOT NULL,
  setup_minutes integer NOT NULL DEFAULT 0,
  cleaning_minutes integer NOT NULL DEFAULT 0,
  actual_start timestamptz,
  actual_end timestamptz,
  notes text,
  pause_reason varchar(500),
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_production_order_status CHECK (status IN ('PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','CANCELLED')),
  CONSTRAINT ck_production_material_status CHECK (material_status IN ('PENDING','AVAILABLE','SHORTAGE','RESERVED')),
  CONSTRAINT ck_production_priority CHECK (priority IN ('NORMAL','HIGH','URGENT')),
  CONSTRAINT ck_production_shift CHECK (shift_code IN ('MORNING','AFTERNOON','NIGHT','CUSTOM')),
  CONSTRAINT ck_production_values CHECK (
    batch_weight_kg > 0 AND bath_volume_l > 0 AND estimated_minutes > 0 AND
    setup_minutes >= 0 AND cleaning_minutes >= 0 AND scheduled_end > scheduled_start
  )
);
CREATE INDEX idx_production_orders_machine_schedule ON production_orders(machine_id,scheduled_start,scheduled_end);
CREATE INDEX idx_production_orders_status ON production_orders(status);
CREATE INDEX idx_production_orders_order_item ON production_orders(sales_order_item_id);
CREATE INDEX idx_production_orders_lab_request ON production_orders(lab_request_id);

CREATE TABLE production_order_rolls (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  reception_roll_id uuid NOT NULL REFERENCES reception_rolls(id) ON DELETE RESTRICT,
  sequence_no integer NOT NULL,
  net_kg numeric(14,3) NOT NULL DEFAULT 0,
  meters numeric(14,3) NOT NULL DEFAULT 0,
  CONSTRAINT uq_production_order_roll UNIQUE(production_order_id,reception_roll_id),
  CONSTRAINT ck_production_roll_values CHECK (net_kg >= 0 AND meters >= 0)
);
CREATE INDEX idx_production_roll_order ON production_order_rolls(production_order_id,sequence_no);
CREATE INDEX idx_production_roll_source ON production_order_rolls(reception_roll_id);

CREATE TABLE production_material_requirements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  line_no integer NOT NULL,
  material_code varchar(60),
  material_name varchar(180) NOT NULL,
  dosage numeric(14,6) NOT NULL,
  dosage_unit varchar(20) NOT NULL,
  planned_quantity_kg numeric(16,6) NOT NULL,
  available_quantity_kg numeric(16,6),
  shortage_quantity_kg numeric(16,6) NOT NULL DEFAULT 0,
  stock_status varchar(20) NOT NULL DEFAULT 'PENDING',
  warehouse_comment varchar(500),
  checked_by varchar(160),
  checked_at timestamptz,
  CONSTRAINT uq_production_material_line UNIQUE(production_order_id,line_no),
  CONSTRAINT ck_production_material_values CHECK (
    dosage > 0 AND planned_quantity_kg >= 0 AND
    (available_quantity_kg IS NULL OR available_quantity_kg >= 0) AND shortage_quantity_kg >= 0
  ),
  CONSTRAINT ck_production_material_unit CHECK (dosage_unit IN ('G_L','PCT_OWF')),
  CONSTRAINT ck_production_stock_status CHECK (stock_status IN ('PENDING','AVAILABLE','SHORTAGE','RESERVED'))
);
CREATE INDEX idx_production_material_order ON production_material_requirements(production_order_id,line_no);

CREATE TABLE production_operations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  sequence_no integer NOT NULL,
  operation_code varchar(40) NOT NULL,
  operation_name varchar(180) NOT NULL,
  planned_minutes integer NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  operator_name varchar(160),
  actual_start timestamptz,
  actual_end timestamptz,
  notes varchar(500),
  CONSTRAINT uq_production_operation_sequence UNIQUE(production_order_id,sequence_no),
  CONSTRAINT ck_production_operation_minutes CHECK (planned_minutes > 0),
  CONSTRAINT ck_production_operation_status CHECK (status IN ('PENDING','IN_PROCESS','COMPLETED','SKIPPED'))
);
CREATE INDEX idx_production_operation_order ON production_operations(production_order_id,sequence_no);

CREATE TABLE production_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  event_type varchar(40) NOT NULL,
  from_status varchar(30),
  to_status varchar(30),
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_production_events_order ON production_events(production_order_id,created_at);

INSERT INTO permissions(code,name) VALUES
('PRODUCTION_ORDERS_READ','Consultar programación y órdenes de producción'),
('PRODUCTION_PLANNING','Programar, reprogramar y cancelar órdenes'),
('PRODUCTION_EXECUTE','Iniciar, pausar y ejecutar operaciones de producción'),
('PRODUCTION_MATERIALS_CHECK','Verificar disponibilidad de materiales para producción')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('PRODUCTION_ORDERS_READ','PRODUCTION_PLANNING','PRODUCTION_EXECUTE','PRODUCTION_MATERIALS_CHECK')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('PRODUCTION_ORDERS_READ','PRODUCTION_PLANNING')
WHERE r.code='PLANNING'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('PRODUCTION_ORDERS_READ','PRODUCTION_EXECUTE')
WHERE r.code='PRODUCTION'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('PRODUCTION_ORDERS_READ','PRODUCTION_MATERIALS_CHECK')
WHERE r.code='WAREHOUSE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='PRODUCTION_ORDERS_READ'
WHERE r.code IN ('LAB','QUALITY','SALES')
ON CONFLICT DO NOTHING;

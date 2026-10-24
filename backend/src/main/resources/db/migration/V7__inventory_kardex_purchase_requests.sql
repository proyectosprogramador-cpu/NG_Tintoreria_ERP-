CREATE SEQUENCE purchase_request_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE warehouses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(40) NOT NULL UNIQUE,
  name varchar(160) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  default_warehouse boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO warehouses(code,name,default_warehouse)
VALUES ('MAIN','Almacén principal',true)
ON CONFLICT(code) DO NOTHING;

CREATE TABLE inventory_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(60) NOT NULL UNIQUE,
  name varchar(180) NOT NULL,
  base_unit varchar(20) NOT NULL DEFAULT 'KG',
  minimum_stock numeric(16,6) NOT NULL DEFAULT 0,
  safety_stock numeric(16,6) NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_inventory_item_stock CHECK (minimum_stock >= 0 AND safety_stock >= 0)
);
CREATE INDEX idx_inventory_items_name ON inventory_items(lower(name));

CREATE TABLE inventory_lots (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  warehouse_id uuid NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  supplier_id uuid REFERENCES suppliers(id) ON DELETE RESTRICT,
  lot_code varchar(80) NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  expires_at date,
  unit_cost numeric(16,6),
  current_quantity numeric(18,6) NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_inventory_lot UNIQUE(warehouse_id,inventory_item_id,lot_code),
  CONSTRAINT ck_inventory_lot_qty CHECK (current_quantity >= 0),
  CONSTRAINT ck_inventory_lot_cost CHECK (unit_cost IS NULL OR unit_cost >= 0)
);
CREATE INDEX idx_inventory_lots_item ON inventory_lots(inventory_item_id,warehouse_id,received_at);

CREATE TABLE inventory_movements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  warehouse_id uuid NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  inventory_lot_id uuid REFERENCES inventory_lots(id) ON DELETE RESTRICT,
  movement_type varchar(30) NOT NULL,
  quantity numeric(18,6) NOT NULL,
  balance_after numeric(18,6) NOT NULL,
  reference_type varchar(40),
  reference_id uuid,
  reference_number varchar(80),
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_inventory_movement_qty CHECK (quantity <> 0),
  CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN (
    'INITIAL_STOCK','PURCHASE_RECEIPT','ADJUSTMENT_IN','ADJUSTMENT_OUT',
    'PRODUCTION_ISSUE','PRODUCTION_RETURN','TRANSFER_IN','TRANSFER_OUT'
  ))
);
CREATE INDEX idx_inventory_movements_item_date ON inventory_movements(inventory_item_id,created_at DESC);
CREATE INDEX idx_inventory_movements_ref ON inventory_movements(reference_type,reference_id);

ALTER TABLE production_material_requirements
  ADD COLUMN inventory_item_id uuid REFERENCES inventory_items(id) ON DELETE RESTRICT;
CREATE INDEX idx_production_material_inventory_item ON production_material_requirements(inventory_item_id);

CREATE TABLE inventory_reservations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  production_material_requirement_id uuid NOT NULL REFERENCES production_material_requirements(id) ON DELETE CASCADE,
  warehouse_id uuid NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  quantity numeric(18,6) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  released_at timestamptz,
  release_reason varchar(300),
  CONSTRAINT ck_inventory_reservation_qty CHECK (quantity > 0),
  CONSTRAINT ck_inventory_reservation_status CHECK (status IN ('ACTIVE','CONSUMED','RELEASED'))
);
CREATE INDEX idx_inventory_res_active_item ON inventory_reservations(inventory_item_id,status);
CREATE INDEX idx_inventory_res_order ON inventory_reservations(production_order_id,status);

CREATE TABLE purchase_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  request_number varchar(30) NOT NULL UNIQUE,
  production_order_id uuid REFERENCES production_orders(id) ON DELETE RESTRICT,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  priority varchar(20) NOT NULL DEFAULT 'NORMAL',
  notes varchar(500),
  requested_by varchar(160) NOT NULL,
  requested_at timestamptz NOT NULL DEFAULT now(),
  approved_by varchar(160),
  approved_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_purchase_request_status CHECK (status IN ('DRAFT','REQUESTED','APPROVED','CANCELLED')),
  CONSTRAINT ck_purchase_request_priority CHECK (priority IN ('NORMAL','HIGH','URGENT'))
);
CREATE INDEX idx_purchase_requests_status ON purchase_requests(status,requested_at DESC);
CREATE INDEX idx_purchase_requests_order ON purchase_requests(production_order_id);
CREATE UNIQUE INDEX uq_purchase_request_active_order ON purchase_requests(production_order_id)
  WHERE production_order_id IS NOT NULL AND status IN ('DRAFT','REQUESTED','APPROVED');

CREATE TABLE purchase_request_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  purchase_request_id uuid NOT NULL REFERENCES purchase_requests(id) ON DELETE CASCADE,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  material_code varchar(60) NOT NULL,
  material_name varchar(180) NOT NULL,
  required_quantity numeric(18,6) NOT NULL,
  shortage_quantity numeric(18,6) NOT NULL,
  requested_quantity numeric(18,6) NOT NULL,
  unit varchar(20) NOT NULL DEFAULT 'KG',
  line_no integer NOT NULL,
  CONSTRAINT uq_purchase_request_line UNIQUE(purchase_request_id,line_no),
  CONSTRAINT ck_purchase_request_item_qty CHECK (
    required_quantity >= 0 AND shortage_quantity >= 0 AND requested_quantity > 0
  )
);

WITH raw_materials AS (
  SELECT material_code, material_name FROM formula_components
  UNION
  SELECT material_code, material_name FROM lab_recipe_components
  UNION
  SELECT material_code, material_name FROM production_material_requirements
), normalized AS (
  SELECT
    COALESCE(NULLIF(trim(material_code),''), 'AUTO-' || upper(substr(md5(lower(trim(material_name))),1,8))) AS code,
    trim(material_name) AS name
  FROM raw_materials
  WHERE material_name IS NOT NULL AND trim(material_name) <> ''
), dedup AS (
  SELECT code, min(name) AS name FROM normalized GROUP BY code
)
INSERT INTO inventory_items(code,name)
SELECT code,name FROM dedup
ON CONFLICT(code) DO NOTHING;

UPDATE production_material_requirements pmr
SET inventory_item_id = (
  SELECT ii.id
  FROM inventory_items ii
  WHERE lower(ii.code)=lower(COALESCE(NULLIF(trim(pmr.material_code),''), 'AUTO-' || upper(substr(md5(lower(trim(pmr.material_name))),1,8))))
     OR lower(ii.name)=lower(trim(pmr.material_name))
  ORDER BY CASE WHEN lower(ii.code)=lower(COALESCE(NULLIF(trim(pmr.material_code),''), 'AUTO-' || upper(substr(md5(lower(trim(pmr.material_name))),1,8)))) THEN 0 ELSE 1 END
  LIMIT 1
)
WHERE pmr.inventory_item_id IS NULL;

-- v0.9.7 permitía una verificación manual. Las OP aún planificadas deben volver a validar
-- contra existencias reales de v0.9.8 antes de poder iniciar producción.
UPDATE production_material_requirements pmr
SET available_quantity_kg=NULL, shortage_quantity_kg=0, stock_status='PENDING',
    warehouse_comment='Pendiente de reserva contra stock real v0.9.8', checked_by=NULL, checked_at=NULL
FROM production_orders po
WHERE po.id=pmr.production_order_id AND po.status='PLANNED';

UPDATE production_orders
SET material_status='PENDING', version_no=version_no+1, updated_at=now()
WHERE status='PLANNED';

INSERT INTO permissions(code,name) VALUES
('INVENTORY_READ','Consultar existencias, kardex y reservas'),
('INVENTORY_MOVEMENTS','Registrar entradas, salidas y ajustes de almacén'),
('INVENTORY_RESERVE','Reservar existencias para órdenes de producción'),
('PURCHASE_REQUESTS_READ','Consultar solicitudes de compra'),
('PURCHASE_REQUESTS_WRITE','Crear y editar solicitudes de compra'),
('PURCHASE_REQUESTS_APPROVE','Aprobar solicitudes de compra')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN (
  'INVENTORY_READ','INVENTORY_MOVEMENTS','INVENTORY_RESERVE',
  'PURCHASE_REQUESTS_READ','PURCHASE_REQUESTS_WRITE','PURCHASE_REQUESTS_APPROVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN (
  'INVENTORY_READ','INVENTORY_MOVEMENTS','INVENTORY_RESERVE',
  'PURCHASE_REQUESTS_READ','PURCHASE_REQUESTS_WRITE'
) WHERE r.code='WAREHOUSE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN (
  'INVENTORY_READ','PURCHASE_REQUESTS_READ','PURCHASE_REQUESTS_WRITE','PURCHASE_REQUESTS_APPROVE'
) WHERE r.code='PURCHASES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='INVENTORY_READ'
WHERE r.code IN ('PLANNING','PRODUCTION','LAB','QUALITY')
ON CONFLICT DO NOTHING;

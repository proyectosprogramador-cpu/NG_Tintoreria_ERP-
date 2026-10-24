ALTER TABLE production_orders
  ADD COLUMN actual_output_weight_kg numeric(16,3),
  ADD COLUMN reprocess_weight_kg numeric(16,3) NOT NULL DEFAULT 0,
  ADD COLUMN waste_weight_kg numeric(16,3) NOT NULL DEFAULT 0,
  ADD COLUMN water_consumption_l numeric(16,3),
  ADD COLUMN energy_kwh numeric(16,3),
  ADD COLUMN execution_notes text;

ALTER TABLE production_orders DROP CONSTRAINT ck_production_material_status;
ALTER TABLE production_orders
  ADD CONSTRAINT ck_production_material_status CHECK (material_status IN ('PENDING','AVAILABLE','SHORTAGE','RESERVED','CONSUMED'));

ALTER TABLE production_orders
  ADD CONSTRAINT ck_production_actual_values CHECK (
    (actual_output_weight_kg IS NULL OR actual_output_weight_kg >= 0) AND
    reprocess_weight_kg >= 0 AND waste_weight_kg >= 0 AND
    (water_consumption_l IS NULL OR water_consumption_l >= 0) AND
    (energy_kwh IS NULL OR energy_kwh >= 0)
  );

ALTER TABLE production_material_requirements
  ADD COLUMN actual_quantity_kg numeric(16,6) NOT NULL DEFAULT 0,
  ADD COLUMN consumption_variance_kg numeric(16,6) NOT NULL DEFAULT 0,
  ADD COLUMN consumption_confirmed boolean NOT NULL DEFAULT false,
  ADD COLUMN consumption_confirmed_by varchar(160),
  ADD COLUMN consumption_confirmed_at timestamptz;

ALTER TABLE production_material_requirements DROP CONSTRAINT ck_production_stock_status;
ALTER TABLE production_material_requirements
  ADD CONSTRAINT ck_production_stock_status CHECK (stock_status IN ('PENDING','AVAILABLE','SHORTAGE','RESERVED','CONSUMED'));
ALTER TABLE production_material_requirements
  ADD CONSTRAINT ck_production_actual_qty CHECK (actual_quantity_kg >= 0);

CREATE TABLE production_material_consumptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  production_material_requirement_id uuid NOT NULL REFERENCES production_material_requirements(id) ON DELETE CASCADE,
  inventory_item_id uuid NOT NULL REFERENCES inventory_items(id) ON DELETE RESTRICT,
  movement_kind varchar(20) NOT NULL,
  quantity_kg numeric(18,6) NOT NULL,
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_production_consumption_kind CHECK (movement_kind IN ('ISSUE','RETURN')),
  CONSTRAINT ck_production_consumption_qty CHECK (quantity_kg > 0)
);
CREATE INDEX idx_production_consumption_order ON production_material_consumptions(production_order_id,created_at DESC);
CREATE INDEX idx_production_consumption_requirement ON production_material_consumptions(production_material_requirement_id,created_at DESC);

CREATE TABLE production_incidents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE CASCADE,
  incident_type varchar(30) NOT NULL,
  severity varchar(20) NOT NULL DEFAULT 'MEDIUM',
  downtime_minutes integer NOT NULL DEFAULT 0,
  description varchar(1000) NOT NULL,
  corrective_action varchar(1000),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_production_incident_type CHECK (incident_type IN ('MACHINE','PROCESS','MATERIAL','QUALITY','SAFETY','OTHER')),
  CONSTRAINT ck_production_incident_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT ck_production_incident_downtime CHECK (downtime_minutes >= 0)
);
CREATE INDEX idx_production_incident_order ON production_incidents(production_order_id,created_at DESC);

INSERT INTO permissions(code,name) VALUES
('PRODUCTION_ACTUALS','Registrar consumos reales, producción obtenida e incidencias')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code='PRODUCTION_ACTUALS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='PRODUCTION_ACTUALS'
WHERE r.code='PRODUCTION'
ON CONFLICT DO NOTHING;

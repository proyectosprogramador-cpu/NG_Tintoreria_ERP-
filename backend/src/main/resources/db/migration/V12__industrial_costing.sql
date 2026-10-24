-- v0.10.3 - Costeo industrial real, costos por OP y margen

ALTER TABLE inventory_items
  ADD COLUMN standard_cost numeric(18,6);
ALTER TABLE inventory_items
  ADD CONSTRAINT ck_inventory_item_standard_cost CHECK (standard_cost IS NULL OR standard_cost >= 0);

ALTER TABLE machines
  ADD COLUMN cost_per_hour numeric(18,6);
ALTER TABLE machines
  ADD CONSTRAINT ck_machine_cost_per_hour CHECK (cost_per_hour IS NULL OR cost_per_hour >= 0);

CREATE TABLE industrial_cost_settings (
  id smallint PRIMARY KEY DEFAULT 1,
  base_currency varchar(3) NOT NULL DEFAULT 'PEN',
  water_cost_per_m3 numeric(18,6) NOT NULL DEFAULT 0,
  energy_cost_per_kwh numeric(18,6) NOT NULL DEFAULT 0,
  labor_cost_per_hour numeric(18,6) NOT NULL DEFAULT 0,
  default_machine_cost_per_hour numeric(18,6) NOT NULL DEFAULT 0,
  overhead_pct numeric(8,4) NOT NULL DEFAULT 0,
  usd_to_pen_rate numeric(18,6),
  updated_by varchar(160),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_industrial_cost_settings_singleton CHECK (id=1),
  CONSTRAINT ck_industrial_cost_settings_values CHECK (
    water_cost_per_m3 >= 0 AND energy_cost_per_kwh >= 0 AND labor_cost_per_hour >= 0 AND
    default_machine_cost_per_hour >= 0 AND overhead_pct >= 0 AND overhead_pct <= 100 AND
    (usd_to_pen_rate IS NULL OR usd_to_pen_rate > 0)
  )
);
INSERT INTO industrial_cost_settings(id) VALUES (1) ON CONFLICT(id) DO NOTHING;

CREATE SEQUENCE production_cost_snapshot_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE production_cost_snapshots (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  snapshot_number varchar(30) NOT NULL UNIQUE,
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE RESTRICT,
  revision_no integer NOT NULL,
  current_snapshot boolean NOT NULL DEFAULT true,
  cost_status varchar(20) NOT NULL DEFAULT 'CALCULATED',
  base_currency varchar(3) NOT NULL DEFAULT 'PEN',
  sale_currency varchar(3) NOT NULL DEFAULT 'PEN',
  exchange_rate numeric(18,6) NOT NULL DEFAULT 1,
  allocated_sale_amount_source numeric(18,4) NOT NULL DEFAULT 0,
  allocated_sale_amount_base numeric(18,4) NOT NULL DEFAULT 0,
  batch_weight_kg numeric(18,3) NOT NULL DEFAULT 0,
  actual_output_weight_kg numeric(18,3) NOT NULL DEFAULT 0,
  waste_weight_kg numeric(18,3) NOT NULL DEFAULT 0,
  reprocess_weight_kg numeric(18,3) NOT NULL DEFAULT 0,
  material_planned_cost numeric(18,4) NOT NULL DEFAULT 0,
  material_actual_cost numeric(18,4) NOT NULL DEFAULT 0,
  water_quantity_l numeric(18,3) NOT NULL DEFAULT 0,
  water_rate_per_m3 numeric(18,6) NOT NULL DEFAULT 0,
  water_cost numeric(18,4) NOT NULL DEFAULT 0,
  energy_quantity_kwh numeric(18,3) NOT NULL DEFAULT 0,
  energy_rate_per_kwh numeric(18,6) NOT NULL DEFAULT 0,
  energy_cost numeric(18,4) NOT NULL DEFAULT 0,
  machine_hours numeric(18,4) NOT NULL DEFAULT 0,
  machine_rate_per_hour numeric(18,6) NOT NULL DEFAULT 0,
  machine_cost numeric(18,4) NOT NULL DEFAULT 0,
  labor_hours numeric(18,4) NOT NULL DEFAULT 0,
  labor_count integer NOT NULL DEFAULT 1,
  labor_rate_per_hour numeric(18,6) NOT NULL DEFAULT 0,
  labor_cost numeric(18,4) NOT NULL DEFAULT 0,
  direct_cost numeric(18,4) NOT NULL DEFAULT 0,
  overhead_pct numeric(8,4) NOT NULL DEFAULT 0,
  overhead_cost numeric(18,4) NOT NULL DEFAULT 0,
  other_cost numeric(18,4) NOT NULL DEFAULT 0,
  total_cost numeric(18,4) NOT NULL DEFAULT 0,
  cost_per_output_kg numeric(18,6),
  gross_margin numeric(18,4) NOT NULL DEFAULT 0,
  margin_pct numeric(10,4),
  unvalued_material_qty numeric(18,6) NOT NULL DEFAULT 0,
  costing_notes text,
  calculated_by varchar(160) NOT NULL,
  calculated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_production_cost_revision UNIQUE(production_order_id,revision_no),
  CONSTRAINT ck_production_cost_status CHECK (cost_status IN ('CALCULATED','LOCKED')),
  CONSTRAINT ck_production_cost_values CHECK (
    revision_no > 0 AND exchange_rate > 0 AND batch_weight_kg >= 0 AND actual_output_weight_kg >= 0 AND
    waste_weight_kg >= 0 AND reprocess_weight_kg >= 0 AND material_planned_cost >= 0 AND material_actual_cost >= 0 AND
    water_quantity_l >= 0 AND water_rate_per_m3 >= 0 AND water_cost >= 0 AND energy_quantity_kwh >= 0 AND
    energy_rate_per_kwh >= 0 AND energy_cost >= 0 AND machine_hours >= 0 AND machine_rate_per_hour >= 0 AND
    machine_cost >= 0 AND labor_hours >= 0 AND labor_count > 0 AND labor_rate_per_hour >= 0 AND labor_cost >= 0 AND
    direct_cost >= 0 AND overhead_pct >= 0 AND overhead_pct <= 100 AND overhead_cost >= 0 AND other_cost >= 0 AND
    total_cost >= 0 AND (cost_per_output_kg IS NULL OR cost_per_output_kg >= 0) AND unvalued_material_qty >= 0
  )
);
CREATE UNIQUE INDEX uq_production_cost_current ON production_cost_snapshots(production_order_id) WHERE current_snapshot=true;
CREATE INDEX idx_production_cost_snapshot_order ON production_cost_snapshots(production_order_id,revision_no DESC);
CREATE INDEX idx_production_cost_snapshot_date ON production_cost_snapshots(calculated_at DESC);

CREATE TABLE production_cost_materials (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  production_cost_snapshot_id uuid NOT NULL REFERENCES production_cost_snapshots(id) ON DELETE CASCADE,
  inventory_item_id uuid REFERENCES inventory_items(id) ON DELETE RESTRICT,
  item_code varchar(60),
  item_name varchar(180) NOT NULL,
  planned_quantity_kg numeric(18,6) NOT NULL DEFAULT 0,
  actual_quantity_kg numeric(18,6) NOT NULL DEFAULT 0,
  valued_actual_quantity_kg numeric(18,6) NOT NULL DEFAULT 0,
  unvalued_actual_quantity_kg numeric(18,6) NOT NULL DEFAULT 0,
  unit_cost numeric(18,6) NOT NULL DEFAULT 0,
  planned_cost numeric(18,4) NOT NULL DEFAULT 0,
  actual_cost numeric(18,4) NOT NULL DEFAULT 0,
  cost_source varchar(40) NOT NULL DEFAULT 'NO_COST',
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_production_cost_material_values CHECK (
    planned_quantity_kg >= 0 AND actual_quantity_kg >= 0 AND valued_actual_quantity_kg >= 0 AND
    unvalued_actual_quantity_kg >= 0 AND unit_cost >= 0 AND planned_cost >= 0 AND actual_cost >= 0
  )
);
CREATE INDEX idx_production_cost_material_snapshot ON production_cost_materials(production_cost_snapshot_id,item_name);

INSERT INTO permissions(code,name) VALUES
('COSTING_READ','Consultar costeo industrial y márgenes'),
('COSTING_CALCULATE','Calcular y recalcular costos por orden de producción'),
('COSTING_CONFIG','Configurar tarifas de agua, energía, mano de obra y costos indirectos')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('COSTING_READ','COSTING_CALCULATE','COSTING_CONFIG')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COSTING_READ','COSTING_CALCULATE','COSTING_CONFIG','REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='FINANCE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='COSTING_READ'
WHERE r.code IN ('PLANNING','PRODUCTION')
ON CONFLICT DO NOTHING;

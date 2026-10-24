CREATE SEQUENCE lab_request_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE lab_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lab_number varchar(30) NOT NULL UNIQUE,
  source_reception_id uuid NOT NULL REFERENCES fabric_receptions(id) ON DELETE RESTRICT,
  sales_order_id uuid NOT NULL REFERENCES sales_orders(id) ON DELETE RESTRICT,
  sales_order_item_id uuid NOT NULL REFERENCES sales_order_items(id) ON DELETE RESTRICT,
  client_id uuid NOT NULL REFERENCES clients(id) ON DELETE RESTRICT,
  fabric_id uuid NOT NULL REFERENCES fabrics(id) ON DELETE RESTRICT,
  color_id uuid NOT NULL REFERENCES dye_colors(id) ON DELETE RESTRICT,
  process_id uuid NOT NULL REFERENCES process_types(id) ON DELETE RESTRICT,
  finish_id uuid REFERENCES finish_types(id) ON DELETE RESTRICT,
  status varchar(30) NOT NULL DEFAULT 'IN_DEVELOPMENT',
  reference_method varchar(100) NOT NULL,
  color_standard varchar(250) NOT NULL,
  illuminant varchar(30) NOT NULL DEFAULT 'D65',
  substrate varchar(250) NOT NULL,
  actual_weight_kg numeric(16,3) NOT NULL,
  observations text,
  selected_alternative varchar(1),
  released_recipe_id uuid,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  sent_at timestamptz,
  approved_at timestamptz,
  rejected_at timestamptz,
  CONSTRAINT ck_lab_request_status CHECK (status IN ('IN_DEVELOPMENT','SENT','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT ck_lab_selected_alternative CHECK (selected_alternative IS NULL OR selected_alternative IN ('A','B','C')),
  CONSTRAINT ck_lab_actual_weight CHECK (actual_weight_kg > 0)
);
CREATE INDEX idx_lab_requests_order ON lab_requests(sales_order_id);
CREATE INDEX idx_lab_requests_item ON lab_requests(sales_order_item_id);
CREATE INDEX idx_lab_requests_reception ON lab_requests(source_reception_id);
CREATE INDEX idx_lab_requests_status ON lab_requests(status);
CREATE INDEX idx_lab_requests_created ON lab_requests(created_at DESC);

CREATE TABLE lab_alternatives (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lab_request_id uuid NOT NULL REFERENCES lab_requests(id) ON DELETE CASCADE,
  alternative_code varchar(1) NOT NULL,
  l_value numeric(8,3),
  a_value numeric(8,3),
  b_value numeric(8,3),
  delta_e numeric(8,3),
  visual_result varchar(40) NOT NULL DEFAULT 'PENDING',
  notes varchar(500),
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_lab_alternative UNIQUE(lab_request_id,alternative_code),
  CONSTRAINT ck_lab_alternative_code CHECK (alternative_code IN ('A','B','C')),
  CONSTRAINT ck_lab_visual_result CHECK (visual_result IN ('PENDING','MATCH','ACCEPTABLE','OUT_OF_TOLERANCE')),
  CONSTRAINT ck_lab_delta_e CHECK (delta_e IS NULL OR delta_e >= 0)
);

CREATE TABLE lab_recipe_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lab_request_id uuid NOT NULL REFERENCES lab_requests(id) ON DELETE CASCADE,
  alternative_code varchar(1) NOT NULL,
  version_no integer NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  actual_weight_kg numeric(16,3) NOT NULL,
  bath_ratio numeric(8,3) NOT NULL,
  bath_volume_l numeric(16,3) NOT NULL,
  temperature_c numeric(8,2),
  time_minutes integer,
  ph numeric(6,2),
  curve_text text,
  change_reason varchar(500),
  formula_id uuid REFERENCES formulas(id) ON DELETE RESTRICT,
  formula_version_id uuid REFERENCES formula_versions(id) ON DELETE RESTRICT,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL,
  released_by varchar(160),
  released_at timestamptz,
  CONSTRAINT uq_lab_recipe_version UNIQUE(lab_request_id,alternative_code,version_no),
  CONSTRAINT ck_lab_recipe_alternative CHECK (alternative_code IN ('A','B','C')),
  CONSTRAINT ck_lab_recipe_status CHECK (status IN ('DRAFT','SUPERSEDED','RELEASED')),
  CONSTRAINT ck_lab_recipe_values CHECK (actual_weight_kg > 0 AND bath_ratio > 0 AND bath_volume_l > 0 AND (time_minutes IS NULL OR time_minutes > 0))
);
CREATE INDEX idx_lab_recipe_request ON lab_recipe_versions(lab_request_id,alternative_code,version_no DESC);

CREATE TABLE lab_recipe_components (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_version_id uuid NOT NULL REFERENCES lab_recipe_versions(id) ON DELETE CASCADE,
  line_no integer NOT NULL,
  material_code varchar(60),
  material_name varchar(180) NOT NULL,
  dosage numeric(14,6) NOT NULL,
  dosage_unit varchar(20) NOT NULL,
  calculated_quantity_kg numeric(16,6) NOT NULL,
  notes varchar(500),
  CONSTRAINT uq_lab_recipe_component_line UNIQUE(recipe_version_id,line_no),
  CONSTRAINT ck_lab_recipe_component_values CHECK (dosage > 0 AND calculated_quantity_kg >= 0),
  CONSTRAINT ck_lab_recipe_component_unit CHECK (dosage_unit IN ('G_L','PCT_OWF'))
);

CREATE TABLE lab_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lab_request_id uuid NOT NULL REFERENCES lab_requests(id) ON DELETE CASCADE,
  event_type varchar(40) NOT NULL,
  from_status varchar(30),
  to_status varchar(30),
  comment varchar(500),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_lab_events_request ON lab_events(lab_request_id,created_at);

ALTER TABLE lab_requests
  ADD CONSTRAINT fk_lab_released_recipe FOREIGN KEY (released_recipe_id) REFERENCES lab_recipe_versions(id) ON DELETE RESTRICT;

INSERT INTO permissions(code,name) VALUES
('LAB_REQUESTS_READ','Consultar solicitudes del laboratorio de color'),
('LAB_REQUESTS_WRITE','Crear y desarrollar lab dips'),
('LAB_REQUESTS_APPROVE','Registrar aprobación o rechazo del cliente'),
('LAB_RECIPES_RELEASE','Liberar recetas aprobadas para producción')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('LAB_REQUESTS_READ','LAB_REQUESTS_WRITE','LAB_REQUESTS_APPROVE','LAB_RECIPES_RELEASE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('LAB_REQUESTS_READ','LAB_REQUESTS_WRITE','LAB_REQUESTS_APPROVE','LAB_RECIPES_RELEASE')
WHERE r.code='LAB'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('LAB_REQUESTS_READ','LAB_REQUESTS_APPROVE')
WHERE r.code='SALES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='LAB_REQUESTS_READ'
WHERE r.code IN ('PLANNING','PRODUCTION','QUALITY','WAREHOUSE')
ON CONFLICT DO NOTHING;

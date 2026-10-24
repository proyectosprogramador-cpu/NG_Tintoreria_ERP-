ALTER TABLE production_orders DROP CONSTRAINT ck_production_order_status;
ALTER TABLE production_orders
  ADD CONSTRAINT ck_production_order_status CHECK (status IN (
    'PLANNED','IN_PROCESS','PAUSED','IN_QUALITY','REPROCESS_REQUIRED',
    'READY_FOR_DISPATCH','QUALITY_REJECTED','CANCELLED'
  ));

CREATE SEQUENCE quality_inspection_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE quality_inspections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  inspection_number varchar(30) NOT NULL UNIQUE,
  production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE RESTRICT,
  attempt_no integer NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  result varchar(20) NOT NULL DEFAULT 'PENDING',
  inspected_by varchar(160),
  inspected_at timestamptz,
  decision_by varchar(160),
  decision_at timestamptz,
  general_notes text,
  defect_summary text,
  reprocess_instructions text,
  version_no integer NOT NULL DEFAULT 1,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_quality_order_attempt UNIQUE(production_order_id,attempt_no),
  CONSTRAINT ck_quality_attempt CHECK (attempt_no > 0),
  CONSTRAINT ck_quality_status CHECK (status IN ('DRAFT','SUBMITTED','DECIDED')),
  CONSTRAINT ck_quality_result CHECK (result IN ('PENDING','APPROVED','OBSERVED','REPROCESS','REJECTED'))
);
CREATE INDEX idx_quality_inspections_order ON quality_inspections(production_order_id,attempt_no DESC);
CREATE INDEX idx_quality_inspections_status ON quality_inspections(status,result,created_at DESC);

CREATE TABLE quality_checks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quality_inspection_id uuid NOT NULL REFERENCES quality_inspections(id) ON DELETE CASCADE,
  line_no integer NOT NULL,
  check_type varchar(40) NOT NULL,
  parameter_name varchar(160) NOT NULL,
  target_value varchar(120),
  actual_value varchar(120),
  unit varchar(40),
  result varchar(20) NOT NULL DEFAULT 'PENDING',
  notes varchar(700),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_quality_check_line UNIQUE(quality_inspection_id,line_no),
  CONSTRAINT ck_quality_check_line CHECK (line_no > 0),
  CONSTRAINT ck_quality_check_type CHECK (check_type IN (
    'COLOR_MATCH','PH','GRAMMAGE_GSM','WIDTH_CM','FASTNESS_WASH','FASTNESS_RUB',
    'SHRINKAGE','HAND_FEEL','STAINS','MOISTURE','OTHER'
  )),
  CONSTRAINT ck_quality_check_result CHECK (result IN ('PENDING','PASS','OBSERVED','FAIL'))
);
CREATE INDEX idx_quality_checks_inspection ON quality_checks(quality_inspection_id,line_no);

CREATE TABLE quality_evidences (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quality_inspection_id uuid NOT NULL REFERENCES quality_inspections(id) ON DELETE CASCADE,
  original_name varchar(255) NOT NULL,
  storage_name varchar(255) NOT NULL UNIQUE,
  content_type varchar(120) NOT NULL,
  size_bytes bigint NOT NULL,
  note varchar(500),
  uploaded_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_quality_evidence_size CHECK (size_bytes > 0)
);
CREATE INDEX idx_quality_evidences_inspection ON quality_evidences(quality_inspection_id,created_at DESC);

CREATE TABLE quality_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quality_inspection_id uuid NOT NULL REFERENCES quality_inspections(id) ON DELETE CASCADE,
  event_type varchar(40) NOT NULL,
  from_status varchar(20),
  to_status varchar(20),
  from_result varchar(20),
  to_result varchar(20),
  comment varchar(700),
  actor varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_quality_events_inspection ON quality_events(quality_inspection_id,created_at);

INSERT INTO permissions(code,name) VALUES
('QUALITY_READ','Consultar control de calidad'),
('QUALITY_WRITE','Registrar pruebas y observaciones de calidad'),
('QUALITY_DECIDE','Aprobar, observar, enviar a reproceso o rechazar calidad'),
('QUALITY_EVIDENCE','Adjuntar y administrar evidencias de calidad')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('QUALITY_READ','QUALITY_WRITE','QUALITY_DECIDE','QUALITY_EVIDENCE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('QUALITY_READ','QUALITY_WRITE','QUALITY_DECIDE','QUALITY_EVIDENCE')
WHERE r.code='QUALITY'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='QUALITY_READ'
WHERE r.code IN ('PRODUCTION','PLANNING','DISPATCH')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='QUALITY'
ON CONFLICT DO NOTHING;

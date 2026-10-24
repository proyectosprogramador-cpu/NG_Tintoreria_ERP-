-- v0.12.1 - Mantenimiento preventivo/correctivo de máquinas, horómetro e indisponibilidad

ALTER TABLE machines
  ADD COLUMN IF NOT EXISTS brand varchar(120),
  ADD COLUMN IF NOT EXISTS model varchar(120),
  ADD COLUMN IF NOT EXISTS serial_number varchar(120),
  ADD COLUMN IF NOT EXISTS location varchar(160),
  ADD COLUMN IF NOT EXISTS commissioning_date date,
  ADD COLUMN IF NOT EXISTS current_meter_hours numeric(16,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS maintenance_interval_hours numeric(16,2),
  ADD COLUMN IF NOT EXISTS maintenance_interval_days integer,
  ADD COLUMN IF NOT EXISTS last_maintenance_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_maintenance_meter_hours numeric(16,2),
  ADD COLUMN IF NOT EXISTS next_maintenance_at timestamptz,
  ADD COLUMN IF NOT EXISTS next_maintenance_meter_hours numeric(16,2);

ALTER TABLE machines DROP CONSTRAINT IF EXISTS ck_machine_meter_values;
ALTER TABLE machines ADD CONSTRAINT ck_machine_meter_values CHECK (
  current_meter_hours >= 0 AND
  (maintenance_interval_hours IS NULL OR maintenance_interval_hours > 0) AND
  (maintenance_interval_days IS NULL OR maintenance_interval_days > 0) AND
  (last_maintenance_meter_hours IS NULL OR last_maintenance_meter_hours >= 0) AND
  (next_maintenance_meter_hours IS NULL OR next_maintenance_meter_hours >= 0)
);

UPDATE machines SET operational_status='AVAILABLE'
WHERE operational_status IS NULL OR btrim(operational_status)=''
   OR upper(btrim(operational_status)) IN ('OPERATIVE','OPERATIONAL','OPERATIVA','OPERATIVO','ACTIVE','ACTIVA','ACTIVO','DISPONIBLE');
UPDATE machines SET operational_status='IN_MAINTENANCE'
WHERE upper(btrim(operational_status)) IN ('MAINTENANCE','MANTENIMIENTO','EN MANTENIMIENTO','EN_MANTENIMIENTO');
UPDATE machines SET operational_status='OUT_OF_SERVICE'
WHERE upper(btrim(operational_status)) IN ('OUT OF SERVICE','FUERA DE SERVICIO','FUERA_SERVICIO','NO OPERATIVA','NO OPERATIVO');
UPDATE machines SET operational_status='RETIRED'
WHERE upper(btrim(operational_status)) IN ('RETIRED','RETIRADA','RETIRADO','BAJA');
-- Cualquier estado histórico no reconocido se conserva como disponible para no bloquear la operación al migrar.
UPDATE machines SET operational_status='AVAILABLE'
WHERE upper(btrim(operational_status)) NOT IN ('AVAILABLE','IN_MAINTENANCE','OUT_OF_SERVICE','RETIRED');

ALTER TABLE machines DROP CONSTRAINT IF EXISTS ck_machine_operational_status;
ALTER TABLE machines ADD CONSTRAINT ck_machine_operational_status
  CHECK (operational_status IN ('AVAILABLE','IN_MAINTENANCE','OUT_OF_SERVICE','RETIRED'));

ALTER TABLE production_orders
  ADD COLUMN IF NOT EXISTS machine_hours_posted boolean NOT NULL DEFAULT false;

CREATE SEQUENCE IF NOT EXISTS maintenance_work_order_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS maintenance_work_orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  maintenance_number varchar(30) NOT NULL UNIQUE,
  machine_id uuid NOT NULL REFERENCES machines(id) ON DELETE RESTRICT,
  maintenance_type varchar(20) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PLANNED',
  priority varchar(20) NOT NULL DEFAULT 'NORMAL',
  trigger_source varchar(20) NOT NULL DEFAULT 'MANUAL',
  title varchar(220) NOT NULL,
  description text,
  scheduled_start timestamptz NOT NULL,
  scheduled_end timestamptz NOT NULL,
  actual_start timestamptz,
  actual_end timestamptz,
  meter_hours_start numeric(16,2),
  meter_hours_end numeric(16,2),
  technician_name varchar(160),
  provider_name varchar(180),
  work_performed text,
  parts_used text,
  downtime_minutes integer NOT NULL DEFAULT 0,
  labor_cost numeric(18,4) NOT NULL DEFAULT 0,
  parts_cost numeric(18,4) NOT NULL DEFAULT 0,
  external_cost numeric(18,4) NOT NULL DEFAULT 0,
  total_cost numeric(18,4) NOT NULL DEFAULT 0,
  final_machine_status varchar(30),
  next_due_at timestamptz,
  next_due_meter_hours numeric(16,2),
  cancellation_reason varchar(500),
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(160),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_by varchar(160),
  CONSTRAINT ck_maintenance_type CHECK (maintenance_type IN ('PREVENTIVE','CORRECTIVE','EMERGENCY','INSPECTION')),
  CONSTRAINT ck_maintenance_status CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')),
  CONSTRAINT ck_maintenance_priority CHECK (priority IN ('LOW','NORMAL','HIGH','CRITICAL')),
  CONSTRAINT ck_maintenance_trigger CHECK (trigger_source IN ('MANUAL','HOURS','DAYS','INCIDENT')),
  CONSTRAINT ck_maintenance_schedule CHECK (scheduled_end > scheduled_start),
  CONSTRAINT ck_maintenance_values CHECK (
    downtime_minutes >= 0 AND labor_cost >= 0 AND parts_cost >= 0 AND external_cost >= 0 AND total_cost >= 0 AND
    (meter_hours_start IS NULL OR meter_hours_start >= 0) AND
    (meter_hours_end IS NULL OR meter_hours_end >= 0) AND
    (next_due_meter_hours IS NULL OR next_due_meter_hours >= 0)
  )
);
CREATE INDEX IF NOT EXISTS idx_maintenance_machine_date ON maintenance_work_orders(machine_id,scheduled_start DESC);
CREATE INDEX IF NOT EXISTS idx_maintenance_status_date ON maintenance_work_orders(status,scheduled_start);
CREATE INDEX IF NOT EXISTS idx_maintenance_type ON maintenance_work_orders(maintenance_type,status);

CREATE TABLE IF NOT EXISTS maintenance_evidences (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  maintenance_work_order_id uuid NOT NULL REFERENCES maintenance_work_orders(id) ON DELETE CASCADE,
  original_name varchar(255) NOT NULL,
  storage_name varchar(255) NOT NULL UNIQUE,
  content_type varchar(120) NOT NULL,
  size_bytes bigint NOT NULL,
  note varchar(500),
  uploaded_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_maintenance_evidence_size CHECK (size_bytes > 0)
);
CREATE INDEX IF NOT EXISTS idx_maintenance_evidence_work_order ON maintenance_evidences(maintenance_work_order_id,created_at DESC);

INSERT INTO permissions(code,name) VALUES
('MAINTENANCE_READ','Consultar estado y mantenimiento de máquinas'),
('MAINTENANCE_WRITE','Programar y editar órdenes de mantenimiento'),
('MAINTENANCE_EXECUTE','Iniciar, completar y cancelar mantenimiento'),
('MAINTENANCE_CONFIG','Configurar horómetro e intervalos de mantenimiento')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('MAINTENANCE_READ','MAINTENANCE_WRITE','MAINTENANCE_EXECUTE','MAINTENANCE_CONFIG')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p
  ON p.code IN ('MAINTENANCE_READ','MAINTENANCE_WRITE','MAINTENANCE_EXECUTE','MAINTENANCE_CONFIG','REPORTS_READ','REPORTS_EXPORT')
WHERE r.code='MAINTENANCE'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='MAINTENANCE_READ'
WHERE r.code IN ('PLANNING','PRODUCTION')
ON CONFLICT DO NOTHING;

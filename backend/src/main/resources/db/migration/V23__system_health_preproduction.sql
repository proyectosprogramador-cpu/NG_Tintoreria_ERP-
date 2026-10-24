-- v0.12.4 - Estado tecnico, preflight y preparacion productiva

CREATE TABLE IF NOT EXISTS system_environment_config (
  id smallint PRIMARY KEY DEFAULT 1,
  deployment_stage varchar(20) NOT NULL DEFAULT 'PREPRODUCTION',
  planned_go_live date,
  responsible varchar(160),
  notes text,
  updated_by varchar(160),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_system_environment_singleton CHECK (id = 1),
  CONSTRAINT ck_system_deployment_stage CHECK (deployment_stage IN ('PREPRODUCTION','PRODUCTION'))
);

INSERT INTO system_environment_config(id,deployment_stage,updated_by)
VALUES (1,'PREPRODUCTION','SYSTEM')
ON CONFLICT(id) DO NOTHING;

CREATE TABLE IF NOT EXISTS system_preflight_runs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  overall_status varchar(20) NOT NULL,
  blocker_count integer NOT NULL DEFAULT 0,
  warning_count integer NOT NULL DEFAULT 0,
  checks_json text NOT NULL,
  run_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_preflight_status CHECK (overall_status IN ('READY','ATTENTION','BLOCKED')),
  CONSTRAINT ck_preflight_counts CHECK (blocker_count >= 0 AND warning_count >= 0)
);
CREATE INDEX IF NOT EXISTS idx_system_preflight_runs_created_at ON system_preflight_runs(created_at DESC);

INSERT INTO permissions(code,name) VALUES
('SYSTEM_HEALTH_READ','Consultar estado tecnico y preparacion productiva'),
('SYSTEM_HEALTH_MANAGE','Ejecutar preflight y administrar etapa de despliegue')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('SYSTEM_HEALTH_READ','SYSTEM_HEALTH_MANAGE')
WHERE r.code='ADMIN'
ON CONFLICT DO NOTHING;

-- v0.10.5 - Seguridad, auditoria, backups y recuperacion

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS failed_login_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS locked_until timestamptz;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_login_at timestamptz;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS password_changed_at timestamptz;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS security_version integer NOT NULL DEFAULT 1;

ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS ip_address varchar(80);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS http_method varchar(12);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS request_path varchar(300);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS success boolean NOT NULL DEFAULT true;
CREATE INDEX IF NOT EXISTS idx_audit_actor_created_at ON audit_events(actor, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_module_created_at ON audit_events(module, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action_created_at ON audit_events(action, created_at DESC);

CREATE TABLE IF NOT EXISTS auth_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  jti uuid NOT NULL UNIQUE,
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  last_seen_at timestamptz NOT NULL,
  revoked_at timestamptz,
  ip_address varchar(80),
  user_agent varchar(500)
);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_active ON auth_sessions(user_id, revoked_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_jti ON auth_sessions(jti);

CREATE TABLE IF NOT EXISTS system_backup_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  filename varchar(260) NOT NULL,
  size_bytes bigint NOT NULL DEFAULT 0,
  sha256 varchar(64),
  status varchar(30) NOT NULL DEFAULT 'CREATED',
  created_by varchar(160) NOT NULL DEFAULT 'WINDOWS_SCRIPT',
  created_at timestamptz NOT NULL DEFAULT now(),
  verified_at timestamptz,
  restored_at timestamptz,
  notes text
);
CREATE INDEX IF NOT EXISTS idx_backup_created_at ON system_backup_history(created_at DESC);

INSERT INTO permissions(code,name) VALUES
('SECURITY_READ','Consultar seguridad, sesiones y salud del sistema'),
('SECURITY_MANAGE','Gestionar sesiones y controles de seguridad'),
('BACKUP_READ','Consultar historial de backups'),
('BACKUP_MANAGE','Registrar y administrar backups/restauraciones')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='ADMIN'
ON CONFLICT DO NOTHING;

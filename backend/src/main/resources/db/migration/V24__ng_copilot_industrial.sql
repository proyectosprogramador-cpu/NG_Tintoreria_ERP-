-- v0.13.0 - NG Copiloto Industrial: consultas seguras, voz/recordatorios y permisos

CREATE TABLE IF NOT EXISTS copilot_reminders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_email varchar(160) NOT NULL,
  message varchar(700) NOT NULL,
  remind_at timestamptz NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  delivered_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_copilot_reminder_status CHECK (status IN ('PENDING','DONE','DISMISSED'))
);
CREATE INDEX IF NOT EXISTS idx_copilot_reminders_user_due
  ON copilot_reminders(user_email,status,remind_at);

INSERT INTO permissions(code,name) VALUES
('COPILOT_USE','Usar NG Copiloto Industrial y recordatorios')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE p.code='COPILOT_USE'
ON CONFLICT DO NOTHING;

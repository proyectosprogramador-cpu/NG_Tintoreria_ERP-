-- v0.13.2 - Comunicaciones inteligentes del NG Copiloto

CREATE TABLE IF NOT EXISTS copilot_notification_contacts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(160) NOT NULL,
  role_code varchar(50) NOT NULL DEFAULT 'ADMIN',
  email varchar(200),
  whatsapp varchar(60),
  email_enabled boolean NOT NULL DEFAULT false,
  whatsapp_enabled boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_copilot_notification_contacts_role
  ON copilot_notification_contacts(role_code,active);

CREATE TABLE IF NOT EXISTS copilot_notification_rules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category varchar(40) NOT NULL UNIQUE,
  min_severity varchar(20) NOT NULL DEFAULT 'IMPORTANT',
  channels varchar(80) NOT NULL DEFAULT 'EMAIL',
  role_codes varchar(300) NOT NULL DEFAULT 'ADMIN',
  enabled boolean NOT NULL DEFAULT true,
  repeat_minutes integer NOT NULL DEFAULT 240,
  quiet_start time,
  quiet_end time,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_copilot_notification_rule_severity CHECK (min_severity IN ('INFO','IMPORTANT','CRITICAL')),
  CONSTRAINT ck_copilot_notification_rule_repeat CHECK (repeat_minutes BETWEEN 30 AND 10080)
);

CREATE TABLE IF NOT EXISTS copilot_notification_outbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_key varchar(260) NOT NULL,
  category varchar(40) NOT NULL,
  severity varchar(20) NOT NULL,
  channel varchar(20) NOT NULL,
  recipient varchar(220) NOT NULL,
  subject varchar(240),
  message text NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  dedupe_key varchar(500) NOT NULL UNIQUE,
  last_error text,
  provider_response text,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  CONSTRAINT ck_copilot_notification_channel CHECK (channel IN ('EMAIL','WHATSAPP')),
  CONSTRAINT ck_copilot_notification_status CHECK (status IN ('PENDING','SENT','FAILED','SIMULATED'))
);
CREATE INDEX IF NOT EXISTS idx_copilot_notification_outbox_created
  ON copilot_notification_outbox(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_copilot_notification_outbox_status
  ON copilot_notification_outbox(status,created_at DESC);

INSERT INTO permissions(code,name) VALUES
('NOTIFICATIONS_READ','Consultar comunicaciones inteligentes y trazabilidad de envíos'),
('NOTIFICATIONS_MANAGE','Configurar destinatarios, reglas y pruebas de comunicaciones')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('NOTIFICATIONS_READ','NOTIFICATIONS_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO copilot_notification_rules(category,min_severity,channels,role_codes,enabled,repeat_minutes) VALUES
('INVENTORY','CRITICAL','EMAIL,WHATSAPP','ADMIN,WAREHOUSE,PURCHASES',true,240),
('PRODUCTION','IMPORTANT','EMAIL,WHATSAPP','ADMIN,PLANNING,PRODUCTION',true,180),
('QUALITY','IMPORTANT','EMAIL,WHATSAPP','ADMIN,QUALITY,PRODUCTION',true,180),
('DISPATCH','IMPORTANT','EMAIL,WHATSAPP','ADMIN,DISPATCH',true,240),
('MAINTENANCE','IMPORTANT','EMAIL,WHATSAPP','ADMIN,MAINTENANCE',true,720),
('RETURNS','IMPORTANT','EMAIL,WHATSAPP','ADMIN,QUALITY,PRODUCTION',true,360)
ON CONFLICT(category) DO NOTHING;

INSERT INTO copilot_notification_contacts(name,role_code,email,email_enabled,whatsapp_enabled,active)
SELECT u.display_name,'ADMIN',u.email,false,false,true
  FROM app_users u
  JOIN user_roles ur ON ur.user_id=u.id
  JOIN roles r ON r.id=ur.role_id AND r.code='ADMIN'
 WHERE u.active=true
   AND NOT EXISTS (SELECT 1 FROM copilot_notification_contacts c WHERE lower(c.email)=lower(u.email))
LIMIT 1;

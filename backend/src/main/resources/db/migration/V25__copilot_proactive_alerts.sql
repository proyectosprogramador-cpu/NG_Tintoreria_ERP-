-- v0.13.1 - NG Copiloto Proactivo: alertas por usuario, briefing y acciones confirmables

CREATE TABLE IF NOT EXISTS copilot_alert_user_state (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_email varchar(160) NOT NULL,
  alert_key varchar(260) NOT NULL,
  hidden_until timestamptz,
  last_notified_at timestamptz,
  last_seen_at timestamptz,
  last_action varchar(30),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_copilot_alert_user UNIQUE(user_email,alert_key),
  CONSTRAINT ck_copilot_alert_action CHECK (last_action IS NULL OR last_action IN ('ACK','SNOOZE','DISMISS'))
);
CREATE INDEX IF NOT EXISTS idx_copilot_alert_user_visible
  ON copilot_alert_user_state(user_email,hidden_until,last_notified_at);

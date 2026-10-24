-- v0.10.4 R1 - reparación idempotente de permisos del Dashboard

-- ADMIN debe tener todos los permisos existentes en la instalación.
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- Reafirma DASHBOARD_READ para los roles operativos estándar.
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'DASHBOARD_READ'
WHERE r.code IN (
  'SALES','LAB','PLANNING','PRODUCTION','WAREHOUSE','PURCHASES',
  'QUALITY','DISPATCH','FINANCE','MAINTENANCE','BILLING'
)
ON CONFLICT DO NOTHING;

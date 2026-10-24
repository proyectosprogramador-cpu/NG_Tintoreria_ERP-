-- v0.10.4 - Dashboard gerencial y acceso transversal a reportes

INSERT INTO permissions(code,name) VALUES
('DASHBOARD_READ','Consultar dashboard operativo y gerencial')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code='DASHBOARD_READ'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='DASHBOARD_READ'
WHERE r.code IN ('SALES','LAB','PLANNING','PRODUCTION','WAREHOUSE','PURCHASES','QUALITY','DISPATCH','FINANCE','MAINTENANCE','BILLING')
ON CONFLICT DO NOTHING;

-- REPORTS_EXPORT sigue estando protegido además por el permiso específico del módulo.
-- Se habilita transversalmente para que cada área pueda descargar únicamente los reportes
-- de los módulos que ya puede consultar.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('REPORTS_READ','REPORTS_EXPORT')
WHERE r.code IN ('SALES','LAB','PLANNING','PRODUCTION','WAREHOUSE','PURCHASES','QUALITY','DISPATCH','FINANCE','BILLING')
ON CONFLICT DO NOTHING;

INSERT INTO permissions(code,name) VALUES
('COMPANY_READ','Consultar empresa'),('COMPANY_WRITE','Modificar empresa'),
('USERS_READ','Consultar usuarios y roles'),('USERS_WRITE','Gestionar usuarios y roles'),
('CLIENTS_READ','Consultar clientes'),('CLIENTS_WRITE','Gestionar clientes'),
('SUPPLIERS_READ','Consultar proveedores'),('SUPPLIERS_WRITE','Gestionar proveedores'),
('MASTERS_READ','Consultar maestros técnicos'),('MASTERS_WRITE','Gestionar maestros técnicos'),
('FORMULAS_READ','Consultar fórmulas'),('FORMULAS_WRITE','Crear/versionar fórmulas'),('FORMULAS_RELEASE','Liberar fórmulas'),
('PRICING_READ','Consultar listas de precios'),('PRICING_WRITE','Gestionar listas de precios'),
('AUDIT_READ','Consultar auditoría')
ON CONFLICT(code) DO NOTHING;

INSERT INTO roles(code,name) VALUES
('ADMIN','Propietario / Administrador'),('SALES','Ventas'),('LAB','Laboratorio'),('PLANNING','Planificación'),('PRODUCTION','Producción'),('WAREHOUSE','Almacén'),('PURCHASES','Compras'),('QUALITY','Calidad'),('DISPATCH','Despacho / Cobranza'),('FINANCE','Finanzas'),('MAINTENANCE','Mantenimiento'),('BILLING','Facturación')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COMPANY_READ','CLIENTS_READ','CLIENTS_WRITE','MASTERS_READ','FORMULAS_READ','PRICING_READ') WHERE r.code='SALES'
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COMPANY_READ','CLIENTS_READ','MASTERS_READ','MASTERS_WRITE','FORMULAS_READ','FORMULAS_WRITE','FORMULAS_RELEASE','PRICING_READ') WHERE r.code='LAB'
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COMPANY_READ','CLIENTS_READ','MASTERS_READ','FORMULAS_READ','PRICING_READ') WHERE r.code IN ('PLANNING','PRODUCTION','WAREHOUSE','QUALITY','MAINTENANCE')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COMPANY_READ','SUPPLIERS_READ','SUPPLIERS_WRITE','MASTERS_READ','PRICING_READ') WHERE r.code='PURCHASES'
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('COMPANY_READ','CLIENTS_READ','SUPPLIERS_READ','MASTERS_READ','PRICING_READ') WHERE r.code IN ('DISPATCH','FINANCE','BILLING')
ON CONFLICT DO NOTHING;

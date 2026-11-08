-- Permiso específico para dar de alta insumos, sin otorgar facultad de mover existencias.
INSERT INTO permissions(code,name)
VALUES ('INVENTORY_ITEMS_WRITE','Registrar nuevos insumos en el catálogo de Almacén')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='INVENTORY_ITEMS_WRITE'
WHERE r.code IN ('ADMIN','WAREHOUSE','PURCHASES')
ON CONFLICT DO NOTHING;

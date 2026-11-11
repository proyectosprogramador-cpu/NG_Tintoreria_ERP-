-- V30: permisos de reportes del rol Producción.
-- Los operarios no deben ver ventas, márgenes ni costeo gerencial.
-- Es idempotente y no afecta los permisos de FINANCE/ADMIN ni datos productivos.
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'PRODUCTION'
  AND p.code = 'COSTING_READ';

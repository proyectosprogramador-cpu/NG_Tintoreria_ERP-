-- v0.13.2-R3 - Laboratorio puede consultar recepciones liberadas

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code='LAB' AND p.code='RECEPTIONS_READ'
ON CONFLICT DO NOTHING;

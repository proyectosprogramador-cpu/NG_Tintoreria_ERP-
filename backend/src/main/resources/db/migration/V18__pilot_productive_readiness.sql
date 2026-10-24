-- v0.11.0 - Piloto productivo, checklist de puesta en marcha e incidencias

CREATE TABLE IF NOT EXISTS pilot_cycles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(40) NOT NULL UNIQUE,
  name varchar(180) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'PLANNING',
  planned_go_live date,
  started_at timestamptz,
  closed_at timestamptz,
  notes text,
  created_by varchar(160) NOT NULL DEFAULT 'SYSTEM',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_pilot_cycle_status CHECK (status IN ('PLANNING','IN_PROGRESS','READY','GO_LIVE','CLOSED'))
);

CREATE TABLE IF NOT EXISTS pilot_checklist_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cycle_id uuid NOT NULL REFERENCES pilot_cycles(id) ON DELETE CASCADE,
  sort_order integer NOT NULL DEFAULT 0,
  area varchar(80) NOT NULL,
  title varchar(220) NOT NULL,
  description text,
  mandatory boolean NOT NULL DEFAULT true,
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  responsible varchar(160),
  evidence text,
  validated_by varchar(160),
  validated_at timestamptz,
  updated_by varchar(160),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_pilot_check_status CHECK (status IN ('PENDING','IN_PROGRESS','PASSED','BLOCKED','NOT_APPLICABLE')),
  UNIQUE(cycle_id, area, title)
);
CREATE INDEX IF NOT EXISTS idx_pilot_checklist_cycle_area ON pilot_checklist_items(cycle_id,area,sort_order);
CREATE INDEX IF NOT EXISTS idx_pilot_checklist_status ON pilot_checklist_items(cycle_id,status);

CREATE SEQUENCE IF NOT EXISTS pilot_incident_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS pilot_incidents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cycle_id uuid NOT NULL REFERENCES pilot_cycles(id) ON DELETE CASCADE,
  incident_number varchar(30) NOT NULL UNIQUE,
  severity varchar(20) NOT NULL DEFAULT 'MEDIUM',
  status varchar(30) NOT NULL DEFAULT 'OPEN',
  module varchar(100) NOT NULL,
  title varchar(220) NOT NULL,
  description text NOT NULL,
  steps_to_reproduce text,
  expected_result text,
  actual_result text,
  assigned_to varchar(160),
  resolution text,
  reported_by varchar(160) NOT NULL,
  reported_at timestamptz NOT NULL DEFAULT now(),
  resolved_by varchar(160),
  resolved_at timestamptz,
  verified_by varchar(160),
  verified_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_pilot_incident_severity CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_pilot_incident_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','VERIFIED','CLOSED'))
);
CREATE INDEX IF NOT EXISTS idx_pilot_incidents_cycle_status ON pilot_incidents(cycle_id,status,severity);
CREATE INDEX IF NOT EXISTS idx_pilot_incidents_reported_at ON pilot_incidents(reported_at DESC);

INSERT INTO permissions(code,name) VALUES
('PILOT_READ','Consultar piloto productivo y checklist'),
('PILOT_WRITE','Gestionar checklist e incidencias del piloto'),
('PILOT_VALIDATE','Validar cierre de incidencias y puesta en marcha')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='ADMIN'
ON CONFLICT DO NOTHING;

-- Permiso de lectura para planificación y producción durante las pruebas controladas.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='PILOT_READ' WHERE r.code IN ('PLANNING','PRODUCTION')
ON CONFLICT DO NOTHING;

INSERT INTO pilot_cycles(code,name,status,planned_go_live,started_at,notes,created_by)
SELECT 'PILOT-001','Piloto productivo inicial','IN_PROGRESS',NULL,now(),
       'Validación integral previa a la salida a producción estable.','SYSTEM'
WHERE NOT EXISTS (SELECT 1 FROM pilot_cycles);

WITH c AS (SELECT id FROM pilot_cycles ORDER BY created_at LIMIT 1),
seed(sort_order,area,title,description,mandatory) AS (VALUES
 (10,'Empresa e identidad','Datos legales y comerciales completos','Razón social, nombre comercial, RUC, dirección, teléfono y correo configurados.',true),
 (20,'Empresa e identidad','Logo corporativo validado','Logo visible correctamente en documentos PDF, Excel e impresiones.',true),
 (30,'Maestros','Telas, colores, procesos y acabados cargados','Maestros técnicos necesarios para el arranque real cargados y revisados.',true),
 (40,'Maestros','Máquinas y capacidades configuradas','Máquinas activas, capacidades y costos por hora revisados.',true),
 (50,'Maestros','Unidades y fórmulas iniciales revisadas','Unidades de medida y fórmulas/recetas necesarias para el piloto validadas.',true),
 (60,'Comercial','Cliente real de prueba creado','Cliente con datos de contacto y condiciones comerciales correctas.',true),
 (70,'Comercial','Cotización/proforma validada','Creación, cálculo, aprobación, impresión/PDF y conversión a pedido probadas.',true),
 (80,'Comercial','Pedido validado','Pedido convertido desde cotización y flujo de estado comprobado.',true),
 (90,'Recepción','Recepción de tela validada','Rollos, kilos, metros, guía, incidencias y liberación probados.',true),
 (100,'Laboratorio','Lab dip y receta liberada validados','Muestra, alternativas, receta, versión y liberación comprobadas.',true),
 (110,'Producción','Programación y OP validadas','OP creada/programada con máquina, lote, prioridad y ruta correctos.',true),
 (120,'Almacén y compras','Stock inicial y Kardex validados','Entrada inicial, saldo físico, reservado y disponible comprobados.',true),
 (130,'Almacén y compras','Reserva de materiales por OP validada','Reserva no descuenta físico y evita doble compromiso de stock.',true),
 (140,'Almacén y compras','Solicitud y orden de compra validadas','Faltante genera SC, aprobación, OC, PDF y recepción parcial/total.',true),
 (150,'Producción','Consumo real y Kardex validados','Consumo real, devolución, merma, agua, energía e incidencias probados.',true),
 (160,'Calidad','Control de calidad y reproceso validados','Aprobación, observación, reproceso, rechazo y evidencias comprobados.',true),
 (170,'Despacho','Despacho parcial y entrega validados','Rollos finales, kilos, transportista, PDF y confirmación de entrega probados.',true),
 (180,'Costeo','Costo industrial y margen validados','Materiales, agua, energía, máquina, mano de obra e indirectos revisados.',true),
 (190,'Reportes','PDF, Excel e impresión validados','Reportes principales descargan con filtros e identidad corporativa.',true),
 (200,'Usuarios y seguridad','Usuarios reales y roles configurados','Usuarios de prueba por puesto creados con permisos mínimos necesarios.',true),
 (210,'Usuarios y seguridad','Sesiones, bloqueo y auditoría validados','Cambio de contraseña, bloqueo, revocación y eventos de auditoría probados.',true),
 (220,'Infraestructura','Acceso LAN desde otra PC validado','Ingreso desde otro equipo de la misma red usando IP del servidor.',true),
 (230,'Infraestructura','Acceso responsive desde laptop/tablet/celular validado','Menú, tablas y formularios operables a 100% de zoom en distintos tamaños.',true),
 (240,'Continuidad','Backup integral verificado','Backup con BD + archivos + logo y SHA-256 creado y verificado.',true),
 (250,'Continuidad','Restauración de prueba ejecutada','Simulacro controlado de restauración completado sobre copia/ventana de mantenimiento.',true),
 (260,'Operación','Flujo punta a punta completado','Caso completo desde cotización hasta entrega y costeo sin intervención manual fuera del flujo previsto.',true),
 (270,'Operación','Incidencias críticas y altas cerradas','No existen incidencias CRITICAL/HIGH abiertas antes de salida real.',true),
 (280,'Entrega','Responsables y procedimiento operativo definidos','Responsables por área y procedimiento básico de uso/contingencia acordados.',true),
 (290,'Entrega','Aprobación de piloto documentada','Administrador/cliente confirma que el piloto puede avanzar a producción estable.',true)
)
INSERT INTO pilot_checklist_items(cycle_id,sort_order,area,title,description,mandatory)
SELECT c.id,s.sort_order,s.area,s.title,s.description,s.mandatory FROM c CROSS JOIN seed s
ON CONFLICT(cycle_id,area,title) DO NOTHING;

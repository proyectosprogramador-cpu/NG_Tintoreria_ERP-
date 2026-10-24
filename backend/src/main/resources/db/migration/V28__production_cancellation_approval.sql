-- V28: solicitud de cancelacion de OP sujeta a autorizacion administrativa.
-- No modifica estados ni reservas de las OP existentes.
CREATE TABLE production_cancellation_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 production_order_id uuid NOT NULL REFERENCES production_orders(id) ON DELETE RESTRICT,
 status varchar(20) NOT NULL DEFAULT 'PENDING',
 reason varchar(500) NOT NULL,
 requested_by varchar(160) NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 requested_order_version integer NOT NULL,
 decided_by varchar(160),
 decided_at timestamptz,
 decision_comment varchar(500),
 CONSTRAINT ck_op_cancel_request_status CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 CONSTRAINT ck_op_cancel_request_reason CHECK(length(btrim(reason)) >= 10),
 CONSTRAINT ck_op_cancel_request_version CHECK(requested_order_version >= 1),
 CONSTRAINT ck_op_cancel_request_decision CHECK (
  (status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
  OR (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
 )
);
CREATE UNIQUE INDEX uq_op_cancel_one_pending
 ON production_cancellation_requests(production_order_id) WHERE status='PENDING';
CREATE INDEX idx_op_cancel_recent ON production_cancellation_requests(production_order_id,requested_at DESC);
CREATE INDEX idx_op_cancel_pending ON production_cancellation_requests(status,requested_at);
-- Actualizar descripcion del permiso historico, sin alterar sus asignaciones.
UPDATE permissions SET name='Programar y reprogramar OP, solicitar cancelacion'
WHERE code='PRODUCTION_PLANNING';

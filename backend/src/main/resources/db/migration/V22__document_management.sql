-- v0.12.3 - Gestión documental central por Pedido / OP / Calidad / Despacho

CREATE TABLE business_documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  entity_type varchar(40) NOT NULL,
  entity_id uuid NOT NULL,
  category varchar(50) NOT NULL DEFAULT 'OTHER',
  title varchar(180) NOT NULL,
  description varchar(1000),
  document_date date,
  document_group_id uuid NOT NULL DEFAULT gen_random_uuid(),
  version_no integer NOT NULL DEFAULT 1,
  is_current boolean NOT NULL DEFAULT true,
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  original_name varchar(255) NOT NULL,
  storage_name varchar(255) NOT NULL UNIQUE,
  content_type varchar(120) NOT NULL,
  size_bytes bigint NOT NULL,
  uploaded_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  archived_by varchar(160),
  archived_at timestamptz,
  CONSTRAINT ck_business_document_entity CHECK (entity_type IN ('SALES_ORDER','PRODUCTION_ORDER','QUALITY_INSPECTION','DISPATCH')),
  CONSTRAINT ck_business_document_category CHECK (category IN ('CUSTOMER_PO','FABRIC_SPEC','TECHNICAL_SHEET','LAB_RESULT','QUALITY_EVIDENCE','DELIVERY_PROOF','COMMERCIAL','OTHER')),
  CONSTRAINT ck_business_document_status CHECK (status IN ('ACTIVE','ARCHIVED')),
  CONSTRAINT ck_business_document_version CHECK (version_no > 0),
  CONSTRAINT ck_business_document_size CHECK (size_bytes > 0),
  CONSTRAINT uq_business_document_group_version UNIQUE(document_group_id,version_no)
);
CREATE INDEX idx_business_documents_entity ON business_documents(entity_type,entity_id,is_current,status,created_at DESC);
CREATE INDEX idx_business_documents_group ON business_documents(document_group_id,version_no DESC);
CREATE INDEX idx_business_documents_category ON business_documents(category,status,created_at DESC);
CREATE UNIQUE INDEX uq_business_document_current_group ON business_documents(document_group_id) WHERE is_current=true;

INSERT INTO permissions(code,name) VALUES
('DOCUMENTS_READ','Consultar documentos vinculados a operaciones'),
('DOCUMENTS_WRITE','Adjuntar documentos y nuevas versiones'),
('DOCUMENTS_ARCHIVE','Archivar documentos vigentes')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('DOCUMENTS_READ','DOCUMENTS_WRITE','DOCUMENTS_ARCHIVE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code IN ('DOCUMENTS_READ','DOCUMENTS_WRITE')
WHERE r.code IN ('SALES','PLANNING','PRODUCTION','QUALITY','DISPATCH')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='DOCUMENTS_READ'
WHERE r.code IN ('WAREHOUSE','FINANCE','BILLING')
ON CONFLICT DO NOTHING;

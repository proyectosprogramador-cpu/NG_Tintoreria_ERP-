CREATE TABLE data_import_batches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  import_type varchar(40) NOT NULL,
  file_name varchar(255) NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PREVIEW',
  total_rows integer NOT NULL DEFAULT 0,
  valid_rows integer NOT NULL DEFAULT 0,
  invalid_rows integer NOT NULL DEFAULT 0,
  skipped_rows integer NOT NULL DEFAULT 0,
  imported_rows integer NOT NULL DEFAULT 0,
  created_by varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  committed_by varchar(160),
  committed_at timestamptz,
  notes varchar(500),
  CONSTRAINT ck_data_import_type CHECK (import_type IN ('CLIENTS','SUPPLIERS','INVENTORY_ITEMS','INITIAL_STOCK','PRICE_LISTS')),
  CONSTRAINT ck_data_import_status CHECK (status IN ('PREVIEW','COMPLETED','CANCELLED','FAILED')),
  CONSTRAINT ck_data_import_counts CHECK (total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0 AND skipped_rows >= 0 AND imported_rows >= 0)
);
CREATE INDEX idx_data_import_batches_date ON data_import_batches(created_at DESC);
CREATE INDEX idx_data_import_batches_type ON data_import_batches(import_type,status);

CREATE TABLE data_import_rows (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES data_import_batches(id) ON DELETE CASCADE,
  row_no integer NOT NULL,
  status varchar(20) NOT NULL,
  action varchar(20) NOT NULL DEFAULT 'CREATE',
  raw_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  normalized_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  error_message varchar(1000),
  imported_reference varchar(160),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_data_import_row UNIQUE(batch_id,row_no),
  CONSTRAINT ck_data_import_row_status CHECK (status IN ('VALID','INVALID','SKIPPED','IMPORTED')),
  CONSTRAINT ck_data_import_row_action CHECK (action IN ('CREATE','SKIP'))
);
CREATE INDEX idx_data_import_rows_batch ON data_import_rows(batch_id,row_no);
CREATE INDEX idx_data_import_rows_status ON data_import_rows(batch_id,status);

INSERT INTO permissions(code,name) VALUES
('DATA_IMPORT_READ','Consultar cargas masivas y plantillas de carga inicial'),
('DATA_IMPORT_WRITE','Previsualizar y ejecutar importaciones masivas')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.code='ADMIN' AND p.code IN ('DATA_IMPORT_READ','DATA_IMPORT_WRITE')
ON CONFLICT DO NOTHING;

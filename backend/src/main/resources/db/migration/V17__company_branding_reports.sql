-- v0.10.8 - Identidad documental y logo corporativo
ALTER TABLE company_profile ADD COLUMN IF NOT EXISTS logo_storage_name varchar(255);
ALTER TABLE company_profile ADD COLUMN IF NOT EXISTS logo_content_type varchar(80);
ALTER TABLE company_profile ADD COLUMN IF NOT EXISTS logo_original_name varchar(255);
ALTER TABLE company_profile ADD COLUMN IF NOT EXISTS logo_updated_at timestamptz;

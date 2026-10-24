CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE permissions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(80) NOT NULL UNIQUE,
  name varchar(150) NOT NULL
);
CREATE TABLE roles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(50) NOT NULL UNIQUE,
  name varchar(120) NOT NULL
);
CREATE TABLE role_permissions (
  role_id uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission_id uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
  PRIMARY KEY(role_id, permission_id)
);
CREATE TABLE app_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email varchar(160) NOT NULL UNIQUE,
  display_name varchar(160) NOT NULL,
  password_hash varchar(255) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  must_change_password boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE TABLE user_roles (
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  role_id uuid NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
  PRIMARY KEY(user_id, role_id)
);

CREATE TABLE company_profile (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  trade_name varchar(180) NOT NULL,
  legal_name varchar(180),
  ruc varchar(20),
  fiscal_address varchar(250),
  phone varchar(80),
  email varchar(160),
  logo_url varchar(500),
  base_currency varchar(3) NOT NULL DEFAULT 'PEN',
  igv_rate double precision NOT NULL DEFAULT 18,
  updated_at timestamptz NOT NULL
);

CREATE TABLE clients (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_number varchar(30) NOT NULL UNIQUE,
  name varchar(180) NOT NULL,
  contact_name varchar(180), whatsapp varchar(80), email varchar(160), address varchar(250),
  credit_days integer NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX idx_clients_name ON clients(lower(name));

CREATE TABLE suppliers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ruc varchar(30) NOT NULL UNIQUE,
  name varchar(180) NOT NULL,
  contact_name varchar(180), email varchar(160), phone varchar(80), payment_terms varchar(120), bank_reference varchar(250),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX idx_suppliers_name ON suppliers(lower(name));

CREATE TABLE fabrics (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  composition varchar(180), gsm double precision, width_cm double precision, default_unit varchar(20)
);
CREATE TABLE dye_colors (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  family varchar(80), pantone varchar(80), surcharge_pct double precision DEFAULT 0
);
CREATE TABLE process_types (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  billing_unit varchar(20), base_price numeric(14,4), minimum_lot numeric(14,3)
);
CREATE TABLE finish_types (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  billing_unit varchar(20), additional_price numeric(14,4)
);
CREATE TABLE measurement_units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  symbol varchar(20), dimension varchar(60)
);
CREATE TABLE machines (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(40) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  min_capacity_kg numeric(14,3), max_capacity_kg numeric(14,3), compatible_processes text, operational_status varchar(30)
);

CREATE TABLE formulas (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(60) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  client_id uuid REFERENCES clients(id) ON DELETE RESTRICT,
  fabric_id uuid NOT NULL REFERENCES fabrics(id) ON DELETE RESTRICT,
  color_id uuid NOT NULL REFERENCES dye_colors(id) ON DELETE RESTRICT,
  process_id uuid NOT NULL REFERENCES process_types(id) ON DELETE RESTRICT,
  finish_id uuid REFERENCES finish_types(id) ON DELETE RESTRICT,
  active boolean NOT NULL DEFAULT true,
  current_version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE formula_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  formula_id uuid NOT NULL REFERENCES formulas(id) ON DELETE CASCADE,
  version_no integer NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  bath_ratio numeric(8,3), temperature_c numeric(8,2), time_minutes integer, ph numeric(6,2), curve_text text,
  created_by varchar(160), created_at timestamptz NOT NULL, released_at timestamptz,
  UNIQUE(formula_id, version_no)
);
CREATE TABLE formula_components (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id uuid NOT NULL REFERENCES formula_versions(id) ON DELETE CASCADE,
  material_code varchar(60), material_name varchar(180) NOT NULL,
  dosage numeric(14,6) NOT NULL, dosage_unit varchar(20) NOT NULL, sequence_order integer NOT NULL DEFAULT 1
);

CREATE TABLE price_lists (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(60) NOT NULL UNIQUE, name varchar(180) NOT NULL,
  client_id uuid REFERENCES clients(id) ON DELETE RESTRICT,
  currency varchar(3) NOT NULL DEFAULT 'PEN', valid_from date NOT NULL, valid_to date,
  priority integer NOT NULL DEFAULT 100, active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE price_rules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  price_list_id uuid NOT NULL REFERENCES price_lists(id) ON DELETE CASCADE,
  fabric_id uuid REFERENCES fabrics(id) ON DELETE RESTRICT,
  color_id uuid REFERENCES dye_colors(id) ON DELETE RESTRICT,
  process_id uuid REFERENCES process_types(id) ON DELETE RESTRICT,
  finish_id uuid REFERENCES finish_types(id) ON DELETE RESTRICT,
  unit varchar(20), min_qty numeric(14,3), max_qty numeric(14,3), unit_price numeric(14,4) NOT NULL,
  surcharge_pct numeric(8,3) DEFAULT 0, discount_pct numeric(8,3) DEFAULT 0, active boolean NOT NULL DEFAULT true
);

CREATE TABLE audit_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor varchar(160) NOT NULL, action varchar(100) NOT NULL,
  module varchar(100) NOT NULL, reference_id varchar(120), detail text, created_at timestamptz NOT NULL
);
CREATE INDEX idx_audit_created_at ON audit_events(created_at DESC);

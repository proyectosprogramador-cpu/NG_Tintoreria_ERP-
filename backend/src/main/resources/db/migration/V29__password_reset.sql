create table if not exists password_reset_tokens (
    id uuid primary key,
    user_id uuid not null references app_users(id) on delete cascade,
    token_hash varchar(64) not null unique,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    requested_ip varchar(80)
);

create index if not exists ix_password_reset_tokens_user_created
    on password_reset_tokens(user_id, created_at desc);

create index if not exists ix_password_reset_tokens_expires
    on password_reset_tokens(expires_at);

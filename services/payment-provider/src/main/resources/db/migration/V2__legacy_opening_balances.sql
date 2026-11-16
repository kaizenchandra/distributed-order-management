create table provider_legacy_imports
(
    id              uuid primary key,
    payment_id      uuid        not null unique references provider_accounts (id),
    digest          text        not null,
    manifest        jsonb       not null,
    accounting_type text        not null default 'OPENING_BALANCE',
    imported_at     timestamptz not null default now()
);
create table provider_legacy_keys
(
    operation_id uuid primary key,
    payment_id   uuid not null,
    import_id    uuid not null references provider_legacy_imports (id)
);

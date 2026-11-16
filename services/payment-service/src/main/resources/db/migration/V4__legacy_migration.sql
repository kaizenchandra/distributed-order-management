alter table payment_intents
    add column provider_origin text not null default 'NATIVE' check (provider_origin in ('NATIVE', 'LEGACY_BLOCKED', 'MIGRATING', 'MIGRATED'));
update payment_intents p
set provider_origin='LEGACY_BLOCKED'
where exists(select 1 from payment_operations o where o.payment_id = p.id)
  and not exists(select 1 from provider_operations q where q.payment_id = p.id);
alter table provider_operations
    add column imported_legacy boolean not null default false;
create table legacy_migrations
(
    id           uuid primary key,
    payment_id   uuid        not null unique references payment_intents (id),
    manifest     jsonb       not null,
    digest       text        not null,
    status       text        not null,
    actor        text        not null,
    attempts     int         not null default 0,
    next_attempt timestamptz          default now(),
    fence        uuid,
    lease_until  timestamptz,
    last_error   text,
    created_at   timestamptz not null default now(),
    verified_at  timestamptz
);
create table legacy_migration_audit
(
    id           bigserial primary key,
    migration_id uuid        not null,
    action       text        not null,
    actor        text        not null,
    detail       text,
    recorded_at  timestamptz not null default now()
);

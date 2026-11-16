create table provider_accounts
(
    id       uuid primary key,
    amount   bigint not null check (amount > 0),
    currency text   not null,
    state    text   not null,
    captured bigint not null default 0,
    refunded bigint not null default 0,
    check (refunded <= captured)
);
create table provider_requests
(
    operation_id uuid primary key,
    payment_id   uuid        not null,
    kind         text        not null,
    request      jsonb       not null,
    result       jsonb       not null,
    hidden       boolean     not null default false,
    created_at   timestamptz not null default now()
);

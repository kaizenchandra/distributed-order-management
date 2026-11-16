create table payment_intents
(
    id          uuid primary key,
    customer_id text   not null,
    amount      bigint not null check (amount > 0),
    currency    text   not null,
    state       text   not null,
    captured    bigint not null default 0,
    refunded    bigint not null default 0,
    check (refunded <= captured)
);
create table payment_operations
(
    operation_id uuid primary key,
    payment_id   uuid  not null,
    kind         text  not null,
    request      jsonb not null,
    result       jsonb not null,
    created_at   timestamptz default now()
);
create table refunds
(
    id         uuid primary key,
    payment_id uuid   not null,
    amount     bigint not null check (amount > 0)
);

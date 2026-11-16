create table outbox
(
    event_id          uuid primary key,
    aggregate_id      uuid        not null,
    aggregate_version bigint      not null,
    event_type        text        not null,
    schema_version    int         not null,
    topic             text        not null,
    payload           jsonb       not null,
    created_at        timestamptz not null default now(),
    published_at      timestamptz,
    attempts          int         not null default 0,
    next_attempt      timestamptz not null default now()
);
create index outbox_pending on outbox (next_attempt, created_at) where published_at is null;
create table inbox
(
    consumer_name text        not null,
    event_id      uuid        not null,
    processed_at  timestamptz not null default now(),
    primary key (consumer_name, event_id)
);

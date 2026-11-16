create table order_events
(
    event_id          uuid primary key,
    aggregate_id      uuid        not null,
    aggregate_version bigint      not null,
    event_type        text        not null,
    schema_version    int         not null default 1,
    payload           jsonb       not null,
    correlation_id    uuid        not null,
    causation_id      uuid,
    occurred_at       timestamptz not null default now(),
    unique (aggregate_id, aggregate_version)
);
create table order_idempotency
(
    customer_id text  not null,
    key         text  not null,
    request     jsonb not null,
    order_id    uuid  not null,
    primary key (customer_id, key)
);
create function immutable_event() returns trigger
    language plpgsql as $$ begin raise exception 'Order history is append only';
end $$;
create trigger no_event_mutation
    before update or
delete
on order_events for each row
execute function immutable_event();

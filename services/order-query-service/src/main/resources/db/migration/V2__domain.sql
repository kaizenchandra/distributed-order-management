create table order_read_models
(
    order_id    uuid primary key,
    customer_id text   not null,
    version     bigint not null default 0,
    state       jsonb  not null default '{}'
);
create index customer_orders on order_read_models (customer_id, order_id);
create table projection_events
(
    order_id uuid   not null,
    version  bigint not null,
    event    jsonb  not null,
    primary key (order_id, version)
);
create table order_timeline
(
    order_id    uuid        not null,
    version     bigint      not null,
    type        text        not null,
    occurred_at timestamptz not null,
    primary key (order_id, version)
);
create table notifications
(
    id         uuid primary key,
    order_id   uuid not null,
    kind       text not null,
    channel    text not null default 'LOCAL_AUDIT',
    created_at timestamptz   default now()
);
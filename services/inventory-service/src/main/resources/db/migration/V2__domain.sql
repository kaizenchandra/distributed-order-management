create table inventory_items
(
    sku       text primary key,
    on_hand   bigint not null check (on_hand >= 0),
    reserved  bigint not null default 0 check (reserved >= 0),
    allocated bigint not null default 0 check (allocated >= 0),
    version   bigint not null default 0,
    check (reserved + allocated <= on_hand)
);
insert into inventory_items(sku, on_hand)
values ('SKU-1', 10000),
       ('SKU-2', 10000);
create table inventory_reservations
(
    id         uuid primary key,
    owner_id   uuid        not null,
    state      text        not null check (state in ('NEW', 'TRIED', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'RELEASED')),
    items      jsonb       not null,
    expires_at timestamptz not null,
    version    bigint      not null default 0
);
create table inventory_movements
(
    id             bigserial primary key,
    reservation_id uuid   not null,
    sku            text   not null,
    quantity       bigint not null,
    kind           text   not null,
    created_at     timestamptz default now()
);
create index reservations_expiry on inventory_reservations (expires_at) where state='TRIED';

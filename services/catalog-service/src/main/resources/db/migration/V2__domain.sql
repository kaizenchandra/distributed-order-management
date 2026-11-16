create table products
(
    sku         text primary key,
    name        text    not null,
    price_minor bigint  not null check (price_minor > 0),
    currency    text    not null,
    active      boolean not null default true
);
insert into products
values ('SKU-1', 'Mechanical Keyboard', 7500, 'USD', true),
       ('SKU-2', 'Mouse', 2500, 'USD', true);
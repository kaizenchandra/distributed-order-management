create table fulfillment_requests
(
    id          uuid primary key,
    customer_id text  not null,
    state       text  not null,
    request     jsonb not null
);
create table shipments
(
    id             uuid primary key,
    fulfillment_id uuid not null unique,
    state          text not null,
    carrier_ref    text
);

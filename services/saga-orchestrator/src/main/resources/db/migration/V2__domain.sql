create table saga_instances
(
    id               uuid primary key,
    state            text    not null,
    step             int     not null,
    compensating     boolean not null default false,
    cancel_requested boolean not null default false,
    payload          jsonb   not null,
    current_command  uuid,
    attempts         int     not null default 0,
    deadline         timestamptz,
    version          bigint  not null default 0,
    last_error       text
);
create index saga_due on saga_instances (deadline) where state='RUNNING';
create table saga_steps
(
    id          bigserial primary key,
    saga_id     uuid not null,
    command_id  uuid not null,
    action      text not null,
    outcome     text not null,
    recorded_at timestamptz default now()
);

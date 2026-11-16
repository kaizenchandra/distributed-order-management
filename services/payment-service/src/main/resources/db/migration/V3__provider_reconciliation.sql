create table provider_operations
(
    operation_id    uuid primary key,
    payment_id      uuid        not null references payment_intents (id),
    kind            text        not null,
    request         jsonb       not null,
    command         jsonb,
    status          text        not null default 'PENDING'
        check (status in ('PENDING', 'SUBMITTING', 'UNKNOWN', 'SUCCEEDED', 'REJECTED', 'MANUAL_REVIEW')),
    attempts        int         not null default 0,
    next_attempt    timestamptz not null default now(),
    lease_token     uuid,
    lease_until     timestamptz,
    provider_result jsonb,
    last_error      text,
    created_at      timestamptz not null default now(),
    completed_at    timestamptz,
    sequence        bigserial unique
);
create index provider_due on provider_operations (next_attempt) where status in ('PENDING','UNKNOWN','SUBMITTING');
create index provider_payment_sequence on provider_operations (payment_id, sequence);

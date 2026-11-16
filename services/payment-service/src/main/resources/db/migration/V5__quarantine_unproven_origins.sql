-- Fail closed for pre-provider rows whose financial audit is absent/incomplete as well.
-- Native enqueue creates its intent and provider operation in the same transaction.
-- Do not modify already verified imports or in-flight migration identities.
update payment_intents p
set provider_origin='LEGACY_BLOCKED'
where provider_origin = 'NATIVE'
  and not exists(select 1 from provider_operations o where o.payment_id = p.id);

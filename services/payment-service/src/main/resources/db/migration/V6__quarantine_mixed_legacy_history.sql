-- A partly upgraded row may have new provider requests as well as older local-only effects.
-- It cannot be treated as native merely because at least one provider request exists.
update payment_intents p
set provider_origin='LEGACY_BLOCKED'
where provider_origin = 'NATIVE'
  and exists (select 1
              from payment_operations l
              where l.payment_id = p.id
                and not exists (select 1
                                from provider_operations r
                                where r.operation_id = l.operation_id
                                  and r.payment_id = p.id));
-- Mixed history stays quarantined: opening-balance import refuses any existing provider history.
-- Reconcile such accounts explicitly; never replay legacy captures to manufacture provenance.

#!/usr/bin/env python3
"""Verify migrated balances and replay an existing key without creating a new financial operation."""
import json
import urllib.request

from compose_support import run
from smoke import token, request, env

operator = token(timeout=60)
raw = run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'payment_service', '-At', '-c',
          "select json_build_object('id',payment_id,'digest',digest,'manifest',manifest)::text from legacy_migrations where status='VERIFIED' order by payment_id").stdout
rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
for row in rows:
    code, local = request(8093, '/api/v1/payments/' + row['id'], operator);
    assert code == 200
    req = urllib.request.Request('http://localhost:8100/internal/payments/' + row['id'],
                                 headers={'X-Provider-Key': env['PROVIDER_KEY']})
    with urllib.request.urlopen(req, timeout=5) as response:
        remote = json.load(response)
    for field in ['amount', 'currency', 'state', 'captured', 'refunded']: assert local[field] == remote[field] == \
                                                                                 row['manifest'][field], (row['id'],
                                                                                                          field)
    assert remote['operation_count'] == 0, remote
    historical = next((op for op in row['manifest']['operations'] if op['kind'] == 'Payment.Capture'), None)
    if historical:
        code, replay = request(8093, '/api/v1/payments/' + row['id'] + '/capture', operator, {},
                               historical['operationId']);
        assert code == 202 and replay['status'] == 'SUCCEEDED', (code, replay)
print(json.dumps({'verified_balances': len(rows), 'opening_balance_imports_are_not_provider_charges': True,
                  'historical_capture_replays_return_recorded_success': True}, indent=2))

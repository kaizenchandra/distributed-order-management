#!/usr/bin/env python3
"""Migrate only audited initial local-simulator balances; never executes financial operations."""
import json

from smoke import token, request, eventually

if __name__ == '__main__':
    admin = token(timeout=60);
    code, rows = request(8093, '/api/v1/admin/legacy-payments', admin);
    assert code == 200, (code, rows)
    verified = []
    for row in rows:
        code, value = request(8093, '/api/v1/admin/legacy-payments/' + row['id'] + '/prepare', admin,
                              {'source': 'oms-local-simulator-v1'})
        assert code == 200, (code, value);
        mid = value['id']


        def done():
            code, result = request(8093, '/api/v1/admin/legacy-payments/migrations/' + mid, admin)
            if code == 200 and result['status'] == 'MANUAL_REVIEW': raise RuntimeError(result)
            return result if code == 200 and result['status'] == 'VERIFIED' else None


        result = eventually(done, 120);
        verified.append(
            {'migrationId': mid, 'paymentId': row['id'], 'digest': result['digest'], 'status': result['status']})
    print(json.dumps({'verified': verified, 'count': len(verified)}, indent=2))

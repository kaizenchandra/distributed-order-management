#!/usr/bin/env python3
"""Black-box tests against the running Compose stack. Uses only Python standard library."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
env = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)


def request(port, path, token=None, body=None, key=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(f'http://localhost:{port}' + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read();
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def token(user=None, timeout=10):
    data = {'client_id': 'oms-demo', 'grant_type': 'password', 'username': user,
            'password': 'local-customer-only'} if user else {'client_id': 'oms-internal',
                                                             'client_secret': env['OMS_SERVICE_SECRET'],
                                                             'grant_type': 'client_credentials'}
    req = urllib.request.Request('http://localhost:8180/realms/oms/protocol/openid-connect/token',
                                 data=urllib.parse.urlencode(data).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.load(r)['access_token']


def eventually(fn, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = fn()
            if value: return value
        except (OSError, KeyError, AssertionError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('Timed out waiting for ' + str(fn))


if __name__ == '__main__':
    for port in [8091, 8092, 8093, 8094, 8095, 8096, 8097, 8099]: eventually(
        lambda: request(port, '/actuator/health')[0] == 200)
    customer = eventually(lambda: token('customer'));
    admin = token();
    other = token('other')
    assert request(8099, '/api/v1/orders')[0] == 401
    assert request(8095, '/api/v1/admin/sagas/' + str(uuid.uuid4()), customer)[0] == 403
    body = {'items': [{'sku': 'SKU-1', 'quantity': 2}, {'sku': 'SKU-2', 'quantity': 1}]}
    key = str(uuid.uuid4());
    status, created = request(8099, '/api/v1/orders', customer, body, key);
    assert status == 202, (status, created)
    oid = created['orderId'];
    assert request(8099, '/api/v1/orders', customer, body, key)[1]['orderId'] == oid
    assert request(8099, '/api/v1/orders', customer, {'items': [{'sku': 'SKU-1', 'quantity': 1}]}, key)[0] == 409


    def saga_state(oid, state):
        code, s = request(8095, '/api/v1/admin/sagas/' + oid, admin)
        if code == 200 and s['state'] == 'MANUAL_REVIEW': raise RuntimeError(s)
        return code == 200 and s['state'] == state


    eventually(lambda: saga_state(oid, 'COMPLETED'))
    assert request(8099, '/api/v1/orders/' + oid, customer)[1]['status'] == 'CONFIRMED'
    assert request(8099, '/api/v1/orders/' + oid, other)[0] == 403
    eventually(lambda: request(8096, '/api/v1/query/orders/' + oid, customer)[1].get('status') == 'CONFIRMED')
    assert request(8093, '/api/v1/payments/' + oid, admin)[1]['captured'] == 17500
    print('PASS authenticated checkout, authoritative pricing, idempotency, ownership, projection, capture')
    request(8099, '/api/v1/orders/' + oid + '/cancel', customer, {})
    eventually(lambda: saga_state(oid, 'COMPENSATED'))
    assert request(8093, '/api/v1/payments/' + oid, admin)[1]['refunded'] == 17500
    assert request(8099, '/api/v1/orders/' + oid, customer)[1]['status'] == 'CANCELLED'
    print('PASS cancellation, refund, allocation release, fulfillment cancellation')
    status, declined = request(8099, '/api/v1/orders', customer, dict(body, paymentScenario='decline'),
                               str(uuid.uuid4()));
    assert status == 202, (status, declined)
    eventually(lambda: saga_state(declined['orderId'], 'COMPENSATED'))
    print('PASS payment decline compensation')
    assert request(8096, '/api/v1/admin/projections/rebuild', admin, {})[0] == 200
    print('PASS projection rebuild')

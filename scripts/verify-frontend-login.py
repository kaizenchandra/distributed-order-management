#!/usr/bin/env python3
"""Verify customer authentication and gateway access through the frontend proxy."""
import json
import os
import urllib.request

base = os.environ.get('OMS_FRONTEND_URL', 'http://127.0.0.1:5173').rstrip('/')
body = urllib.parse.urlencode({
    'grant_type': 'password', 'client_id': 'oms-demo',
    'username': os.environ.get('OMS_CUSTOMER_USERNAME', 'customer'),
    'password': os.environ.get('OMS_CUSTOMER_PASSWORD', 'local-customer-only'),
}).encode()
request = urllib.request.Request(base + '/realms/oms/protocol/openid-connect/token', data=body)
with urllib.request.urlopen(request, timeout=20) as response:
    result = json.load(response)
assert result.get('access_token'), 'Keycloak did not return an access token'
print('PASS: customer authenticated through frontend Keycloak proxy')
for path in ['/api/v1/catalog/products', '/api/v1/query/customers/me/orders']:
    request = urllib.request.Request(base + path, headers={'Authorization': 'Bearer ' + result['access_token']})
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    assert isinstance(data, list), 'Expected a list from ' + path
    print('PASS:', path, '(' + str(len(data)) + ' records)')
print('Customer login and authenticated frontend API integration verified.')

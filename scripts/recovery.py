#!/usr/bin/env python3
"""Disrupts only OMS payment/Saga containers; run after smoke.py on a disposable stack."""
import json
import os
import subprocess
import sys
import uuid

from compose_support import command
from smoke import request, token, eventually, root


def compose(*args):
    if os.environ.get('OMS_HOST_MODE') == '1':
        action, *names = args
        if action == 'restart':
            subprocess.run([sys.executable, 'scripts/host-services.py', 'stop', *names], cwd=root, check=True)
            action = 'start'
        subprocess.run([sys.executable, 'scripts/host-services.py', action, *names], cwd=root, check=True)
        return
    if args[0] == 'start': args = ('up', '-d', '--no-deps', *args[1:])
    subprocess.run(command() + list(args), cwd=root, check=True, timeout=90)


def inspect(oid, admin):
    status, value = request(8095, '/api/v1/admin/sagas/' + oid, admin)
    return value if status == 200 and isinstance(value, dict) else {}


if __name__ == '__main__':
    if os.environ.get('OMS_HOST_MODE') != '1':
        subprocess.run([sys.executable, str(root / 'scripts/check-no-host.py')], check=True)
    customer = token('customer');
    admin = token()
    compose('stop', 'payment-service')
    try:
        status, order = request(8099, '/api/v1/orders', customer, {'items': [{'sku': 'SKU-1', 'quantity': 1}]},
                                str(uuid.uuid4()))
        assert status == 202, (status, order)
        oid = order['orderId']
        eventually(lambda: inspect(oid, admin).get('step') == 1)
        compose('restart', 'saga-orchestrator')
        compose('start', 'payment-service')
        eventually(lambda: inspect(oid, admin).get('state') == 'COMPLETED', 180)
        payment = request(8093, '/api/v1/payments/' + oid, admin)[1]
        assert payment['captured'] == 7500, payment
        print('PASS durable in-flight Saga restart and participant outage; captured 7500 once')
    finally:
        compose('start', 'payment-service', 'saga-orchestrator')
